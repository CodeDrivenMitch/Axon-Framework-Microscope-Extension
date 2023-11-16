package com.insidion.axon.microscope.api

import org.axonframework.messaging.Message
import java.util.*

data class MicroscopeEvent(
    val name: String,
    val timestampStart: Long,
    val parent: String? = null,
    val message: Message<*>? = null,
    val eventProcessor: EventProcessorDetails?,
    var timestampStartEnd: Long? = null,
    var erroneous: Boolean = false,
    val identifier: String = UUID.randomUUID().toString(),
)

data class EventProcessorDetails(
    val name: String,
    val segment: Int,
)