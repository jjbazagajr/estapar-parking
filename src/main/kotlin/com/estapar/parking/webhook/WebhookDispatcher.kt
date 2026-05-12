package com.estapar.parking.webhook

import com.estapar.parking.domain.DomainRuleViolation
import com.estapar.parking.garage.GarageService
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

@Component
class WebhookDispatcher(
    private val garage: GarageService,
) {

    private val log = LoggerFactory.getLogger(WebhookDispatcher::class.java)

    @Async("webhookExecutor")
    fun dispatch(event: WebhookEvent) {
        try {
            when (event) {
                is EntryEvent -> garage.registerEntry(event.licensePlate, event.entryTime)
                is ParkedEvent -> garage.parkVehicle(event.licensePlate, event.lat, event.lng)
                is ExitEvent -> garage.processExit(event.licensePlate, event.exitTime)
            }
        } catch (ex: DomainRuleViolation) {
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
    }

    private fun DataIntegrityViolationException.mostSpecificCauseMessage(): String =
        mostSpecificCause.message ?: message.orEmpty()
}
