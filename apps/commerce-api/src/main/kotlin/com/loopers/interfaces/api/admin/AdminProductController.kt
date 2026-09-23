package com.loopers.interfaces.api.admin

import com.loopers.application.common.PageResult
import com.loopers.application.product.ProductApplicationService
import com.loopers.application.product.ProductResult
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.brand.BrandResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.ZonedDateTime

data class CreateProductRequest(val brandId: Long, val name: String, val price: Long, val stock: Int)

data class UpdateProductRequest(val name: String, val price: Long)

data class AdjustStockRequest(val stock: Int)

data class AdminProductResponse(
    val id: Long,
    val name: String,
    val price: Long,
    val stock: Int,
    val brand: BrandResponse,
    val createdAt: ZonedDateTime,
    val updatedAt: ZonedDateTime,
) {
    companion object {
        fun from(result: ProductResult) =
            AdminProductResponse(
                result.id,
                result.name,
                result.price,
                result.stock,
                BrandResponse(result.brandId, result.brandName),
                result.createdAt,
                result.updatedAt,
            )
    }
}

@RestController
@RequestMapping("/api-admin/v1/products")
class AdminProductController(private val service: ProductApplicationService) {
    @GetMapping
    fun list(
        @RequestParam(required = false) brandId: Long?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResult<AdminProductResponse>> {
        val result = service.list(brandId, page, size)
        return ApiResponse.success(
            PageResult(
                result.content.map(AdminProductResponse::from),
                result.page,
                result.size,
                result.totalElements,
                result.totalPages,
            ),
        )
    }

    @PostMapping
    fun create(@RequestBody request: CreateProductRequest): ResponseEntity<ApiResponse<AdminProductResponse>> =
        ResponseEntity.status(HttpStatus.CREATED).body(
            ApiResponse.success(
                AdminProductResponse.from(service.create(request.brandId, request.name, request.price, request.stock)),
            ),
        )

    @GetMapping("/{productId}")
    fun get(@PathVariable productId: Long): ApiResponse<AdminProductResponse> =
        ApiResponse.success(AdminProductResponse.from(service.get(productId)))

    @PutMapping("/{productId}")
    fun update(@PathVariable productId: Long, @RequestBody request: UpdateProductRequest): ApiResponse<AdminProductResponse> =
        ApiResponse.success(AdminProductResponse.from(service.update(productId, request.name, request.price)))

    @PutMapping("/{productId}/stock")
    fun adjustStock(@PathVariable productId: Long, @RequestBody request: AdjustStockRequest): ApiResponse<AdminProductResponse> =
        ApiResponse.success(AdminProductResponse.from(service.adjustStock(productId, request.stock)))

    @DeleteMapping("/{productId}")
    fun delete(@PathVariable productId: Long): ApiResponse<Any> {
        service.delete(productId)
        return ApiResponse.success()
    }
}
