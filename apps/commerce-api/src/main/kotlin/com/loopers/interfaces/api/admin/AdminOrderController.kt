package com.loopers.interfaces.api.admin

import com.loopers.application.common.PageResult
import com.loopers.application.order.OrderApplicationService
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.order.OrderResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api-admin/v1/orders")
class AdminOrderController(private val orders: OrderApplicationService) {
    @GetMapping
    fun list(
        @RequestParam(required = false) userId: Long?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResult<OrderResponse>> {
        val result = orders.listForAdmin(userId, page, size)
        return ApiResponse.success(
            PageResult(
                result.content.map(OrderResponse::from),
                result.page,
                result.size,
                result.totalElements,
                result.totalPages,
            ),
        )
    }

    @GetMapping("/{orderId}")
    fun get(
        @PathVariable orderId: Long,
    ): ApiResponse<OrderResponse> {
        return ApiResponse.success(OrderResponse.from(orders.getForAdmin(orderId)))
    }
}
