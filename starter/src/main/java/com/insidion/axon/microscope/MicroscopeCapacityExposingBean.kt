package com.insidion.axon.microscope

import io.micrometer.core.instrument.MeterRegistry
import org.axonframework.axonserver.connector.command.AxonServerCommandBus
import org.axonframework.axonserver.connector.query.AxonServerQueryBus
import org.axonframework.commandhandling.CommandBus
import org.axonframework.common.ReflectionUtils
import org.axonframework.queryhandling.QueryBus
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor

@Component
class MicroscopeCapacityExposingBean(
        meterRegistry: MeterRegistry,
        configurationRegistry: MicroscopeConfigurationRegistry,
        commandBus: CommandBus,
        queryBus: QueryBus,
) {
    init {
        if (commandBus is AxonServerCommandBus) {
            val executorField = commandBus::class.java.getDeclaredField("executorService")
            val executor = ReflectionUtils.getFieldValue<ExecutorService>(executorField, commandBus)
            if (executor is ThreadPoolExecutor) {
                meterRegistry.gauge("commandBus_capacity_total", executor.corePoolSize)
                configurationRegistry.registerConfigurationValue("commandBus_capacity", "${executor.corePoolSize}")
            }
        }
        if (queryBus is AxonServerQueryBus) {
            val executorField = queryBus::class.java.getDeclaredField("queryExecutor")
            val executor = ReflectionUtils.getFieldValue<ExecutorService>(executorField, queryBus)
            if (executor is ThreadPoolExecutor) {
                meterRegistry.gauge("queryBus_capacity_total", executor.corePoolSize)
                configurationRegistry.registerConfigurationValue("queryBus_capacity", "${executor.corePoolSize}")
            }
        }
    }
}
