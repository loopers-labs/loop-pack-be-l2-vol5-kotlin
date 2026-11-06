package com.loopers.domain.order

interface OrderEventOutbox {
    fun append(event: OrderConfirmedEvent)
}
