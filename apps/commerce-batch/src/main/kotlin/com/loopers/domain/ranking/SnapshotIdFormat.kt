package com.loopers.domain.ranking

object SnapshotIdFormat {
    private val FORMAT = Regex("""\d{4}_(0[1-9]|1[0-2])_[1-5]_r([1-9]\d*)""")

    fun matches(snapshotId: String): Boolean = FORMAT.matches(snapshotId)

    fun revisionOf(snapshotId: String): Int? = FORMAT.matchEntire(snapshotId)?.groupValues?.get(2)?.toInt()
}
