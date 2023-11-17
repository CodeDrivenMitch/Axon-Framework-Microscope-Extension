package com.insidion.axon.microscope.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import org.axonframework.messaging.Message
import java.util.*

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MicroscopeEvent(
    @JsonProperty("n")
    val name: String,
    @JsonProperty("ts")
    val timestampStart: Long,
    @JsonProperty("p")
    val parent: String? = null,
    @JsonProperty("mt")
    val messageType: String? = null,
    @JsonProperty("mi")
    val messageIdentifier: String? = null,
    @JsonProperty("e")
    val eventProcessor: EventProcessorDetails?,
    @JsonProperty("te")
    var timestampStartEnd: Long? = null,
    @JsonProperty("i")
    val identifier: String = UUID.randomUUID().toString().split("-").last(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class EventProcessorDetails(
    @JsonProperty("n")
    val name: String,
    @JsonProperty("s")
    val segment: Int,
)