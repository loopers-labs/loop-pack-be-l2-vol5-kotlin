package com.loopers.infrastructure.purchase

import com.loopers.domain.purchase.ProcessedOrderConfirmation
import org.springframework.data.jpa.repository.JpaRepository

interface ProcessedConfirmationJpaRepository : JpaRepository<ProcessedOrderConfirmation, Long> {
    fun findByEventId(eventId: String): ProcessedOrderConfirmation?
}
