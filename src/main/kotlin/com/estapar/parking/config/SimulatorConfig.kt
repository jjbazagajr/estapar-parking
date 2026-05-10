package com.estapar.parking.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
@EnableConfigurationProperties(SimulatorProperties::class)
class SimulatorConfig {

    @Bean
    fun simulatorRestClient(properties: SimulatorProperties): RestClient =
        RestClient.builder()
            .baseUrl(properties.baseUrl)
            .build()
}
