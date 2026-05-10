package com.estapar.parking.webhook

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    property = "event_type",
    visible = true,
)
@JsonSubTypes(
    JsonSubTypes.Type(value = EntryEvent::class, name = "ENTRY"),
    JsonSubTypes.Type(value = ParkedEvent::class, name = "PARKED"),
    JsonSubTypes.Type(value = ExitEvent::class, name = "EXIT"),
)
@JsonIgnoreProperties(ignoreUnknown = true)
sealed interface WebhookEvent {
    val licensePlate: String
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class EntryEvent(
    override val licensePlate: String,
    val entryTime: Instant,
) : WebhookEvent

@JsonIgnoreProperties(ignoreUnknown = true)
data class ParkedEvent(
    override val licensePlate: String,
    val lat: Double,
    val lng: Double,
) : WebhookEvent

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExitEvent(
    override val licensePlate: String,
    val exitTime: Instant,
) : WebhookEvent
