package com.loopers.relay

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OutboxRelayScheduler(
    private val outboxRelay: OutboxRelay,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${relay.cron}", zone = "Asia/Seoul")
    fun relay() {
        val relayed = outboxRelay.relayAll()

        log.info("아웃박스 릴레이 완료. relayed={}", relayed)
    }
}
