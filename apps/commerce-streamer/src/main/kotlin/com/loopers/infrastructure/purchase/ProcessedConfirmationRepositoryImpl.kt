package com.loopers.infrastructure.purchase

import com.loopers.domain.purchase.ProcessedConfirmationRepository
import com.loopers.domain.purchase.ProcessedOrderConfirmation
import org.springframework.stereotype.Component

@Component
class ProcessedConfirmationRepositoryImpl(
    private val processedConfirmationJpaRepository: ProcessedConfirmationJpaRepository,
) : ProcessedConfirmationRepository {
    override fun save(processed: ProcessedOrderConfirmation): ProcessedOrderConfirmation =
        processedConfirmationJpaRepository.saveAndFlush(processed)

    override fun findByEventId(eventId: String): ProcessedOrderConfirmation? =
        processedConfirmationJpaRepository.findByEventId(eventId)
}
