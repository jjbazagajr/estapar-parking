package com.estapar.parking.garage

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

class PricingPolicyTest {

    private val pricing = PricingPolicy()

    @Test
    fun `given ocupacao 0 porcento when multiplierFor then retorna 0_90`() {
        // given
        val occupancy = 0.0

        // when
        val multiplier = pricing.multiplierFor(occupancy)

        // then
        assertEquals(0, BigDecimal("0.90").compareTo(multiplier))
    }

    @Test
    fun `given ocupacao logo abaixo de 25 porcento when multiplierFor then retorna 0_90`() {
        // given
        val occupancy = 0.2499

        // when
        val multiplier = pricing.multiplierFor(occupancy)

        // then
        assertEquals(0, BigDecimal("0.90").compareTo(multiplier))
    }

    @Test
    fun `given ocupacao exata 25 porcento when multiplierFor then retorna 1_00`() {
        // given
        val occupancy = 0.25

        // when
        val multiplier = pricing.multiplierFor(occupancy)

        // then
        assertEquals(0, BigDecimal("1.00").compareTo(multiplier))
    }

    @Test
    fun `given ocupacao logo abaixo de 50 porcento when multiplierFor then retorna 1_00`() {
        // given
        val occupancy = 0.4999

        // when
        val multiplier = pricing.multiplierFor(occupancy)

        // then
        assertEquals(0, BigDecimal("1.00").compareTo(multiplier))
    }

    @Test
    fun `given ocupacao exata 50 porcento when multiplierFor then retorna 1_10`() {
        // given
        val occupancy = 0.50

        // when
        val multiplier = pricing.multiplierFor(occupancy)

        // then
        assertEquals(0, BigDecimal("1.10").compareTo(multiplier))
    }

    @Test
    fun `given ocupacao logo abaixo de 75 porcento when multiplierFor then retorna 1_10`() {
        // given
        val occupancy = 0.7499

        // when
        val multiplier = pricing.multiplierFor(occupancy)

        // then
        assertEquals(0, BigDecimal("1.10").compareTo(multiplier))
    }

    @Test
    fun `given ocupacao exata 75 porcento when multiplierFor then retorna 1_25`() {
        // given
        val occupancy = 0.75

        // when
        val multiplier = pricing.multiplierFor(occupancy)

        // then
        assertEquals(0, BigDecimal("1.25").compareTo(multiplier))
    }

    @Test
    fun `given ocupacao 100 porcento when multiplierFor then retorna 1_25`() {
        // given
        val occupancy = 1.0

        // when
        val multiplier = pricing.multiplierFor(occupancy)

        // then
        assertEquals(0, BigDecimal("1.25").compareTo(multiplier))
    }

    @Test
    fun `given duracao 0 segundos when feeFor then retorna 0_00`() {
        // given
        val seconds = 0L

        // when
        val fee = pricing.feeFor(seconds, BigDecimal("40.50"), BigDecimal("1.000"))

        // then
        assertEquals(BigDecimal("0.00"), fee)
    }

    @Test
    fun `given duracao exata 30 min when feeFor then retorna 0_00`() {
        // given
        val seconds = 30L * 60

        // when
        val fee = pricing.feeFor(seconds, BigDecimal("40.50"), BigDecimal("1.000"))

        // then
        assertEquals(BigDecimal("0.00"), fee)
    }

    @Test
    fun `given duracao 30 min e 1 segundo when feeFor then cobra 1 hora`() {
        // given
        val seconds = 30L * 60 + 1

        // when
        val fee = pricing.feeFor(seconds, BigDecimal("40.50"), BigDecimal("1.000"))

        // then 1 × 40.50 × 1.000 = 40.50
        assertEquals(BigDecimal("40.50"), fee)
    }

    @Test
    fun `given duracao exata 60 min when feeFor then cobra 1 hora`() {
        // given
        val seconds = 60L * 60

        // when
        val fee = pricing.feeFor(seconds, BigDecimal("40.50"), BigDecimal("1.000"))

        // then
        assertEquals(BigDecimal("40.50"), fee)
    }

    @Test
    fun `given duracao 60 min e 1 segundo when feeFor then cobra 2 horas`() {
        // given
        val seconds = 60L * 60 + 1

        // when
        val fee = pricing.feeFor(seconds, BigDecimal("40.50"), BigDecimal("1.000"))

        // then 2 × 40.50 × 1.000 = 81.00
        assertEquals(BigDecimal("81.00"), fee)
    }

    @Test
    fun `given basePrice 40_50 e multiplicador 0_900 e duracao 1h when feeFor then retorna 36_45`() {
        // given
        val seconds = 60L * 60

        // when
        val fee = pricing.feeFor(seconds, BigDecimal("40.50"), BigDecimal("0.900"))

        // then 1 × 40.50 × 0.900 = 36.4500 → 36.45
        assertEquals(BigDecimal("36.45"), fee)
    }

    @Test
    fun `given basePrice 4_10 e multiplicador 1_250 e duracao 2h when feeFor then retorna 10_25`() {
        // given
        val seconds = 2L * 60 * 60

        // when
        val fee = pricing.feeFor(seconds, BigDecimal("4.10"), BigDecimal("1.250"))

        // then 2 × 4.10 × 1.250 = 10.2500 → 10.25
        assertEquals(BigDecimal("10.25"), fee)
    }

    @Test
    fun `given basePrice 40_50 e multiplicador 1_100 e duracao 25h when feeFor then retorna 1113_75`() {
        // given
        val seconds = 25L * 60 * 60

        // when
        val fee = pricing.feeFor(seconds, BigDecimal("40.50"), BigDecimal("1.100"))

        // then 25 × 40.50 × 1.100 = 1113.7500 → 1113.75
        assertEquals(BigDecimal("1113.75"), fee)
    }
}
