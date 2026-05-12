package com.estapar.parking.webhook

import com.estapar.parking.domain.SessionAlreadyOpenException
import com.estapar.parking.garage.GarageService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.dao.DataIntegrityViolationException
import java.time.LocalDateTime

class WebhookDispatcherTest {

    private val garage: GarageService = mock(GarageService::class.java)
    private val dispatcher = WebhookDispatcher(garage)

    private val plate = "ABC1D23"
    private val anyTime = LocalDateTime.of(2026, 5, 10, 12, 0)

    @Test
    fun `given ENTRY when dispatch then chama garage_registerEntry`() {
        // given
        val event = EntryEvent(plate, anyTime)

        // when
        dispatcher.dispatch(event)

        // then
        verify(garage).registerEntry(plate, anyTime)
    }

    @Test
    fun `given PARKED when dispatch then chama garage_parkVehicle`() {
        // given
        val event = ParkedEvent(plate, lat = -23.5, lng = -46.6)

        // when
        dispatcher.dispatch(event)

        // then
        verify(garage).parkVehicle(plate, -23.5, -46.6)
    }

    @Test
    fun `given EXIT when dispatch then chama garage_processExit`() {
        // given
        val exit = anyTime.plusHours(1)
        val event = ExitEvent(plate, exit)

        // when
        dispatcher.dispatch(event)

        // then
        verify(garage).processExit(plate, exit)
    }

    @Test
    fun `given garage lanca DomainRuleViolation when dispatch then engole excecao (log) e nao propaga`() {
        // given
        doThrow(SessionAlreadyOpenException(plate))
            .`when`(garage).registerEntry(plate, anyTime)

        // when / then (nao propaga)
        dispatcher.dispatch(EntryEvent(plate, anyTime))
    }

    @Test
    fun `given garage lanca DataIntegrityViolationException when dispatch then engole excecao (log) e nao propaga`() {
        // given (corrida na constraint uk_sessions_open_plate)
        doThrow(DataIntegrityViolationException("uk_sessions_open_plate"))
            .`when`(garage).registerEntry(plate, anyTime)

        // when / then (nao propaga)
        dispatcher.dispatch(EntryEvent(plate, anyTime))
    }
}
