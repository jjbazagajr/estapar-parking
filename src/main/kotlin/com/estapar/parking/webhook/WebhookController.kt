package com.estapar.parking.webhook

import com.estapar.parking.garage.GarageService
import com.estapar.parking.garage.WebhookEventIgnored
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/webhook")
@Tag(name = "Webhook", description = "Eventos do simulador: ENTRY, PARKED e EXIT")
class WebhookController(
    private val garage: GarageService,
) {

    private val log = LoggerFactory.getLogger(WebhookController::class.java)

    @PostMapping
    @Operation(summary = "Recebe evento do simulador e atualiza estado da garagem")
    fun receive(@RequestBody event: WebhookEvent): ResponseEntity<Void> {
        try {
            when (event) {
                is EntryEvent -> garage.registerEntry(event.licensePlate, event.entryTime)
                is ParkedEvent -> garage.parkVehicle(event.licensePlate, event.lat, event.lng)
                is ExitEvent -> garage.processExit(event.licensePlate, event.exitTime)
            }
        } catch (ex: WebhookEventIgnored) {
            log.info(
                "evento ignorado: type={} plate={} reason={}",
                event.javaClass.simpleName, event.licensePlate, ex.message,
            )
        } catch (ex: DataIntegrityViolationException) {
            log.info(
                "evento ignorado por constraint: type={} plate={} reason={}",
                event.javaClass.simpleName, event.licensePlate, ex.mostSpecificCauseMessage(),
            )
        }
        return ResponseEntity.ok().build()
    }

    private fun DataIntegrityViolationException.mostSpecificCauseMessage(): String =
        mostSpecificCause.message ?: message.orEmpty()
}
