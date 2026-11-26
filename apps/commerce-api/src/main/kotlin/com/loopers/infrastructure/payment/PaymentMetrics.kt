package com.loopers.infrastructure.payment

import com.loopers.domain.payment.PaymentRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

@Component
class PaymentMetrics(
    paymentRepository: PaymentRepository,
    properties: PaymentMetricsProperties,
    registry: MeterRegistry,
) {
    init {
        Gauge.builder(STALE_PENDING) {
            paymentRepository.countPendingCreatedBefore(ZonedDateTime.now().minus(properties.stalePendingAfter))
        }
            .description("생성 후 기준 시간이 지나도 결과를 모르는(PENDING) 결제 수")
            .register(registry)
    }

    companion object {
        const val STALE_PENDING = "payment.pending.stale"
    }
}
