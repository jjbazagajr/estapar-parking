package com.estapar.parking.domain

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalTime
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SectorTest {

    @Test
    fun `given sector com janela 08 as 18 when consulta isOpenAt em 12 then retorna true`() {
        // given
        val sector = sectorWith(open = LocalTime.of(8, 0), close = LocalTime.of(18, 0))

        // when
        val result = sector.isOpenAt(LocalTime.of(12, 0))

        // then
        assertTrue(result)
    }

    @Test
    fun `given sector com janela 08 as 18 when consulta isOpenAt em 07h59 then retorna false`() {
        // given
        val sector = sectorWith(open = LocalTime.of(8, 0), close = LocalTime.of(18, 0))

        // when
        val result = sector.isOpenAt(LocalTime.of(7, 59))

        // then
        assertFalse(result)
    }

    @Test
    fun `given sector com janela 08 as 18 when consulta isOpenAt na fronteira 08h00 then retorna true`() {
        // given
        val sector = sectorWith(open = LocalTime.of(8, 0), close = LocalTime.of(18, 0))

        // when
        val result = sector.isOpenAt(LocalTime.of(8, 0))

        // then
        assertTrue(result)
    }

    @Test
    fun `given sector com janela 08 as 18 when consulta isOpenAt na fronteira 18h00 then retorna true`() {
        // given
        val sector = sectorWith(open = LocalTime.of(8, 0), close = LocalTime.of(18, 0))

        // when
        val result = sector.isOpenAt(LocalTime.of(18, 0))

        // then
        assertTrue(result)
    }

    @Test
    fun `given sector sem open hour e close hour when consulta isOpenAt em qualquer horario then retorna true`() {
        // given
        val sector = sectorWith(open = null, close = null)

        // when
        val result = sector.isOpenAt(LocalTime.of(3, 0))

        // then
        assertTrue(result)
    }

    private fun sectorWith(open: LocalTime?, close: LocalTime?) = Sector(
        name = "A",
        basePrice = BigDecimal("10.00"),
        maxCapacity = 10,
        openHour = open,
        closeHour = close,
    )
}
