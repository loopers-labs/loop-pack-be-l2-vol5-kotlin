package com.loopers.domain.point

import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PointAccountTest {
    @Test
    fun `account requires a valid user identifier`() {
        assertThat(assertThrows<CommerceException> { PointAccount.open(0) }.reason).isEqualTo(CommerceFailure.INVALID_REQUEST)
    }

    @Test
    fun `charge and pay change only valid balances`() {
        val account = PointAccount.open(1)
        account.charge(10000)
        assertThat(account.balance.amount).isEqualTo(10000)
        account.pay(0)
        assertThat(account.balance.amount).isEqualTo(10000)
        account.pay(7000)
        assertThat(account.balance.amount).isEqualTo(3000)
        assertThat(assertThrows<CommerceException> { account.pay(4000) }.reason).isEqualTo(CommerceFailure.INSUFFICIENT_POINTS)
        assertThat(account.balance.amount).isEqualTo(3000)
    }

    @Test
    fun `invalid charge and overflow preserve balance`() {
        val account = PointAccount.open(1)
        for (invalid in listOf(0L, -1L)) {
            assertThat(assertThrows<CommerceException> { account.charge(invalid) }.reason).isEqualTo(CommerceFailure.INVALID_REQUEST)
        }
        account.charge(Long.MAX_VALUE)
        assertThat(assertThrows<CommerceException> { account.charge(1) }.reason).isEqualTo(CommerceFailure.POINT_BALANCE_OVERFLOW)
        assertThat(account.balance.amount).isEqualTo(Long.MAX_VALUE)
    }
}
