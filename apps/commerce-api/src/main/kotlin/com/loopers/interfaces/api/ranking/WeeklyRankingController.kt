package com.loopers.interfaces.api.ranking

import com.loopers.application.ranking.WeeklyRankingQueryService
import com.loopers.domain.ranking.WeeklyRankingView
import com.loopers.interfaces.api.ApiResponse
import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/rankings")
class WeeklyRankingController(private val service: WeeklyRankingQueryService) {
    @GetMapping("/weekly")
    fun weekly(@RequestParam periodStart: String, @RequestParam(defaultValue = "10") limit: Int): ApiResponse<WeeklyRankingView> {
        val date = try {
            require(periodStart.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
            LocalDate.parse(periodStart).also { require(it.dayOfWeek == java.time.DayOfWeek.MONDAY && limit in 1..100) }
        } catch (_: IllegalArgumentException) {
            throw CommerceException(CommerceFailure.INVALID_REQUEST)
        } catch (_: java.time.format.DateTimeParseException) {
            throw CommerceException(CommerceFailure.INVALID_REQUEST)
        }

        return ApiResponse.success(service.find(date, limit))
    }
}
