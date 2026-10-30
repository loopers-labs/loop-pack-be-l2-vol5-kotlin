package com.loopers.domain.product

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class StockReservationService(
    private val stockReservationRepository: StockReservationRepository,
    private val productService: ProductService,
) {
    @Transactional
    fun reserve(orderId: Long, commands: List<StockReservationCommand>): List<StockReservation> {
        if (stockReservationRepository.findAllByOrderId(orderId).isNotEmpty()) {
            throw alreadyReserved(orderId)
        }

        val reservations = commands.map { command ->
            val product = productService.getSellable(command.productId)
            productService.decreaseStock(product, command.quantity)

            StockReservation(
                orderId = orderId,
                productId = command.productId,
                quantity = command.quantity,
            )
        }

        return try {
            stockReservationRepository.saveAll(reservations)
        } catch (_: DataIntegrityViolationException) {
            throw alreadyReserved(orderId)
        }
    }

    @Transactional
    fun commit(orderId: Long): List<StockReservation> =
        stockReservationRepository.findAllByOrderId(orderId).onEach { it.commit() }

    @Transactional
    fun release(orderId: Long) {
        stockReservationRepository.findAllByOrderId(orderId).forEach { reservation ->
            if (reservation.release()) {
                productService.restoreStock(reservation.productId, reservation.quantity)
            }
        }
    }

    private fun alreadyReserved(orderId: Long): CoreException =
        CoreException(ErrorType.CONFLICT, "이미 재고를 예약한 주문입니다. [orderId = $orderId]")
}
