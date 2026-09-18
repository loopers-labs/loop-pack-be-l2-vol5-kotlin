package com.loopers.application.order

import com.loopers.application.common.PageResult
import com.loopers.application.common.offset
import com.loopers.application.common.pageResult
import com.loopers.application.common.positiveId
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.order.Order
import com.loopers.domain.order.OrderLine
import com.loopers.domain.order.OrderRepository
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.PaymentResult
import com.loopers.domain.point.PointAccount
import com.loopers.domain.point.PointAccountRepository
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZonedDateTime

data class OrderItemResult(val productId: Long, val quantity: Int, val unitPrice: Long, val lineAmount: Long)

data class OrderResult(
    val id: Long,
    val userId: Long,
    val status: OrderStatus,
    val items: List<OrderItemResult>,
    val totalAmount: Long,
    val paymentAmount: Long?,
    val paymentResult: PaymentResult?,
    val createdAt: ZonedDateTime,
    val confirmedAt: ZonedDateTime?,
)

data class OrderItemInput(val productId: Long, val quantity: Int)

@Service
class OrderApplicationService(
    private val orders: OrderRepository,
    private val products: ProductRepository,
    private val users: UserRepository,
    private val points: PointAccountRepository,
) {
    fun create(userId: Long, inputs: List<OrderItemInput>): OrderResult {
        requireUser(userId)
        if (inputs.isEmpty()) throw CommerceException(CommerceFailure.INVALID_REQUEST)
        val quantities = sortedMapOf<Long, Int>()
        inputs.forEach { input ->
            positiveId(input.productId)
            if (input.quantity <= 0) throw CommerceException(CommerceFailure.INVALID_QUANTITY)
            quantities[input.productId] = try {
                Math.addExact(quantities[input.productId] ?: 0, input.quantity)
            } catch (_: ArithmeticException) {
                throw CommerceException(CommerceFailure.INVALID_QUANTITY)
            }
        }
        val found = products.findActiveByIds(quantities.keys).associateBy { it.id }
        if (found.size != quantities.size) throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND)
        val lines = quantities.map { (id, quantity) -> OrderLine(id, quantity, found.getValue(id).price.amount) }
        return result(orders.save(Order(userId, lines)))
    }

    @Transactional
    fun confirm(userId: Long, orderId: Long): OrderResult {
        requireUser(userId)
        positiveId(orderId)
        val order = orders.find(orderId) ?: throw CommerceException(CommerceFailure.ORDER_NOT_FOUND)
        if (order.userId != userId) throw CommerceException(CommerceFailure.ORDER_NOT_OWNED)
        order.assertDraft()
        val activeProducts = products.findActiveByIds(order.items.map { it.productId }).associateBy { it.id }
        val orderProducts = order.items.map { item ->
            activeProducts[item.productId] ?: throw CommerceException(CommerceFailure.PRODUCT_NOT_FOUND)
        }
        order.items.zip(orderProducts).forEach { (item, product) -> product.assertCanDecrease(item.quantity) }
        val savedPointAccount = points.findByUserId(userId)
        val pointAccount = savedPointAccount ?: PointAccount.open(userId)
        pointAccount.assertCanPay(order.totalAmount.amount)
        order.items.zip(orderProducts).forEach { (item, product) ->
            product.decreaseStock(item.quantity)
        }
        products.saveAll(orderProducts)
        pointAccount.pay(order.totalAmount.amount)
        if (savedPointAccount != null) points.save(pointAccount)
        order.confirm()
        return result(orders.save(order))
    }

    fun getForUser(userId: Long, orderId: Long): OrderResult {
        requireUser(userId)
        positiveId(orderId)
        val order = orders.find(orderId) ?: throw CommerceException(CommerceFailure.ORDER_NOT_FOUND)
        if (order.userId != userId) throw CommerceException(CommerceFailure.ORDER_NOT_OWNED)
        return result(order)
    }

    fun listForUser(userId: Long, page: Int, size: Int): PageResult<OrderResult> {
        requireUser(userId)
        val offset = offset(page, size)
        return pageResult(page, size, orders.count(userId), orders.findPage(userId, offset, size).map(::result))
    }

    fun getForAdmin(orderId: Long): OrderResult {
        positiveId(orderId)
        return result(orders.find(orderId) ?: throw CommerceException(CommerceFailure.ORDER_NOT_FOUND))
    }

    fun listForAdmin(userId: Long?, page: Int, size: Int): PageResult<OrderResult> {
        if (userId != null) positiveId(userId)
        val offset = offset(page, size)
        return pageResult(page, size, orders.count(userId), orders.findPage(userId, offset, size).map(::result))
    }

    private fun requireUser(id: Long) {
        positiveId(id)
        users.find(id) ?: throw CommerceException(CommerceFailure.USER_NOT_FOUND)
    }

    private fun result(order: Order) = OrderResult(
        order.id,
        order.userId,
        order.status,
        order.items.map { OrderItemResult(it.productId, it.quantity, it.unitPrice.amount, it.lineAmount.amount) },
        order.totalAmount.amount,
        order.paymentAmount?.amount,
        order.paymentResult,
        order.createdAt,
        order.confirmedAt,
    )
}
