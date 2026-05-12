package com.estapar.parking.webhook

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.http.HttpStatus
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebhookControllerTest {

    private val dispatcher: WebhookDispatcher = mock(WebhookDispatcher::class.java)
    private val controller = WebhookController(dispatcher)

    private val plate = "ABC1D23"
    private val entryTime = LocalDateTime.of(2026, 5, 10, 12, 0)

    @Test
    fun `given ENTRY valido when receive then despacha evento e responde 200 sem body`() {
        // given
        val event = EntryEvent(plate, entryTime)

        // when
        val response = controller.receive(event)

        // then
        assertEquals(HttpStatus.OK, response.statusCode)
        assertNull(response.body)
        verify(dispatcher).dispatch(event)
    }

    @Test
    fun `given PARKED valido when receive then despacha evento e responde 200`() {
        // given
        val event = ParkedEvent(plate, lat = -23.5, lng = -46.6)

        // when
        val response = controller.receive(event)

        // then
        assertEquals(HttpStatus.OK, response.statusCode)
        verify(dispatcher).dispatch(event)
    }

    @Test
    fun `given EXIT valido when receive then despacha evento e responde 200`() {
        // given
        val event = ExitEvent(plate, exitTime = entryTime.plusHours(1))

        // when
        val response = controller.receive(event)

        // then
        assertEquals(HttpStatus.OK, response.statusCode)
        verify(dispatcher).dispatch(event)
    }
}
