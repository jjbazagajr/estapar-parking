package com.estapar.parking.webhook

import com.estapar.parking.simulator.GarageBootstrap
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/webhook")
@Tag(name = "Webhook", description = "Eventos do simulador: ENTRY, PARKED e EXIT")
class WebhookController(
    private val dispatcher: WebhookDispatcher,
    private val log: Logger = LoggerFactory.getLogger(GarageBootstrap::class.java)

) {

    @PostMapping
    @Operation(summary = "Recebe evento do simulador e despacha processamento assíncrono")
    fun receive(@RequestBody event: WebhookEvent): ResponseEntity<Void> {
        log.info("Evento webhook recebido {}", event)
        dispatcher.dispatch(event)
        return ResponseEntity.ok().build()
    }
}
