package com.estapar.parking.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "estapar.simulator")
data class SimulatorProperties(
    val baseUrl: String = "http://localhost:8081",
    val bootstrapEnabled: Boolean = true,
)
