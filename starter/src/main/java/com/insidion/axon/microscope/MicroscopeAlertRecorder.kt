package com.insidion.axon.microscope

import com.insidion.axon.microscope.api.MicroscopeAlert
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Component
class MicroscopeAlertRecorder(
    private val eventRecorder: MicroscopeEventRecorder,
) {
    private val logger = LoggerFactory.getLogger("MicroscopeAlertRecorder")

    // The alert registry rolls over every minute, to prevent ConcurrentModificationExceptions when cleaning up
    private val alertRegistry = ConcurrentHashMap<Long, MutableList<MicroscopeAlert>>()

    fun getAlerts(): List<MicroscopeAlert> = alertRegistry.flatMap { it.value }

    fun reportAlert(component: String, description: String) {
        val currentTime = System.currentTimeMillis()
        val alert = MicroscopeAlert(
            component = component,
            description = description,
            timestamp = currentTime,
            parentTrace = eventRecorder.currentIdentifier()
        )

        val bucket = currentTime / 60000
        alertRegistry
            .computeIfAbsent(bucket) {
                alertRegistry.keys().iterator().forEach {
                    if (it < bucket - 30) {
                        alertRegistry.remove(it)
                    }
                }
                CopyOnWriteArrayList()
            }
            .add(alert)
        logger.warn("Microscope Alert for $component: $description. Parent trace: ${alert.parentTrace}")
    }
}