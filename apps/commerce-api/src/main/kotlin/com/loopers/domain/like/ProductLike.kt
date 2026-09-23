package com.loopers.domain.like

import java.time.ZonedDateTime

class ProductLike(
    val userId: Long,
    val productId: Long,
    val id: Long = 0,
    val likedAt: ZonedDateTime = ZonedDateTime.now(),
)
