package com.loopers.domain.order

import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.product.Money

class OrderItem(line: OrderLine) {
    val id: Long = line.id

    val productId: Long = line.productId

    val quantity: Int = line.quantity

    val unitPrice: Money = Money(line.unitPrice)

    val lineAmount: Money = unitPrice.multiply(line.quantity)

    init {
        if (line.productId <= 0) throw CommerceException(CommerceFailure.INVALID_REQUEST)
    }
}
