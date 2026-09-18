package com.loopers.domain.point

import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure

class PointAccount private constructor(
    val userId: Long,
    balance: Long,
) {
    var balance: PointBalance = PointBalance(balance)
        private set

    init {
        if (userId <= 0) throw CommerceException(CommerceFailure.INVALID_REQUEST)
    }

    fun charge(amount: Long) {
        balance = balance.charge(amount)
    }

    fun pay(amount: Long) {
        balance = balance.pay(amount)
    }

    fun assertCanPay(amount: Long) {
        balance.pay(amount)
    }

    companion object {
        fun open(userId: Long): PointAccount = PointAccount(userId, 0)

        fun reconstitute(userId: Long, balance: Long): PointAccount = PointAccount(userId, balance)
    }
}
