package com.loopers.infrastructure.product

import com.loopers.domain.product.StockReservation
import org.springframework.data.jpa.repository.JpaRepository

interface StockReservationJpaRepository : JpaRepository<StockReservation, Long> {
    fun findAllByOrderIdOrderByIdAsc(orderId: Long): List<StockReservation>
}
