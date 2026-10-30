package com.loopers.domain.order

data class OrderConfirmedEvent(
    val eventId: String,
    val orderId: Long,
    val userId: Long,
    val confirmedAt: String,
    val items: List<Item>,
    val eventType: String = TYPE,
    val schemaVersion: Int = UNSTAMPED,
) {
    data class Item(
        val productId: Long,
        val quantity: Int,
    )

    companion object {
        const val TYPE = "OrderConfirmed"

        /** 발행 직전에 설정값으로 찍힌다. 계약의 버전이지 업무 사실이 아니다. */
        const val UNSTAMPED = 0

        fun idOf(orderId: Long): String = "order-confirmed:v1:$orderId"
    }
}
