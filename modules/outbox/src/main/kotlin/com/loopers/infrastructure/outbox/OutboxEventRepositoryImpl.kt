package com.loopers.infrastructure.outbox

import com.loopers.domain.outbox.OutboxEvent
import com.loopers.domain.outbox.OutboxEventRepository
import com.loopers.domain.outbox.OutboxStatus
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component

@Component
class OutboxEventRepositoryImpl(
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
) : OutboxEventRepository {
    override fun save(event: OutboxEvent): OutboxEvent = outboxEventJpaRepository.save(event)

    override fun findPendingAfter(id: Long, limit: Int): List<OutboxEvent> =
        outboxEventJpaRepository.findByStatusAndIdGreaterThanOrderByIdAsc(
            OutboxStatus.PENDING,
            id,
            PageRequest.of(0, limit),
        )

    override fun markSent(eventId: String) {
        outboxEventJpaRepository.updateStatus(eventId, OutboxStatus.PENDING, OutboxStatus.SENT)
    }
}
