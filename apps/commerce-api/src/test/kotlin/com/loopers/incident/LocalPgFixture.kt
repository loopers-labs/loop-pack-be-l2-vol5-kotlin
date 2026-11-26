package com.loopers.incident

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class LocalPgFixture(
    private val dropHold: Duration,
) : AutoCloseable {
    private val objectMapper = ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val httpClient = HttpClient.newHttpClient()
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress(0), 0)

    private val transactions = ConcurrentHashMap<Long, Transaction>()
    private val createAttempts = CopyOnWriteArrayList<CreateAttempt>()
    private val dropResponseOrders = ConcurrentHashMap.newKeySet<Long>()
    private val holdCallbackOrders = ConcurrentHashMap.newKeySet<Long>()

    lateinit var callbackUrl: String

    val baseUrl: String
        get() = "http://localhost:${server.address.port}"

    init {
        server.executor = executor
        server.createContext("/api/v1/payments") { exchange ->
            exchange.use {
                when (exchange.requestMethod) {
                    "POST" -> accept(exchange)
                    "GET" -> find(exchange)
                    else -> respond(exchange, 405, mapOf("message" to "지원하지 않는 메서드"))
                }
            }
        }
        server.start()
    }

    fun dropResponseFor(orderIds: Collection<Long>) = dropResponseOrders.addAll(orderIds)

    fun holdCallbackFor(orderIds: Collection<Long>) = holdCallbackOrders.addAll(orderIds)

    fun clearFaults() {
        dropResponseOrders.clear()
        holdCallbackOrders.clear()
    }

    fun reset() {
        clearFaults()
        transactions.clear()
        createAttempts.clear()
    }

    fun faultsCleared(): Boolean = dropResponseOrders.isEmpty() && holdCallbackOrders.isEmpty()

    fun transactionOf(orderId: Long): Transaction? = transactions[orderId]

    fun transactions(): List<Transaction> = transactions.values.toList()

    fun createAttempts(): List<CreateAttempt> = createAttempts.toList()

    fun createCountOf(orderId: Long): Int = createAttempts.count { it.orderId == orderId }

    fun duplicateCreates(): List<CreateAttempt> =
        createAttempts.groupBy { it.orderId }.values.filter { it.size > 1 }.flatMap { it.drop(1) }

    fun settle(orderId: Long): Int? {
        val settled = transactions.computeIfPresent(orderId) { _, transaction ->
            transaction.copy(status = SUCCESS, settledAt = transaction.settledAt ?: Instant.now())
        } ?: throw IllegalStateException("거래가 없는 주문입니다. [orderId = $orderId]")

        if (orderId in holdCallbackOrders) {
            return null
        }

        return sendCallback(settled)
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun accept(exchange: HttpExchange) {
        val request = objectMapper.readTree(exchange.requestBody)
        val orderId = request.get("orderId").asLong()
        val amount = request.get("amount").asLong()
        val cardType = request.get("cardType").asText()
        val cardNo = request.get("cardNo").asText()
        createAttempts.add(CreateAttempt(orderId = orderId, amount = amount, at = Instant.now()))

        val transaction = transactions.computeIfAbsent(orderId) {
            Transaction(
                transactionKey = "pgtx_${UUID.randomUUID().toString().take(12)}",
                orderId = orderId,
                amount = amount,
                cardType = cardType,
                maskedCardNo = mask(cardNo),
                status = PENDING,
                requestedAt = Instant.now(),
                settledAt = null,
            )
        }

        if (transaction.amount != amount) {
            respond(exchange, 409, mapOf("message" to "이미 다른 금액으로 접수된 주문입니다."))
            return
        }

        if (orderId in dropResponseOrders) {
            transactions.computeIfPresent(orderId) { _, it -> it.copy(status = SUCCESS, settledAt = Instant.now()) }
            Thread.sleep(dropHold.toMillis())
            return
        }

        respond(exchange, 202, transaction.toBody())
    }

    private fun find(exchange: HttpExchange) {
        val orderId = exchange.requestURI.query
            ?.split("&")
            ?.map { it.split("=", limit = 2) }
            ?.firstOrNull { it[0] == "orderId" }
            ?.getOrNull(1)
            ?.toLongOrNull()

        val transaction = orderId?.let { transactions[it] }
        if (transaction == null) {
            respond(exchange, 404, mapOf("message" to "거래를 찾을 수 없습니다."))
            return
        }

        respond(exchange, 200, transaction.toBody())
    }

    private fun sendCallback(transaction: Transaction): Int {
        val body = mapOf(
            "transactionKey" to transaction.transactionKey,
            "orderId" to transaction.orderId,
            "status" to transaction.status,
            "amount" to transaction.amount.toString(),
            "settledAt" to transaction.settledAt,
        )
        val request = HttpRequest.newBuilder(URI.create(callbackUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build()

        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun respond(exchange: HttpExchange, status: Int, body: Any) {
        val bytes = objectMapper.writeValueAsString(body).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun mask(cardNo: String): String {
        val digits = cardNo.filter { it.isDigit() }
        return "****-****-****-${digits.takeLast(4)}"
    }

    data class Transaction(
        val transactionKey: String,
        val orderId: Long,
        val amount: Long,
        val cardType: String,
        val maskedCardNo: String,
        val status: String,
        val requestedAt: Instant,
        val settledAt: Instant?,
    ) {
        fun toBody(): Map<String, Any?> = mapOf(
            "transactionKey" to transactionKey,
            "orderId" to orderId,
            "amount" to amount,
            "cardType" to cardType,
            "maskedCardNo" to maskedCardNo,
            "status" to status,
            "requestedAt" to requestedAt,
            "settledAt" to settledAt,
            "declineCode" to null,
            "declineReason" to null,
        )
    }

    data class CreateAttempt(
        val orderId: Long,
        val amount: Long,
        val at: Instant,
    )

    companion object {
        const val PENDING = "PENDING"
        const val SUCCESS = "SUCCESS"
    }
}
