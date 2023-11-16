package com.insidion.axon.microscope.monitors

import com.insidion.axon.microscope.MicroscopeMetricFactory
import io.micrometer.core.instrument.Tags
import org.axonframework.eventhandling.EventMessage
import org.axonframework.messaging.Message
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork
import org.axonframework.messaging.unitofwork.UnitOfWork
import org.axonframework.monitoring.MessageMonitor
import org.axonframework.monitoring.MessageMonitor.MonitorCallback
import org.axonframework.monitoring.NoOpMessageMonitorCallback
import java.util.concurrent.TimeUnit

class MicroscopeLatencyMessageMonitor(
    private val componentName: String,
    private val metricFactory: MicroscopeMetricFactory,
    private val metadataField: String,
    private val tagsBuilder: (Message<*>) -> Tags,
) : MessageMonitor<Message<*>> {
    override fun onMessageIngested(message: Message<*>): MonitorCallback {
        val timestampFromMessage = getTimestampFromMessage(message)
        if (timestampFromMessage > 0) {
            val tags = tagsBuilder.invoke(message)
            metricFactory.createTimer("$componentName.latency.ingest", tags)
                .record(System.currentTimeMillis() - timestampFromMessage, TimeUnit.MILLISECONDS)

            CurrentUnitOfWork.ifStarted { uow: UnitOfWork<*> ->
                uow.afterCommit { _ ->
                    metricFactory.createTimer("$componentName.latency.commit", tags)
                        .record(System.currentTimeMillis() - timestampFromMessage, TimeUnit.MILLISECONDS)
                }
            }
        }
        return NoOpMessageMonitorCallback.INSTANCE
    }

    private fun getTimestampFromMessage(message: Message<*>): Long {
        if (message is EventMessage<*>) {
            return message.timestamp.toEpochMilli()
        }
        return if (message.metaData.containsKey(metadataField)) {
            message.metaData[metadataField] as Long
        } else -1
    }
}
