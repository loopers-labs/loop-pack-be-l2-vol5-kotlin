package com.loopers.domain.order

interface OrderRepository {
    fun save(order: Order): Order

    fun find(id: Long): Order?

    fun findPage(userId: Long?, offset: Int, limit: Int): List<Order>

    fun count(userId: Long?): Long
}
