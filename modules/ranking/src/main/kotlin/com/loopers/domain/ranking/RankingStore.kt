package com.loopers.domain.ranking

import java.time.Instant
import java.time.LocalDate

data class RankingRequest(val periodStart: LocalDate, val snapshotId: String, val revision: Long) {
    init {
        RankingPeriod(periodStart)
        require(snapshotId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "invalid snapshotId" }
        require(revision > 0) { "revision must be positive" }
    }
}

data class RankingSnapshot(
    val request: RankingRequest,
    val frozenAt: Instant,
    val inputCount: Long,
    val productCount: Long,
    val inputChecksum: String,
    val status: String = "FROZEN",
    val resultChecksum: String? = null,
) {
    fun verifyInputs(inputs: List<RankingInput>) {
        val period = RankingPeriod(request.periodStart)
        check(inputs.all { period.contains(it.confirmedAt) }) { "input outside period" }
        check(inputs.map { it.orderId to it.productId }.distinct().size == inputs.size) { "duplicate inputs" }
        check(inputCount == inputs.size.toLong()) { "input count mismatch" }
        check(productCount == inputs.map { it.productId }.distinct().size.toLong()) { "product count mismatch" }
        check(inputChecksum == RankingChecksum.inputs(inputs)) { "input checksum mismatch" }
    }
}

interface RankingStore {
    fun lockPeriod(periodStart: LocalDate): String?
    fun findSnapshot(snapshotId: String, forUpdate: Boolean = false): RankingSnapshot?
    fun databaseTime(): Instant
    fun liveInputs(period: RankingPeriod): List<RankingInput>
    fun frozenInputs(snapshotId: String): List<RankingInput>
    fun productInputs(snapshotId: String, productId: Long): List<RankingInput>
    fun insertSnapshot(snapshot: RankingSnapshot, inputs: List<RankingInput>)
    fun writeScores(snapshotId: String, scores: List<ProductScore>)
    fun scores(snapshotId: String): List<ProductScore>
    fun results(snapshotId: String): List<RankedProduct>
    fun saveResults(snapshotId: String, results: List<RankedProduct>, checksum: String)
    fun pointTo(periodStart: LocalDate, snapshotId: String)
}

interface RankingPeriodLock {
    fun acquire(periodStart: LocalDate): AutoCloseable
}
