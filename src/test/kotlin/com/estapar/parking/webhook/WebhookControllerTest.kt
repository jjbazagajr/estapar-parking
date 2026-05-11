package com.estapar.parking.webhook

import com.estapar.parking.garage.GarageService
import com.estapar.parking.garage.SessionAlreadyOpenException
import com.estapar.parking.garage.SessionNotFoundException
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebhookControllerTest {

    private val garage: GarageService = mock(GarageService::class.java)
    private val controller = WebhookController(garage)

    private val plate = "ABC1D23"
    private val entryTime = LocalDateTime.of(2026, 5, 10, 12, 0)

    @Test
    fun `given ENTRY valido when receive then chama service e responde 200 sem body`() {
        // given (mock retorna Unit por default)

        // when
        val response = controller.receive(EntryEvent(plate, entryTime))

        // then
        assertEquals(HttpStatus.OK, response.statusCode)
        assertNull(response.body)
        verify(garage).registerEntry(plate, entryTime)
    }

    @Test
    fun `given service lanca WebhookEventIgnored when receive then responde 200 e nao propaga`() {
        // given
        doThrow(SessionAlreadyOpenException(plate))
            .`when`(garage).registerEntry(plate, entryTime)

        // when
        val response = controller.receive(EntryEvent(plate, entryTime))

        // then
        assertEquals(HttpStatus.OK, response.statusCode)
        assertNull(response.body)
    }

    @Test
    fun `given service lanca DataIntegrityViolationException when receive then responde 200 e nao propaga`() {
        // given (corrida na constraint uk_sessions_open_plate ou uk_sessions_entry_event)
        doThrow(DataIntegrityViolationException("uk_sessions_open_plate"))
            .`when`(garage).registerEntry(plate, entryTime)

        // when
        val response = controller.receive(EntryEvent(plate, entryTime))

        // then
        assertEquals(HttpStatus.OK, response.statusCode)
        assertNull(response.body)
    }

    @Test
    fun `given PARKED para placa sem sessao when receive then responde 200 e nao propaga`() {
        // given evento PARKED chega antes de qualquer ENTRY
        doThrow(SessionNotFoundException(plate))
            .`when`(garage).parkVehicle(plate, 0.0, 0.0)

        // when
        val response = controller.receive(ParkedEvent(plate, 0.0, 0.0))

        // then
        assertEquals(HttpStatus.OK, response.statusCode)
        assertNull(response.body)
    }
}
