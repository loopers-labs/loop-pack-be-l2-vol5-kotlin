package com.loopers.domain.point

import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure

data class PointBalance(val amount: Long) {
    init {
        if (amount < 0) throw CommerceException(CommerceFailure.INVALID_REQUEST)
    }

    fun charge(amount: Long): PointBalance {
        if (amount <= 0) throw CommerceException(CommerceFailure.INVALID_REQUEST)
        val sum = try {
            Math.addExact(this.amount, amount)
        } catch (_: ArithmeticException) {
            throw CommerceException(CommerceFailure.POINT_BALANCE_OVERFLOW)
        }
        return PointBalance(sum)
    }

    fun pay(amount: Long): PointBalance {
        if (amount < 0) throw CommerceException(CommerceFailure.INVALID_REQUEST)
        if (amount > this.amount) throw CommerceException(CommerceFailure.INSUFFICIENT_POINTS)
        return PointBalance(this.amount - amount)
    }
}
