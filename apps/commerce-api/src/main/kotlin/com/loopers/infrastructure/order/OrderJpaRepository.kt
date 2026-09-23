package com.loopers.infrastructure.order

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface OrderJpaRepository : JpaRepository<OrderJpaEntity, Long> {
    fun findByUserId(userId: Long, pageable: Pageable): Page<OrderJpaEntity>

    fun countByUserId(userId: Long): Long
}
