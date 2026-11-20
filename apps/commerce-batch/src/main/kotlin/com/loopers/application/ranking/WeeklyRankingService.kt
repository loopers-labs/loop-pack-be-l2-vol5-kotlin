package com.loopers.application.ranking

import com.loopers.domain.ranking.ProductScore
import com.loopers.domain.ranking.RankingCalculation
import com.loopers.domain.ranking.RankingChecksum
import com.loopers.domain.ranking.RankingPeriod
import com.loopers.domain.ranking.RankingRequest
import com.loopers.domain.ranking.RankingSnapshot
import com.loopers.domain.ranking.RankingStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class WeeklyRankingService(private val store: RankingStore) {
    // Lock the period before the manifest; freeze all input in one repeatable-read transaction or reuse it unchanged.
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun freeze(request: RankingRequest): RankingSnapshot {
        store.lockPeriod(request.periodStart)
        val existing = store.findSnapshot(request.snapshotId, forUpdate = true)
        if (existing != null) {
            check(existing.request == request) { "snapshot identity conflict" }
            existing.verifyInputs(store.frozenInputs(request.snapshotId))

            return existing
        }

        val frozenAt = store.databaseTime()
        val inputs = store.liveInputs(RankingPeriod(request.periodStart))
        val snapshot = RankingSnapshot(
            request,
            frozenAt,
            inputs.size.toLong(),
            inputs.map { it.productId }.distinct().size.toLong(),
            RankingChecksum.inputs(inputs),
        )
        snapshot.verifyInputs(inputs)
        store.insertSnapshot(snapshot, inputs)

        return snapshot
    }

    @Transactional(readOnly = true)
    fun score(snapshotId: String, productId: Long): ProductScore {
        val inputs = store.productInputs(snapshotId, productId)
        check(inputs.isNotEmpty()) { "product missing from frozen input" }

        return ProductScore(productId, RankingCalculation.score(inputs))
    }

    @Transactional
    fun write(snapshotId: String, scores: List<ProductScore>) {
        check(store.findSnapshot(snapshotId)?.status == "FROZEN") { "snapshot must be frozen and unfinished" }

        store.writeScores(snapshotId, scores)
    }

    // Validate every frozen product before atomically publishing. A repeated publish verifies the saved completed result.
    @Transactional
    fun publish(request: RankingRequest) {
        val currentId = store.lockPeriod(request.periodStart)
        val snapshot = checkNotNull(store.findSnapshot(request.snapshotId, forUpdate = true)) { "snapshot not found" }
        check(snapshot.request == request) { "snapshot identity conflict" }
        val current = currentId?.let { checkNotNull(store.findSnapshot(it)) }
        check(current == null || current.request.revision <= request.revision) { "superseded revision" }

        val inputs = store.frozenInputs(request.snapshotId)
        snapshot.verifyInputs(inputs)
        val expected = inputs.groupBy { it.productId }.map { (id, rows) -> ProductScore(id, RankingCalculation.score(rows)) }
            .sortedBy { it.productId }
        check(store.scores(request.snapshotId) == expected) { "stage does not match frozen inputs" }
        val ranked = RankingCalculation.rank(expected)
        val checksum = RankingChecksum.results(ranked)

        if (snapshot.status == "COMPLETE") {
            check(
                snapshot.resultChecksum == checksum && store.results(request.snapshotId) == ranked,
            ) { "completed result mismatch" }
        } else {
            check(snapshot.status == "FROZEN")
            store.saveResults(request.snapshotId, ranked, checksum)
        }

        store.pointTo(request.periodStart, request.snapshotId)
    }
}
