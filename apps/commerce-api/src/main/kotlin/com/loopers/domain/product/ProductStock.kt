package com.loopers.domain.product

import com.loopers.domain.BaseEntity
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "product_stock",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_product_stock_product", columnNames = ["product_id"]),
    ],
)
class ProductStock(
    productId: Long,
    stock: Stock,
) : BaseEntity() {
    @Column(name = "product_id", nullable = false, updatable = false)
    var productId: Long = productId
        protected set

    @Embedded
    @AttributeOverride(name = "quantity", column = Column(name = "quantity", nullable = false))
    var stock: Stock = stock
        protected set

    fun change(quantity: Int) {
        this.stock = Stock(quantity)
    }
}
