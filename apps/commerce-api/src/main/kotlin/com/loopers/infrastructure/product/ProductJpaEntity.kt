package com.loopers.infrastructure.product

import com.loopers.domain.BaseEntity
import com.loopers.domain.commerce.EntityState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.Check

@Entity
@Check(constraints = "price >= 0 and stock >= 0")
@Table(
    name = "products",
    indexes = [
        Index(columnList = "deleted_at,brand_id,created_at,id"),
        Index(columnList = "deleted_at,brand_id,price,id"),
    ],
)
class ProductJpaEntity(
    @Column(name = "brand_id", nullable = false)
    val brandId: Long,
    @Column(nullable = false, length = 255)
    var name: String,
    @Column(nullable = false)
    var price: Long,
    @Column(nullable = false)
    var stock: Int,
) : BaseEntity() {
    fun toEntity(): ProductEntity = ProductEntity(brandId, name, price, stock, EntityState(id, createdAt, updatedAt, deletedAt))

    fun updateFrom(entity: ProductEntity) {
        name = entity.name
        price = entity.price
        stock = entity.stock
        deletedAt = entity.state.deletedAt
    }
}
