package com.loopers.interfaces.api.ranking

import com.loopers.application.ranking.RankingFacade
import com.loopers.interfaces.api.ApiResponse
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/rankings")
class RankingV1Controller(
    private val rankingFacade: RankingFacade,
) {
    @GetMapping("/weekly")
    fun getWeekly(
        @RequestParam(value = "periodStart", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        periodStart: LocalDate?,
        @RequestParam(value = "limit", required = false) limit: Int?,
    ): ApiResponse<RankingV1Dto.WeeklyResponse> =
        rankingFacade.getWeekly(periodStart, limit)
            .let { RankingV1Dto.WeeklyResponse.from(it) }
            .let { ApiResponse.success(it) }
}
