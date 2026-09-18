package com.loopers.domain.brand

import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BrandTest {
    @Test
    fun `normalizes name and rejects invalid boundaries`() {
        val brand = Brand("  브랜드  ")
        assertThat(brand.name).isEqualTo("브랜드")
        brand.rename("  새 이름 ")
        assertThat(brand.name).isEqualTo("새 이름")
        assertThat(Brand("a".repeat(255)).name).hasSize(255)
        for (invalid in listOf("   ", "a".repeat(256))) {
            assertThat(assertThrows<CommerceException> { Brand(invalid) }.reason).isEqualTo(CommerceFailure.INVALID_NAME)
        }
    }

    @Test
    fun `failed rename preserves old name`() {
        val brand = Brand("원래 이름")
        assertThrows<CommerceException> { brand.rename(" ") }
        assertThat(brand.name).isEqualTo("원래 이름")
    }
}
