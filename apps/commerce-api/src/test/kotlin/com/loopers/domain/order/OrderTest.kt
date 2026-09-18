package com.loopers.domain.order

import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.product.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OrderTest {
    @Test
    fun `order snapshots sorted items and confirms exactly once`() {
        val order = Order(1, listOf(OrderLine(3, 1, 2000), OrderLine(2, 2, 1000)))
        assertThat(order.items.map { it.productId }).containsExactly(2, 3)
        assertThat(order.totalAmount.amount).isEqualTo(4000)
        assertThat(order.status).isEqualTo(OrderStatus.DRAFT)
        assertThat(order.paymentAmount).isNull()
        order.confirm()
        assertThat(order.status).isEqualTo(OrderStatus.CONFIRMED)
        assertThat(order.paymentAmount?.amount).isEqualTo(4000)
        assertThat(order.paymentResult).isEqualTo(PaymentResult.SUCCESS)
        assertThat(assertThrows<CommerceException> { order.confirm() }.reason).isEqualTo(CommerceFailure.ORDER_ALREADY_CONFIRMED)
    }

    @Test
    fun `money rejects negative value and arithmetic overflow`() {
        assertThat(Money(0).amount).isZero()
        assertThat(assertThrows<CommerceException> { Money(-1) }.reason).isEqualTo(CommerceFailure.INVALID_PRICE)
        assertThat(assertThrows<CommerceException> { Money(Long.MAX_VALUE).add(Money(1)) }.reason).isEqualTo(CommerceFailure.AMOUNT_OVERFLOW)
        assertThat(assertThrows<CommerceException> { Money(Long.MAX_VALUE).multiply(2) }.reason).isEqualTo(CommerceFailure.AMOUNT_OVERFLOW)
    }
}
