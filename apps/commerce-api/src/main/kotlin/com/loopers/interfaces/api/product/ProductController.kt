package com.loopers.interfaces.api.product

import com.loopers.application.common.PageResult
import com.loopers.application.product.CustomerProductQueryService
import com.loopers.application.product.CustomerProductResult
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.brand.BrandResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.ZonedDateTime

data class CustomerProductResponse(
    val id: Long,
    val name: String,
    val price: Long,
    val stock: Int,
    val brand: BrandResponse,
    val likeCount: Long,
    val createdAt: ZonedDateTime,
) {
    companion object {
        fun from(result: CustomerProductResult) =
            CustomerProductResponse(
                result.id,
                result.name,
                result.price,
                result.stock,
                BrandResponse(result.brandId, result.brandName),
                result.likeCount,
                result.createdAt,
            )
    }
}

@RestController
@RequestMapping("/api/v1/products")
class ProductController(private val products: CustomerProductQueryService) {
    @GetMapping
    fun list(
        @RequestParam(required = false) brandId: Long?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "latest") sort: String,
    ): ApiResponse<PageResult<CustomerProductResponse>> {
        val result = products.list(brandId, page, size, sort)
        return ApiResponse.success(
            PageResult(
                result.content.map(CustomerProductResponse::from),
                result.page,
                result.size,
                result.totalElements,
                result.totalPages,
            ),
        )
    }

    @GetMapping("/{productId}")
    fun get(@PathVariable productId: Long): ApiResponse<CustomerProductResponse> =
        ApiResponse.success(CustomerProductResponse.from(products.get(productId)))
}
