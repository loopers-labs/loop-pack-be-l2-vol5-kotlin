package com.loopers.interfaces.api.brand

import com.loopers.application.brand.BrandApplicationService
import com.loopers.interfaces.api.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/brands")
class BrandController(private val brands: BrandApplicationService) {
    @GetMapping("/{brandId}")
    fun get(@PathVariable brandId: Long): ApiResponse<BrandResponse> {
        val result = brands.get(brandId)
        return ApiResponse.success(BrandResponse(result.id, result.name))
    }
}
