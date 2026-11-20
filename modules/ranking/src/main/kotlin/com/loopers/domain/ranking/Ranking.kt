package com.loopers.domain.ranking

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.security.MessageDigest

class RankingPeriod(val start: LocalDate) {
    init {
        require(start.dayOfWeek == DayOfWeek.MONDAY) { "periodStart must be Monday" }
    }

    val end: LocalDate = start.plusWeeks(1)
    val startAt: Instant = start.atStartOfDay(ZoneOffset.UTC).toInstant()
    val endAt: Instant = end.atStartOfDay(ZoneOffset.UTC).toInstant()

    fun contains(instant: Instant): Boolean = instant >= startAt && instant < endAt
}

data class RankingInput(val orderId: Long, val productId: Long, val quantity: Long, val confirmedAt: Instant) {
    init {
        require(orderId > 0 && productId > 0 && quantity > 0)
        require(confirmedAt.nano % 1000 == 0) { "confirmedAt must have microsecond precision" }
    }
}

data class ProductScore(val productId: Long, val score: Long) {
    init {
        require(productId > 0 && score > 0)
    }
}

data class RankedProduct(val productId: Long, val score: Long, val rank: Long)

object RankingCalculation {
    fun score(inputs: List<RankingInput>): Long = inputs.fold(0L) { total, row -> Math.addExact(total, row.quantity) }

    fun rank(scores: List<ProductScore>): List<RankedProduct> {
        require(scores.map { it.productId }.distinct().size == scores.size) { "duplicate product scores" }

        return scores.sortedWith(compareByDescending<ProductScore> { it.score }.thenBy { it.productId }).mapIndexed { i, s ->
            RankedProduct(s.productId, s.score, i + 1L)
        }
    }
}

object RankingChecksum {
    private val utc = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun inputs(rows: List<RankingInput>): String = hash(
        rows.sortedWith(compareBy<RankingInput> { it.orderId }.thenBy { it.productId })
            .joinToString("") { "${it.orderId}\t${it.productId}\t${it.quantity}\t${utc.format(it.confirmedAt)}\n" },
    )

    fun results(rows: List<RankedProduct>): String = hash(
        rows.sortedBy { it.rank }.joinToString("") { "${it.productId}\t${it.score}\t${it.rank}\n" },
    )

    private fun hash(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
