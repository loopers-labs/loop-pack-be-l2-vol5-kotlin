package com.loopers.domain.user

import com.loopers.domain.commerce.EntityState
import java.time.ZonedDateTime

class User private constructor(
    val name: String,
    state: EntityState,
) {
    constructor(name: String) : this(name, EntityState())

    val id: Long = state.id
    val createdAt: ZonedDateTime = state.createdAt
    val updatedAt: ZonedDateTime = state.updatedAt

    companion object {
        fun reconstitute(name: String, state: EntityState): User = User(name, state)
    }
}
