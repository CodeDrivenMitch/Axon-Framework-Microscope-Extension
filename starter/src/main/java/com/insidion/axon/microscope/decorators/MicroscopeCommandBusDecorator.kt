package com.insidion.axon.microscope.decorators

import com.insidion.axon.microscope.MicroscopeEventRecorder
import org.axonframework.commandhandling.CommandBus
import org.axonframework.commandhandling.CommandCallback
import org.axonframework.commandhandling.CommandMessage
import org.axonframework.commandhandling.CommandResultMessage

class MicroscopeCommandBusDecorator(
    private val delegate: CommandBus,
    private val recorder: MicroscopeEventRecorder
) : CommandBus by delegate {
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