package com.loopers.application.common

import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure

data class PageResult<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

fun <T> pageResult(page: Int, size: Int, total: Long, content: List<T>): PageResult<T> {
    if (page < 0 || size !in 1..100 || page.toLong() * size > Int.MAX_VALUE) {
        throw CommerceException(CommerceFailure.INVALID_REQUEST)
    }
    return PageResult(content, page, size, total, ((total + size - 1) / size).toInt())
}

fun offset(page: Int, size: Int): Int {
    if (page < 0 || size !in 1..100 || page.toLong() * size > Int.MAX_VALUE) {
        throw CommerceException(CommerceFailure.INVALID_REQUEST)
    }
    return page * size
}

fun positiveId(id: Long) {
    if (id <= 0) throw CommerceException(CommerceFailure.INVALID_REQUEST)
}
