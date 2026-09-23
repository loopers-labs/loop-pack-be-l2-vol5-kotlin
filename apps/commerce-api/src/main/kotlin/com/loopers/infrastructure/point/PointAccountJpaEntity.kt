package com.loopers.infrastructure.point

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Check

@Entity
@Table(name = "point_balances")
@Check(constraints = "balance >= 0")
class PointAccountJpaEntity(
    @Id
    @Column(name = "user_id")
    val userId: Long,
    @Column(nullable = false)
    var balance: Long,
) {
    fun toEntity(): PointAccountEntity = PointAccountEntity(userId, balance)
}
