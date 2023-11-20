package com.insidion.axon.microscope.decorators

import com.insidion.axon.microscope.MicroscopeConfigurationRegistry
import com.insidion.axon.microscope.MicroscopeEventRecorder
import io.micrometer.core.instrument.MeterRegistry
import org.axonframework.axonserver.connector.command.AxonServerCommandBus
import org.axonframework.commandhandling.CommandBus
import org.axonframework.commandhandling.CommandCallback
import org.axonframework.commandhandling.CommandMessage
import org.axonframework.commandhandling.CommandResultMessage
import org.axonframework.common.ReflectionUtils
import org.axonframework.common.Registration
import org.axonframework.messaging.MessageDispatchInterceptor
import org.axonframework.messaging.MessageHandler
import org.axonframework.messaging.MessageHandlerInterceptor
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor

class MicroscopeCommandBusDecorator(
    private val delegate: CommandBus,
    private val recorder: MicroscopeEventRecorder,
    private val meterRegistry: MeterRegistry,
    private val configurationRegistry: MicroscopeConfigurationRegistry,
) : CommandBus by delegate {
    init {
        if (delegate is AxonServerCommandBus) {
            val executorField = delegate::class.java.getDeclaredField("executorService")
            val executor = ReflectionUtils.getFieldValue<ExecutorService>(executorField, delegate)
            if (executor is ThreadPoolExecutor) {
                meterRegistry.gauge("commandBus_capacity_total", executor.corePoolSize)
                configurationRegistry.registerConfigurationValue("commandBus_capacity") { "${executor.corePoolSize}" }
            }
        }
    }

    override fun <C : Any?> dispatch(cmd: CommandMessage<C>) {
        val recording = recorder.recordEvent("dispatchCommand", msg = cmd)
        delegate.dispatch<C, Any?>(cmd) { _, _ -> recording.end() }
    }

    override fun <C : Any?, R : Any?> dispatch(cmd: CommandMessage<C>, p1: CommandCallback<in C, in R>) {
        val recording = recorder.recordEvent("dispatchCommand", msg = cmd)
        delegate.dispatch<C, Any?>(cmd) { commandMessage, commandResultMessage ->
            recording.end()
            p1.onResult(commandMessage, commandResultMessage as CommandResultMessage<R>)
        }

    }
}