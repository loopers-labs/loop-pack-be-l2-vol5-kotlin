package com.loopers.domain.product

import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
data class Money(
    val amount: Long,
) {
    init {
        if (amount < 0) throw CommerceException(CommerceFailure.INVALID_PRICE)
    }

    fun add(other: Money): Money = try {
        Money(Math.addExact(amount, other.amount))
    } catch (_: ArithmeticException) {
        throw CommerceException(CommerceFailure.AMOUNT_OVERFLOW)
    }

    fun multiply(quantity: Int): Money {
        if (quantity <= 0) throw CommerceException(CommerceFailure.INVALID_QUANTITY)
        return try {
            Money(Math.multiplyExact(amount, quantity.toLong()))
        } catch (_: ArithmeticException) {
            throw CommerceException(CommerceFailure.AMOUNT_OVERFLOW)
        }
    }
}
