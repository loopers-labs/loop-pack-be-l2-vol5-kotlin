package com.loopers.application.product

import com.loopers.application.common.PageResult
import com.loopers.application.common.offset
import com.loopers.application.common.pageResult
import com.loopers.application.common.positiveId
import com.loopers.domain.brand.BrandRepository
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZonedDateTime

data class ProductResult(
    val id: Long,
    val name: String,
    val price: Long,
    val stock: Int,
    val brandId: Long,
    val brandName: String,
    val createdAt: ZonedDateTime,
    val updatedAt: ZonedDateTime,
)

@Service
class ProductApplicationService(
    private val products: ProductRepository,
    private val brands: BrandRepository,
) {
    fun create(brandId: Long, name: String, price: Long, stock: Int): ProductResult {
        positiveId(brandId)
        val brand = brands.findActive(brandId) ?: throw CommerceException(CommerceFailure.BRAND_NOT_FOUND)
        return result(products.save(Product(brandId, name, price, stock)), brand.name)
    }

    fun get(id: Long): ProductResult {
        positiveId(id)
        return result(products.findActive(id) ?: throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND))
    }

    fun list(brandId: Long?, page: Int, size: Int): PageResult<ProductResult> {
        if (brandId != null) positiveId(brandId)
        val offset = offset(page, size)
        val pageProducts = products.findActivePage(brandId, offset, size)
        val brandNames = brands.findActiveByIds(pageProducts.map { it.brandId }.distinct()).associate { it.id to it.name }
        val content = pageProducts.map { product ->
            result(product, brandNames[product.brandId] ?: throw CommerceException(CommerceFailure.BRAND_NOT_FOUND))
        }
        return pageResult(page, size, products.countActive(brandId), content)
    }

    @Transactional
    fun update(id: Long, name: String, price: Long): ProductResult {
        positiveId(id)
        val product = products.findActive(id) ?: throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND)
        product.changeDetails(name, price)
        return result(products.save(product))
    }

    @Transactional
    fun adjustStock(id: Long, stock: Int): ProductResult {
        positiveId(id)
        val product = products.findActive(id) ?: throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND)
        product.adjustStock(stock)
        return result(products.save(product))
    }

    fun delete(id: Long) {
        positiveId(id)
        val product = products.findActive(id) ?: throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND)
        product.delete()
        products.save(product)
    }

    private fun result(product: Product): ProductResult =
        result(product, brands.findActive(product.brandId)?.name ?: throw CommerceException(CommerceFailure.BRAND_NOT_FOUND))

    private fun result(product: Product, brandName: String) =
        ProductResult(
            product.id,
            product.name,
            product.price.amount,
            product.stock.remaining,
            product.brandId,
            brandName,
            product.createdAt,
            product.updatedAt,
        )
}
