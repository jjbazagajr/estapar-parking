package com.estapar.parking.webhook

import com.estapar.parking.domain.ParkingSessionRepository
import com.estapar.parking.domain.SectorRepository
import com.estapar.parking.domain.SpotRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WebhookService(
    private val sessions: ParkingSessionRepository,
    private val spots: SpotRepository,
    private val sectors: SectorRepository,
) {

    private val log = LoggerFactory.getLogger(WebhookService::class.java)

    @Transactional
    fun handle(event: WebhookEvent) {
        when (event) {
            is EntryEvent -> handleEntry(event)
            is ParkedEvent -> handleParked(event)
            is ExitEvent -> handleExit(event)
        }
    }

    private fun handleEntry(event: EntryEvent) {
        log.info("ENTRY received plate={} at={}", event.licensePlate, event.entryTime)
        // TODO: aplicar regras (lotação 100%, preço dinâmico) e persistir sessão aberta
    }

    private fun handleParked(event: ParkedEvent) {
        log.info("PARKED received plate={} at=({}, {})", event.licensePlate, event.lat, event.lng)
        // TODO: localizar spot pela coordenada, marcar como ocupado, vincular à sessão aberta
    }

    private fun handleExit(event: ExitEvent) {
        log.info("EXIT received plate={} at={}", event.licensePlate, event.exitTime)
        // TODO: encerrar sessão, liberar spot, calcular tarifa (30min grátis + hora cheia arredondada * multiplicador)
    }
}
