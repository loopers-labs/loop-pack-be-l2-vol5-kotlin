package com.loopers.interfaces.api.order

import com.loopers.application.order.OrderFacade
import com.loopers.application.order.OrderInfo
import com.loopers.domain.order.OrderCommand
import com.loopers.domain.payment.CardInfo
import com.loopers.domain.payment.PaymentMethod
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import com.loopers.interfaces.api.ApiResponse
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/orders")
class OrderV1Controller(
    private val orderFacade: OrderFacade,
) {
    @PostMapping
    fun create(
        @RequestHeader(value = "X-USER-ID") userId: Long,
        @RequestBody request: OrderV1Dto.CreateRequest,
    ): ApiResponse<OrderV1Dto.OrderResponse> {
        val lines = request.lines.map { OrderCommand.Line(productId = it.productId, quantity = it.quantity) }

        return orderFacade.create(userId, lines)
            .let { toResponse(it) }
            .let { ApiResponse.success(it) }
    }

    @PostMapping("/{orderId}/confirm")
    fun confirm(
        @RequestHeader(value = "X-USER-ID") userId: Long,
        @PathVariable(value = "orderId") orderId: Long,
        @RequestBody(required = false) request: OrderV1Dto.ConfirmRequest? = null,
    ): ApiResponse<OrderV1Dto.OrderResponse> {
        val method = toMethod(request?.method)
        val card = request?.cardType?.let { type ->
            request.cardNo?.let { number -> CardInfo(type = type, number = number) }
        }

        return orderFacade.confirm(userId, orderId, method, card)
            .let { toResponse(it) }
            .let { ApiResponse.success(it) }
    }

    @PostMapping("/{orderId}/reconcile")
    fun reconcile(
        @RequestHeader(value = "X-USER-ID") userId: Long,
        @PathVariable(value = "orderId") orderId: Long,
    ): ApiResponse<OrderV1Dto.ReconcileResponse> {
        return orderFacade.reconcile(userId, orderId)
            .let {
                OrderV1Dto.ReconcileResponse(
                    orderId = it.orderId,
                    outcome = it.outcome.name,
                    paymentStatus = it.paymentStatus?.name,
                    transactionKey = it.transactionKey,
                )
            }
            .let { ApiResponse.success(it) }
    }

    @DeleteMapping("/{orderId}")
    fun cancel(
        @RequestHeader(value = "X-USER-ID") userId: Long,
        @PathVariable(value = "orderId") orderId: Long,
    ): ApiResponse<OrderV1Dto.OrderResponse> {
        return orderFacade.cancel(userId, orderId)
            .let { toResponse(it) }
            .let { ApiResponse.success(it) }
    }

    @GetMapping("/{orderId}")
    fun get(
        @RequestHeader(value = "X-USER-ID") userId: Long,
        @PathVariable(value = "orderId") orderId: Long,
    ): ApiResponse<OrderV1Dto.OrderResponse> {
        return orderFacade.get(userId, orderId)
            .let { toResponse(it) }
            .let { ApiResponse.success(it) }
    }

    @GetMapping
    fun getAll(
        @RequestHeader(value = "X-USER-ID") userId: Long,
    ): ApiResponse<List<OrderV1Dto.OrderResponse>> {
        return orderFacade.getAll(userId)
            .map { toResponse(it) }
            .let { ApiResponse.success(it) }
    }

    private fun toMethod(raw: String?): PaymentMethod {
        if (raw == null) return PaymentMethod.POINT

        return runCatching { PaymentMethod.valueOf(raw.uppercase()) }
            .getOrElse { throw CoreException(ErrorType.BAD_REQUEST, "지원하지 않는 결제 수단입니다. [method = $raw]") }
    }

    private fun toResponse(info: OrderInfo) = OrderV1Dto.OrderResponse(
        id = info.id,
        status = info.status,
        totalAmount = info.totalAmount,
        paidAmount = info.paidAmount,
        lines = info.lines.map {
            OrderV1Dto.OrderResponse.Item(
                productId = it.productId,
                quantity = it.quantity,
                unitPrice = it.unitPrice,
                amount = it.amount,
            )
        },
    )
}
