package com.loopers.interfaces.api.point

import com.fasterxml.jackson.databind.JsonNode
import com.loopers.application.point.PointApplicationService
import com.loopers.application.point.PointResult
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.requesterId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ChargePointRequest(val amount: JsonNode) {
    fun integralAmount(): Long {
        if (!amount.isIntegralNumber || !amount.canConvertToLong()) {
            throw CommerceException(CommerceFailure.INVALID_REQUEST)
        }
        return amount.longValue()
    }
}

data class PointResponse(val userId: Long, val balance: Long) {
    companion object {
        fun from(result: PointResult) = PointResponse(result.userId, result.balance)
    }
}

@RestController
@RequestMapping("/api/v1/points")
class PointController(private val points: PointApplicationService) {
    @GetMapping
    fun get(@RequestHeader("X-USER-ID", required = false) header: String?): ApiResponse<PointResponse> =
        ApiResponse.success(PointResponse.from(points.get(requesterId(header))))

    @PostMapping("/charge")
    fun charge(
        @RequestHeader("X-USER-ID", required = false) header: String?,
        @RequestBody request: ChargePointRequest,
    ): ApiResponse<PointResponse> =
        ApiResponse.success(
            PointResponse.from(points.charge(requesterId(header), request.integralAmount())),
        )
}
