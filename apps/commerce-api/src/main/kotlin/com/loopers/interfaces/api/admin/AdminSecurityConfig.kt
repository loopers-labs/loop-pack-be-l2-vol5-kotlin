package com.loopers.interfaces.api.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.interfaces.api.ApiResponse
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
class AdminSecurityConfig {
    @Bean
    @Order(1)
    fun adminSecurity(http: HttpSecurity, mapper: ObjectMapper): SecurityFilterChain {
        val forbidden: (HttpServletResponse) -> Unit = { response ->
            response.status = 403
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            mapper.writeValue(response.outputStream, ApiResponse.fail("ADMIN_FORBIDDEN", "관리자 권한이 필요합니다."))
        }
        http.securityMatcher("/api-admin/**")
            .authorizeHttpRequests { it.anyRequest().hasRole("ADMIN") }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ -> forbidden(response) }
                it.accessDeniedHandler { _, response, _ -> forbidden(response) }
            }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
        return http.build()
    }

    @Bean
    @Order(2)
    fun publicSecurity(http: HttpSecurity): SecurityFilterChain {
        http.authorizeHttpRequests { it.anyRequest().permitAll() }
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
        return http.build()
    }
}
