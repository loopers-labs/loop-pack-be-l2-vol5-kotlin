package com.loopers.domain.product

data class StockReservationCommand(
    val productId: Long,
    val quantity: Int,
)
