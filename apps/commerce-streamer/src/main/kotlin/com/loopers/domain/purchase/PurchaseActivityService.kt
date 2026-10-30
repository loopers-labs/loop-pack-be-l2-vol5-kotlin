package com.loopers.domain.purchase

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class PurchaseActivityService(
    private val processedConfirmationRepository: ProcessedConfirmationRepository,
    private val purchaseActivityRepository: PurchaseActivityRepository,
) {
    @Transactional
    fun record(command: PurchaseActivityCommand) {
        processedConfirmationRepository.save(
            ProcessedOrderConfirmation(eventId = command.eventId, orderId = command.orderId),
        )

        purchaseActivityRepository.saveAll(
            command.items.map {
                ProductPurchaseActivity(
                    userId = command.userId,
                    orderId = command.orderId,
                    productId = it.productId,
                    quantity = it.quantity,
                    confirmedAt = command.confirmedAt,
                )
            },
        )
    }

    @Transactional(readOnly = true)
    fun conflictsWith(command: PurchaseActivityCommand): Boolean {
        val stored = purchaseActivityRepository.findAllByOrderId(command.orderId)

        if (stored.isEmpty()) return command.items.isNotEmpty()

        return stored.any { it.userId != command.userId } ||
            stored.any { it.confirmedAt.toInstant() != command.confirmedAt.toInstant() } ||
            stored.map { it.productId to it.quantity }.toSet() !=
            command.items.map { it.productId to it.quantity }.toSet()
    }
}
