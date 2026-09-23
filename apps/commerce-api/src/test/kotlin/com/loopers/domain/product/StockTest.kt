package com.loopers.domain.product

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StockTest {
    @Test
    fun allowsZeroInitialStock() {
        assertEquals(0, Stock(0).remaining)
    }

    @Test
    fun rejectsNegativeInitialStock() {
        assertThrows<IllegalArgumentException> { Stock(-1) }
    }

    @Test
    fun decreasesStockWithoutChangingOriginalValue() {
        val stock = Stock(5)

        val decreased = stock.decrease(2)

        assertEquals(3, decreased.remaining)
        assertEquals(5, stock.remaining)
    }

    @Test
    fun allowsDecreasingAllRemainingStock() {
        assertEquals(0, Stock(5).decrease(5).remaining)
    }

    @Test
    fun rejectsZeroQuantityAndPreservesOriginalValue() {
        val stock = Stock(5)

        assertThrows<IllegalArgumentException> { stock.decrease(0) }

        assertEquals(5, stock.remaining)
    }

    @Test
    fun rejectsNegativeQuantityAndPreservesOriginalValue() {
        val stock = Stock(5)

        assertThrows<IllegalArgumentException> { stock.decrease(-1) }

        assertEquals(5, stock.remaining)
    }

    @Test
    fun rejectsQuantityGreaterThanRemaining() {
        val stock = Stock(5)

        assertThrows<InsufficientStockException> { stock.decrease(6) }

        assertEquals(5, stock.remaining)
    }

    @Test
    fun adjustsFinalStockWithoutChangingOriginalValue() {
        val stock = Stock(5)

        val adjusted = stock.adjustTo(8)

        assertEquals(8, adjusted.remaining)
        assertEquals(5, stock.remaining)
    }

    @Test
    fun allowsAdjustingFinalStockToZero() {
        assertEquals(0, Stock(5).adjustTo(0).remaining)
    }

    @Test
    fun rejectsNegativeFinalStockAndPreservesOriginalValue() {
        val stock = Stock(5)

        assertThrows<IllegalArgumentException> { stock.adjustTo(-1) }

        assertEquals(5, stock.remaining)
    }
}
