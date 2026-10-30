package com.loopers.config.kafka

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaTemplate

@SpringBootTest
class KafkaProducerWiringTest @Autowired constructor(
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
) {
    @DisplayName("commerce-api 에 프로듀서가 올라오고 kafka.yml 의 직렬화 설정을 쓴다.")
    @Test
    fun wiresProducer() {
        val config = kafkaTemplate.producerFactory.configurationProperties

        assertAll(
            {
                assertThat(config["bootstrap.servers"])
                    .isEqualTo(listOf("localhost:19092", "localhost:19093", "localhost:19094"))
            },
            { assertThat(config["key.serializer"].toString()).contains("StringSerializer") },
            { assertThat(config["value.serializer"].toString()).contains("JsonSerializer") },
        )
    }
}
