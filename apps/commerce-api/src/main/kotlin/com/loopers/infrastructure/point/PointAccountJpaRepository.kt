package com.loopers.infrastructure.point

import org.springframework.data.jpa.repository.JpaRepository

interface PointAccountJpaRepository : JpaRepository<PointAccountJpaEntity, Long>
