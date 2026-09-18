package com.loopers.interfaces.api

import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure

fun requesterId(header: String?): Long {
    if (header == null) throw CommerceException(CommerceFailure.AUTHENTICATION_REQUIRED)
    val id = header.toLongOrNull() ?: throw CommerceException(CommerceFailure.INVALID_REQUEST)
    if (id <= 0) throw CommerceException(CommerceFailure.INVALID_REQUEST)
    return id
}
