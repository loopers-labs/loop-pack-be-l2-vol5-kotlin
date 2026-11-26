package com.loopers.incident

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class MutableClock(
    @Volatile private var now: Instant,
    private val zone: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    fun set(instant: Instant) {
        now = instant
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
}
