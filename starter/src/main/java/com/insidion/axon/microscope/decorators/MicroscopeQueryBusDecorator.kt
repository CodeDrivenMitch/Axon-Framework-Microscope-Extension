package com.insidion.axon.microscope.decorators

import com.insidion.axon.microscope.MicroscopeConfigurationRegistry
import com.insidion.axon.microscope.MicroscopeEventRecorder
import io.micrometer.core.instrument.MeterRegistry
import org.axonframework.axonserver.connector.query.AxonServerQueryBus
import org.axonframework.common.ReflectionUtils
import org.axonframework.queryhandling.QueryBus
import org.axonframework.queryhandling.QueryMessage
import org.axonframework.queryhandling.QueryResponseMessage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor

class MicroscopeQueryBusDecorator(
    private val delegate: QueryBus,
    private val recorder: MicroscopeEventRecorder,
    private val meterRegistry: MeterRegistry,
    private val configurationRegistry: MicroscopeConfigurationRegistry,
) : QueryBus by delegate {
    init {

        if (delegate is AxonServerQueryBus) {
            val executorField = delegate::class.java.getDeclaredField("queryExecutor")
            val executor = ReflectionUtils.getFieldValue<ExecutorService>(executorField, delegate)
            if (executor is ThreadPoolExecutor) {
                meterRegistry.gauge("queryBus_capacity_total", executor.corePoolSize)
                configurationRegistry.registerConfigurationValue("queryBus_capacity", "${executor.corePoolSize}")
            }
        }
    }

    override fun <Q : Any?, R : Any?> query(query: QueryMessage<Q, R>): CompletableFuture<QueryResponseMessage<R>> {
        val recording = recorder.recordEvent("query", msg=query)
        return delegate.query(query)
            .whenComplete { _, _ ->
                recording.end()
            }
    }
}