package com.estapar.parking.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotTest {

    @Test
    fun `given spot livre when occupy then marca como ocupado`() {
        // given
        val spot = Spot(id = 1L, sector = "A", lat = 0.0, lng = 0.0, occupied = false)

        // when
        spot.occupy()

        // then
        assertTrue(spot.occupied)
    }

    @Test
    fun `given spot ja ocupado when occupy then lanca SpotAlreadyOccupiedException`() {
        // given
        val spot = Spot(id = 1L, sector = "A", lat = -23.5, lng = -46.6, occupied = true)

        // when / then
        assertFailsWith<SpotAlreadyOccupiedException> { spot.occupy() }
    }

    @Test
    fun `given spot ocupado when release then marca como livre`() {
        // given
        val spot = Spot(id = 1L, sector = "A", lat = 0.0, lng = 0.0, occupied = true)

        // when
        spot.release()

        // then
        assertFalse(spot.occupied)
    }

    @Test
    fun `given spot existente when syncWith then atualiza atributos mutaveis`() {
        // given
        val spot = Spot(id = 1L, sector = "A", lat = 0.0, lng = 0.0, occupied = false)

        // when
        spot.syncWith(sector = "B", lat = -10.0, lng = -20.0, occupied = true)

        // then
        assertEquals("B", spot.sector)
        assertEquals(-10.0, spot.lat)
        assertEquals(-20.0, spot.lng)
        assertTrue(spot.occupied)
    }
}
