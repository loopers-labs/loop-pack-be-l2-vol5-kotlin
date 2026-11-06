package com.loopers.domain.outbox

interface OutboxEventRepository {
    fun save(event: OutboxEvent): OutboxEvent

    fun findPendingAfter(id: Long, limit: Int): List<OutboxEvent>

    fun markSent(eventId: String)
}
