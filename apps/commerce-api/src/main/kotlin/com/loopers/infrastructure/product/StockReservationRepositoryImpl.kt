package com.loopers.infrastructure.product

import com.loopers.domain.product.StockReservation
import com.loopers.domain.product.StockReservationRepository
import org.springframework.stereotype.Component

@Component
class StockReservationRepositoryImpl(
    private val stockReservationJpaRepository: StockReservationJpaRepository,
) : StockReservationRepository {
    override fun saveAll(reservations: List<StockReservation>): List<StockReservation> =
        stockReservationJpaRepository.saveAllAndFlush(reservations)

    override fun findAllByOrderId(orderId: Long): List<StockReservation> =
        stockReservationJpaRepository.findAllByOrderIdOrderByIdAsc(orderId)
}
