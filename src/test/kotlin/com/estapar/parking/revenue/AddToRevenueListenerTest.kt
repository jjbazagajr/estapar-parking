package com.estapar.parking.revenue

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.time.Instant

class AddToRevenueListenerTest {

    private val service: RevenueService = mock(RevenueService::class.java)
    private val listener = AddToRevenueListener(service)

    @Test
    fun `given AddToRevenueEvent when on then delega para service com o mesmo evento`() {
        // given
        val event = AddToRevenueEvent(sessionId = 99L, exitTime = Instant.parse("2026-05-10T13:00:00Z"))

        // when
        listener.on(event)

        // then
        verify(service).addRevenue(event)
    }
}
