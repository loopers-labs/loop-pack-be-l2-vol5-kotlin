package com.loopers.config.kafka

import com.fasterxml.jackson.core.JsonProcessingException
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.listener.ConsumerRecordRecoverer
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.kafka.support.SendResult
import org.springframework.util.backoff.BackOff
import java.time.format.DateTimeParseException
import java.util.concurrent.CompletableFuture

private val log = LoggerFactory.getLogger(RetryThenDeadLetterErrorHandler::class.java)

private class LandingAwareRecoverer(
    deadLetters: KafkaOperations<*, *>,
) : DeadLetterPublishingRecoverer(deadLetters) {
    private val landed = ThreadLocal<String?>()

    fun landed(): String? = landed.get()

    override fun verifySendResult(
        kafkaTemplate: KafkaOperations<Any, Any>,
        outRecord: ProducerRecord<Any, Any>,
        sendResult: CompletableFuture<SendResult<Any, Any>>?,
        inRecord: ConsumerRecord<*, *>,
    ) {
        landed.remove()
        super.verifySendResult(kafkaTemplate, outRecord, sendResult, inRecord)

        sendResult?.getNow(null)?.recordMetadata?.let {
            landed.set("${it.topic()}-${it.partition()}@${it.offset()}")
        }
    }
}

private fun quarantining(
    deadLetters: KafkaOperations<*, *>,
    alerts: List<DeadLetterAlert>,
): ConsumerRecordRecoverer {
    val publishing = LandingAwareRecoverer(deadLetters)
    val byGroup = alerts.associateBy { it.groupId }

    return ConsumerRecordRecoverer { record, exception ->
        publishing.accept(record, exception)

        val group = (exception as? ListenerExecutionFailedException)?.groupId
        log.error(deadLetterMessage(record, exception, group, byGroup[group], publishing.landed()), exception)
    }
}

class RetryThenDeadLetterErrorHandler(
    deadLetters: KafkaOperations<*, *>,
    backOff: BackOff,
    alerts: List<DeadLetterAlert> = emptyList(),
) : DefaultErrorHandler(quarantining(deadLetters, alerts), backOff) {
    init {
        setCommitRecovered(true)
        addNotRetryableExceptions(
            JsonProcessingException::class.java,
            DateTimeParseException::class.java,
        )
    }

    companion object {
        const val TOPIC_SUFFIX = "-dlt"
    }
}
