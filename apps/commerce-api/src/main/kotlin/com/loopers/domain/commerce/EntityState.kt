package com.loopers.domain.commerce

import java.time.ZonedDateTime

data class EntityState(
    val id: Long = 0,
    val createdAt: ZonedDateTime = ZonedDateTime.now(),
    val updatedAt: ZonedDateTime = createdAt,
    val deletedAt: ZonedDateTime? = null,
)

abstract class DeletableEntity(state: EntityState) {
    val id: Long = state.id
    val createdAt: ZonedDateTime = state.createdAt
    val updatedAt: ZonedDateTime = state.updatedAt

    var deletedAt: ZonedDateTime? = state.deletedAt
        private set

    fun delete() {
        if (deletedAt == null) deletedAt = ZonedDateTime.now()
    }

    fun restore() {
        deletedAt = null
    }
}
