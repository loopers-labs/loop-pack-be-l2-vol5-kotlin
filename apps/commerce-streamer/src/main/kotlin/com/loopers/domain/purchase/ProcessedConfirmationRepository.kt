package com.loopers.domain.purchase

interface ProcessedConfirmationRepository {
    fun save(processed: ProcessedOrderConfirmation): ProcessedOrderConfirmation

    fun findByEventId(eventId: String): ProcessedOrderConfirmation?
}
