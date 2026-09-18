package com.loopers.domain.brand

import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.commerce.DeletableEntity
import com.loopers.domain.commerce.EntityState

class Brand private constructor(name: String, state: EntityState) : DeletableEntity(state) {
    constructor(name: String) : this(name, EntityState())

    var name: String = normalizedName(name)
        private set

    fun rename(name: String) {
        this.name = normalizedName(name)
    }

    companion object {
        fun reconstitute(name: String, state: EntityState): Brand = Brand(name, state)

        private fun normalizedName(value: String): String {
            val normalized = value.trim()
            if (normalized.isEmpty() || normalized.length > 255) throw CommerceException(CommerceFailure.INVALID_NAME)
            return normalized
        }
    }
}
