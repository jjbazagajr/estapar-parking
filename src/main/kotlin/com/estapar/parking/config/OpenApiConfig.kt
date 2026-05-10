package com.estapar.parking.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun estaparOpenAPI(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("Estapar Parking API")
                .description("API de gestão de estacionamento: webhooks de eventos do simulador e consulta de receita por setor.")
                .version("v1"),
        )
}
