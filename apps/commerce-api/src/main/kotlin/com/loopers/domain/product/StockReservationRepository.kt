package com.loopers.domain.product

interface StockReservationRepository {
    fun saveAll(reservations: List<StockReservation>): List<StockReservation>

    fun findAllByOrderId(orderId: Long): List<StockReservation>
}
