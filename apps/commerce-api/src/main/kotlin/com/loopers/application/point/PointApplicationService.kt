package com.loopers.application.point

import com.loopers.application.common.positiveId
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.point.PointAccount
import com.loopers.domain.point.PointAccountRepository
import com.loopers.domain.user.UserRepository
import org.springframework.stereotype.Service

data class PointResult(val userId: Long, val balance: Long)

@Service
class PointApplicationService(
    private val users: UserRepository,
    private val accounts: PointAccountRepository,
) {
    fun get(userId: Long): PointResult {
        positiveId(userId)
        if (users.find(userId) == null) throw CommerceException(CommerceFailure.USER_NOT_FOUND)
        return PointResult(userId, accounts.findByUserId(userId)?.balance?.amount ?: 0)
    }

    fun charge(userId: Long, amount: Long): PointResult {
        positiveId(userId)
        if (users.find(userId) == null) throw CommerceException(CommerceFailure.USER_NOT_FOUND)
        val account = accounts.findByUserId(userId) ?: PointAccount.open(userId)
        account.charge(amount)
        val saved = accounts.save(account)
        return PointResult(saved.userId, saved.balance.amount)
    }
}
