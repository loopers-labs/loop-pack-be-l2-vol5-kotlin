package com.loopers.domain.product

data class Stock(
    val remaining: Int,
) {
    init {
        require(remaining >= 0) { "재고는 음수일 수 없습니다." }
    }

    fun decrease(quantity: Int): Stock {
        require(quantity > 0) { "차감 수량은 양수여야 합니다." }
        if (quantity > remaining) throw InsufficientStockException()
        return Stock(remaining - quantity)
    }

    fun adjustTo(finalQuantity: Int): Stock = Stock(finalQuantity)
}
