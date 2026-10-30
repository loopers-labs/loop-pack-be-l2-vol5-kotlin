package com.loopers.interfaces.consumer.order

data class OrderConfirmedEvent(
    val eventId: String,
    val orderId: Long,
    val userId: Long,
    val confirmedAt: String,
    val items: List<Item>,
    val eventType: String,
    val schemaVersion: Int,
) {
    data class Item(
        val productId: Long,
        val quantity: Int,
    )
}
