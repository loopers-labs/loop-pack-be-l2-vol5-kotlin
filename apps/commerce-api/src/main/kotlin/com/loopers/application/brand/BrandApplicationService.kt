package com.loopers.application.brand

import com.loopers.application.common.PageResult
import com.loopers.application.common.offset
import com.loopers.application.common.pageResult
import com.loopers.application.common.positiveId
import com.loopers.domain.brand.Brand
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.product.ProductRepository
import org.springframework.stereotype.Service
import java.time.ZonedDateTime

data class BrandResult(val id: Long, val name: String, val createdAt: ZonedDateTime, val updatedAt: ZonedDateTime)

@Service
class BrandApplicationService(
    private val brands: BrandRepository,
    private val products: ProductRepository,
) {
    fun create(name: String): BrandResult = result(brands.save(Brand(name)))

    fun get(id: Long): BrandResult {
        positiveId(id)
        return result(brands.findActive(id) ?: throw CommerceException(CommerceFailure.BRAND_NOT_FOUND))
    }

    fun list(page: Int, size: Int): PageResult<BrandResult> {
        val offset = offset(page, size)
        return pageResult(page, size, brands.countActive(), brands.findActivePage(offset, size).map(::result))
    }

    fun update(id: Long, name: String): BrandResult {
        positiveId(id)
        val brand = brands.findActive(id) ?: throw CommerceException(CommerceFailure.BRAND_NOT_FOUND)
        brand.rename(name)
        return result(brands.save(brand))
    }

    fun delete(id: Long) {
        positiveId(id)
        val brand = brands.findActive(id) ?: throw CommerceException(CommerceFailure.BRAND_NOT_FOUND)
        if (products.existsActiveByBrandId(id)) throw CommerceException(CommerceFailure.BRAND_HAS_ACTIVE_PRODUCTS)
        brand.delete()
        brands.save(brand)
    }

    private fun result(brand: Brand) = BrandResult(brand.id, brand.name, brand.createdAt, brand.updatedAt)
}
