package com.loopers.interfaces.api.like

import com.loopers.application.common.PageResult
import com.loopers.application.like.LikeResult
import com.loopers.application.like.ProductLikeApplicationService
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.product.CustomerProductResponse
import com.loopers.interfaces.api.requesterId
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class ProductLikeController(private val service: ProductLikeApplicationService) {
    @PostMapping("/products/{productId}/likes")
    fun like(
        @PathVariable productId: Long,
        @RequestHeader("X-USER-ID", required = false) header: String?,
    ): ApiResponse<LikeResult> =
        ApiResponse.success(service.like(requesterId(header), productId))

    @DeleteMapping("/products/{productId}/likes")
    fun unlike(
        @PathVariable productId: Long,
        @RequestHeader("X-USER-ID", required = false) header: String?,
    ): ApiResponse<LikeResult> =
        ApiResponse.success(service.unlike(requesterId(header), productId))

    @GetMapping("/users/{userId}/likes")
    fun list(
        @PathVariable userId: Long,
        @RequestHeader("X-USER-ID", required = false) header: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResult<CustomerProductResponse>> {
        val result = service.list(requesterId(header), userId, page, size)
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
}
