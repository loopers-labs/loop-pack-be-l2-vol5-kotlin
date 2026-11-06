package com.loopers.infrastructure.outbox

import com.loopers.domain.outbox.OutboxEvent
import com.loopers.domain.outbox.OutboxStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface OutboxEventJpaRepository : JpaRepository<OutboxEvent, Long> {
    fun findByStatusAndIdGreaterThanOrderByIdAsc(status: OutboxStatus, id: Long, pageable: Pageable): List<OutboxEvent>

    @Transactional
    @Modifying
    @Query("update OutboxEvent e set e.status = :to where e.eventId = :eventId and e.status = :from")
    fun updateStatus(
        @Param("eventId") eventId: String,
        @Param("from") from: OutboxStatus,
        @Param("to") to: OutboxStatus,
    ): Int
}
