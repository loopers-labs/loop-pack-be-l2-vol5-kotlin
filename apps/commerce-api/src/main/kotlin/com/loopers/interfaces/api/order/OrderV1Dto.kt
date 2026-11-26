package com.loopers.interfaces.api.order

class OrderV1Dto {
    data class CreateRequest(
        val lines: List<Line>,
    ) {
        data class Line(
            val productId: Long,
            val quantity: Int,
        )
    }

    data class ConfirmRequest(
        val method: String,
        val cardType: String? = null,
        val cardNo: String? = null,
    )

    data class ReconcileResponse(
        val orderId: Long,
        val outcome: String,
        val paymentStatus: String?,
        val transactionKey: String?,
    )

    data class OrderResponse(
        val id: Long,
        val status: String,
        val totalAmount: Long,
        val paidAmount: Long?,
        val lines: List<Item>,
    ) {
        data class Item(
            val productId: Long,
            val quantity: Int,
            val unitPrice: Long,
            val amount: Long,
        )
    }
}
