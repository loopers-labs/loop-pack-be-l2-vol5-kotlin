package com.loopers.domain.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.ZonedDateTime

@Entity
@Table(
    name = "outbox_event",
    indexes = [
        Index(name = "idx_outbox_event_status_id", columnList = "status, id"),
        Index(name = "idx_outbox_event_event_id", columnList = "event_id"),
    ],
)
class OutboxEvent(
    eventId: String,
    topic: String,
    eventType: String,
    schemaVersion: Int,
    payload: String,
    key: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @Column(name = "event_id", nullable = false, updatable = false, length = 100)
    var eventId: String = eventId
        protected set

    @Column(name = "topic", nullable = false, updatable = false, length = 100)
    var topic: String = topic
        protected set

    @Column(name = "event_type", nullable = false, updatable = false, length = 50)
    var eventType: String = eventType
        protected set

    @Column(name = "schema_version", nullable = false, updatable = false)
    var schemaVersion: Int = schemaVersion
        protected set

    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "TEXT")
    var payload: String = payload
        protected set

    @Column(name = "message_key", nullable = false, updatable = false, length = 100)
    var key: String = key
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: OutboxStatus = OutboxStatus.PENDING
        protected set

    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: ZonedDateTime
        protected set

    @PrePersist
    private fun prePersist() {
        createdAt = ZonedDateTime.now()
    }
}
