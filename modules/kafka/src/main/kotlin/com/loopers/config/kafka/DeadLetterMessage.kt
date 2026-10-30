package com.loopers.config.kafka

import com.fasterxml.jackson.core.JsonProcessingException
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.core.NestedExceptionUtils
import java.sql.SQLException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private const val MAX_LINE = 200
private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

internal fun deadLetterMessage(
    record: ConsumerRecord<*, *>,
    exception: Exception,
    group: String?,
    alert: DeadLetterAlert?,
    landed: String?,
    now: Instant = Instant.now(),
): String {
    val cause = NestedExceptionUtils.getMostSpecificCause(exception)

    return listOf(
        "'${alert?.label ?: group ?: "컨슈머"}' 도중 ${stageOf(cause)} DLT로 격리하였다.",
        "시각    발행 ${publishedAt(record)} · 실패까지 ${elapsedOf(record, now)}",
        "이벤트  ${described(record, alert)}",
        "원인    ${cause.javaClass.simpleName}: ${oneLine(cause.message)}",
        "원본    ${record.topic()}-${record.partition()}@${record.offset()}",
        "DLT     ${landed ?: "착지점 확인 못 함"}",
        "그룹    ${group ?: "알 수 없음"}",
    ).joinToString("\n")
}

private fun stageOf(cause: Throwable): String = when (cause) {
    is JsonProcessingException, is DateTimeParseException, is IllegalArgumentException -> "이벤트를 해석하지 못해"
    is SQLException -> "저장하지 못해"
    else -> "처리하지 못해"
}

private fun described(record: ConsumerRecord<*, *>, alert: DeadLetterAlert?): String =
    alert?.let { runCatching { oneLine(it.describe(record)) }.getOrNull() }
        ?: "해석 불가 (key=${record.key()})"

private fun publishedAt(record: ConsumerRecord<*, *>): String =
    if (record.timestamp() < 0) {
        "알 수 없음"
    } else {
        STAMP.format(Instant.ofEpochMilli(record.timestamp()).atZone(ZoneId.systemDefault()))
    }

private fun elapsedOf(record: ConsumerRecord<*, *>, now: Instant): String {
    if (record.timestamp() < 0) return "알 수 없음"

    val millis = now.toEpochMilli() - record.timestamp()

    return when {
        millis < 0 -> "알 수 없음"
        millis < 60_000 -> "${millis / 1_000}초"
        millis < 3_600_000 -> "${millis / 60_000}분"
        else -> "${millis / 3_600_000}시간"
    }
}

private fun oneLine(text: String?): String =
    text?.lineSequence()?.firstOrNull()?.trim()?.take(MAX_LINE) ?: "내용 없음"
