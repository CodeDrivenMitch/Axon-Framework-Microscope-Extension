package com.insidion.axon.microscope.monitors

import com.insidion.axon.microscope.*
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
    private val alertRecorder: MicroscopeAlertRecorder,
    private val alertProperties: AlertConfigurationProperties,
    private val componentName: String,
    private val metricFactory: MicroscopeMetricFactory,
    private val tagsBuilder: (Message<*>) -> Tags,
) : MessageMonitor<Message<*>> {
    override fun onMessageIngested(message: Message<*>): MonitorCallback {
        val timestampFromMessage = getTimestampFromMessage(message)
        if (timestampFromMessage != null) {
            val tags = tagsBuilder.invoke(message)
            val ingestTime = System.currentTimeMillis() - timestampFromMessage
            if (alertProperties.ingestLatency != -1L && ingestTime > alertProperties.ingestLatency) {
                alertRecorder.reportAlert(
                    componentName,
                    "Ingest latency > ${alertProperties.commitLatency} for message ${message.identifier} of type ${message.payloadType.simpleName}"
                )
            }
            metricFactory.createTimer("$componentName.latency.ingest", tags)
                .record(ingestTime, TimeUnit.MILLISECONDS)

            CurrentUnitOfWork.ifStarted { uow: UnitOfWork<*> ->
                uow.afterCommit { _ ->
                    val commitLatency = System.currentTimeMillis() - timestampFromMessage
                    if (alertProperties.commitLatency != -1L && commitLatency > alertProperties.commitLatency) {
                        alertRecorder.reportAlert(
                            componentName,
                            "Commit latency > ${alertProperties.commitLatency} for message ${message.identifier} of type ${message.payloadType.simpleName}"
                        )
                    }
                    metricFactory.createTimer("$componentName.latency.commit", tags)
                        .record(commitLatency, TimeUnit.MILLISECONDS)
                }
            }
        }
        return NoOpMessageMonitorCallback.INSTANCE
    }
}
