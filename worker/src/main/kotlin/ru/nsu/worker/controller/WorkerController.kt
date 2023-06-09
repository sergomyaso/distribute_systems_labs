package ru.nsu.worker.controller

import com.rabbitmq.client.CancelCallback
import com.rabbitmq.client.Connection
import com.rabbitmq.client.DeliverCallback
import com.rabbitmq.client.Delivery
import jakarta.xml.bind.JAXBContext
import jakarta.xml.bind.JAXBException
import jakarta.xml.bind.Marshaller
import org.paukov.combinatorics.CombinatoricsFactory
import org.paukov.combinatorics.Generator
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.*
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.nsu.ccfit.schema.crack_hash_response.CrackHashManagerRequest
import ru.nsu.ccfit.schema.crack_hash_response.CrackHashWorkerResponse
import ru.nsu.ccfit.schema.crack_hash_response.CrackHashWorkerResponse.Answers
import ru.nsu.worker.configrutaions.RabbitQueue
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.StringWriter
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.pow

@EnableAutoConfiguration
@RestController
@RequestMapping("/internal/api/worker/hash/crack")
class WorkerController @Autowired constructor(
    private val rabbitConn: Connection,
    private val workerQueue: RabbitQueue,
) {
    private val queueManagerResp: ConcurrentLinkedQueue<CrackHashManagerRequest> = ConcurrentLinkedQueue()
    val WORKER_EXCHANGE = "worker_exchange"
    val MANAGE_EXCHANGE = "manager_exchange"

    init {
        rabbitConn.createChannel().use {
            val deliverCallback = DeliverCallback { consumerTag: String?, delivery: Delivery? ->
                val message = delivery?.body?.let { String(it, Charsets.UTF_8) }
                println("Received '$message'")

                val jaxbContext = JAXBContext.newInstance(CrackHashManagerRequest::class.java)
                val unmarshaller = jaxbContext.createUnmarshaller()
                val resp: CrackHashManagerRequest;
                if (message != null) {
                    resp =
                        unmarshaller.unmarshal(ByteArrayInputStream(message.toByteArray())) as CrackHashManagerRequest
                    queueManagerResp.add(resp);
                }
                it.basicAck(delivery?.envelope?.deliveryTag ?: 0, false)
            }

            it.basicConsume(workerQueue.queueName, false, deliverCallback, CancelCallback { })
        }
    }

    private val restTemplate = RestTemplateBuilder()
        .rootUri("http://manager:8080")
        .requestFactory(HttpComponentsClientHttpRequestFactory::class.java)
        .setConnectTimeout(Duration.ofMillis(500))
        .setReadTimeout(Duration.ofMillis(500))
        .build()

    private val executorService = Executors.newCachedThreadPool()

    var logger: Logger? = LoggerFactory.getLogger(WorkerController::class.java)

    @PostMapping("/task")
    fun submitTask(@RequestBody taskRequest: CrackHashManagerRequest): ResponseEntity<String> {
        return try {
            executorService.submit { doWork(taskRequest) }
            logger?.info("Task [${taskRequest.requestId}] in work")
            ResponseEntity.ok("Task in work")
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.message)
        }
    }

    private fun doWork(taskRequest: CrackHashManagerRequest) {
        // Calculate word range to test
        val partSize = calculatePartSize(taskRequest.maxLength, taskRequest.partCount)
        val startIdx = taskRequest.partNumber * partSize
        var endIdx = (taskRequest.partNumber + 1) * partSize - 1
        if (endIdx > calculateWordSpace(taskRequest.maxLength)) {
            endIdx = calculateWordSpace(taskRequest.maxLength) - 1
        }

        // Generate and test words
        val matchedWords: MutableList<String> = ArrayList()
        val md: MessageDigest = MessageDigest.getInstance("MD5")
        for (word in generateWords(startIdx, endIdx, taskRequest.maxLength, taskRequest.alphabet.symbols)) {
            val md5Word = BigInteger(1, md.digest(word.toByteArray())).toString(16).padStart(32, '0')
            if (md5Word == taskRequest.hash) {
                matchedWords.add(word)
            }
        }
        doneWork(matchedWords, taskRequest.requestId, taskRequest.partNumber)
    }

    private fun doneWork(matchedWords: List<String>, requestId: String, partNumber: Int) {
        // Send response to manager
        val taskResponse = CrackHashWorkerResponse()
        taskResponse.requestId = requestId
        taskResponse.answers = convertToAnswers(matchedWords)
        taskResponse.partNumber = partNumber
        sendResponseToManagerQueue(taskResponse)

        sendResponseToManagerQueue(taskResponse)
//        val entity = HttpEntity(taskResponse)
//        val response = restTemplate.exchange(
//            "/internal/api/manager/hash/crack/request", HttpMethod.PATCH, entity,
//            String::class.java
//        )
    }

    private fun sendResponseToManagerQueue(req: CrackHashWorkerResponse) {
//        val jaxbContext = JAXBContext.newInstance(CrackHashWorkerResponse::class.java)
//        val marshaller = jaxbContext.createMarshaller()
//
        rabbitConn.createChannel().basicPublish(WORKER_EXCHANGE, "", null, req.toString().toByteArray())
    }

    private fun convertToAnswers(words: List<String>): Answers {
        val answers = Answers()
        answers.words.addAll(words)
        return answers
    }

    // Helper methods for calculating word range and generating words

    private fun calculatePartSize(maxWordLength: Int, partCount: Int): Long {
        val wordSpace = calculateWordSpace(maxWordLength)
        return ceil(wordSpace.toDouble() / partCount).toLong()
    }

    private fun calculateWordSpace(maxWordLength: Int): Long {
        val alphabetSize = 36 // 26 letters + 10 digits
        var wordSpace = 0L
        for (i in 1..maxWordLength) {
            wordSpace += alphabetSize.toDouble().pow(i.toDouble()).toLong()
        }
        return wordSpace
    }

    @Scheduled(fixedRate = 30)
    fun checkQueueWorkerResponses() {
        if (queueManagerResp.size > 0) {
            executorService.submit { doWork(queueManagerResp.poll()) }
        }
    }

    private fun generateWords(startIdx: Long, endIdx: Long, maxWordLength: Int, alphabet: List<String>): List<String> {
        val vector = CombinatoricsFactory.createVector(alphabet)
        val gen: Generator<String> =
            CombinatoricsFactory.createPermutationWithRepetitionGenerator(vector, maxWordLength)
        val words = ArrayList<String>()
        for (word in gen.generateObjectsRange(startIdx, endIdx)) {
            words.add(java.lang.String.join("", word.vector))
        }
        return words
    }

    companion object {
        private const val RESPONSE_XSD_PATH = "classpath:scheme.xsd"
    }
}