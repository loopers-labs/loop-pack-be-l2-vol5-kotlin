package com.loopers.domain.purchase

import java.time.ZonedDateTime

data class PurchaseActivityCommand(
    val eventId: String,
    val orderId: Long,
    val userId: Long,
    val confirmedAt: ZonedDateTime,
    val items: List<Item>,
) {
    data class Item(
        val productId: Long,
        val quantity: Int,
    )
}
