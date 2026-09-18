package com.loopers.interfaces.api.admin

import com.loopers.application.brand.BrandApplicationService
import com.loopers.application.brand.BrandResult
import com.loopers.application.common.PageResult
import com.loopers.interfaces.api.ApiResponse
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

data class BrandRequest(val name: String)

data class AdminBrandResponse(val id: Long, val name: String, val createdAt: ZonedDateTime, val updatedAt: ZonedDateTime) {
    companion object {
        fun from(result: BrandResult) = AdminBrandResponse(result.id, result.name, result.createdAt, result.updatedAt)
    }
}

@RestController
@RequestMapping("/api-admin/v1/brands")
class AdminBrandController(private val service: BrandApplicationService) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResult<AdminBrandResponse>> {
        val result = service.list(page, size)
        return ApiResponse.success(
            PageResult(
                result.content.map(AdminBrandResponse::from),
                result.page,
                result.size,
                result.totalElements,
                result.totalPages,
            ),
        )
    }

    @PostMapping
    fun create(@RequestBody request: BrandRequest): ResponseEntity<ApiResponse<AdminBrandResponse>> =
        ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(AdminBrandResponse.from(service.create(request.name))))

    @GetMapping("/{brandId}")
    fun get(
        @PathVariable brandId: Long,
    ): ApiResponse<AdminBrandResponse> = ApiResponse.success(AdminBrandResponse.from(service.get(brandId)))

    @PutMapping("/{brandId}")
    fun update(@PathVariable brandId: Long, @RequestBody request: BrandRequest): ApiResponse<AdminBrandResponse> =
        ApiResponse.success(AdminBrandResponse.from(service.update(brandId, request.name)))

    @DeleteMapping("/{brandId}")
    fun delete(@PathVariable brandId: Long): ApiResponse<Any> {
        service.delete(brandId)
        return ApiResponse.success()
    }
}
