package com.insidion.axon.microscope

import com.insidion.axon.microscope.api.EventProcessorDetails
import com.insidion.axon.microscope.api.MicroscopeEvent
import org.axonframework.messaging.Message
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Component
class MicroscopeEventRecorder {
    private val logger = LoggerFactory.getLogger("MicroscopeEventRecorder")

    // The event registry rolls over every minute, to prevent ConcurrentModificationExceptions when cleaning up
    private val eventRegistry = ConcurrentHashMap<Long, MutableList<MicroscopeEvent>>()

    private val currentEventDeque = ThreadLocal<Deque<MicroscopeEvent>>()

    fun getEvents(): List<MicroscopeEvent> = eventRegistry.flatMap { it.value }

    fun recordEvent(
        name: String,
        processorName: String? = null,
        segment: Int? = null,
        msg: Message<*>? = null
    ): RecordCallback {
        val processorDetails = getProcessorDetails(processorName, segment)
        val message = msg ?: CurrentUnitOfWork.map { it.message }.orElse(null)
        val currentTime = System.currentTimeMillis()
        currentEventDeque.set(currentEventDeque.get() ?: LinkedList())
        val currentDeque = currentEventDeque.get()
        val parent = if(currentDeque.isEmpty()) {
            message?.metaData?.getOrDefault(TRACE_METADATA_KEY, null) as String?
        } else {
            currentDeque.peek().identifier
        }

        val event = MicroscopeEvent(
            name = name,
            timestampStart = currentTime,
            messageType = message?.payloadType?.simpleName,
            messageIdentifier = message?.identifier,
            eventProcessor = processorDetails,
            timestampStartEnd = null,
            parent = parent
        )


        currentDeque.addFirst(event)
        val bucket = currentTime / 60000
        eventRegistry
            .computeIfAbsent(currentTime / 60000) {
                // New section; clean up older than 5 minutes
                eventRegistry.keys().iterator().forEach {
                    if (it < bucket - 5) {
                        eventRegistry.remove(it)
                    }
                }
                CopyOnWriteArrayList()
            }
            .add(event)

        logger.debug("+ $event")
        return object : RecordCallback {
            override fun end() {
                currentEventDeque.get()?.remove(event)

                event.timestampStartEnd = System.currentTimeMillis()
                logger.debug("- $event")
            }

        }
    }

    fun currentIdentifier() = currentEventDeque.get()?.peek()?.identifier

    private fun getProcessorDetails(processorName: String? = null, segment: Int? = null): EventProcessorDetails? {
        val actualProcessorName = processorName ?: getCurrentProcessorName()
        if(actualProcessorName == "UNKNOWN") {
            return null
        }
        val actualSegment = segment ?: getCurrentSegment()
        return EventProcessorDetails(
            actualProcessorName,
            actualSegment
        )
    }

    @FunctionalInterface
    interface RecordCallback {
        fun end()

        fun <T : Any?> record(block: () -> T): T {
            try {
                return block()
            } finally {
                end()
            }
        }
    }
}