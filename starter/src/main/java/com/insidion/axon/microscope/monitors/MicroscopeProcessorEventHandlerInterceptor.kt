package com.insidion.axon.microscope.monitors

import com.insidion.axon.microscope.MicroscopeEventRecorder
import org.axonframework.messaging.InterceptorChain
import org.axonframework.messaging.Message
import org.axonframework.messaging.MessageHandlerInterceptor
import org.axonframework.messaging.unitofwork.BatchingUnitOfWork
import org.axonframework.messaging.unitofwork.UnitOfWork

class MicroscopeProcessorEventHandlerInterceptor(
    private val eventRecorder: MicroscopeEventRecorder,
) : MessageHandlerInterceptor<Message<*>> {
    override fun handle(uow: UnitOfWork<out Message<*>>, p1: InterceptorChain): Any? {
        if (uow is BatchingUnitOfWork) {
            if (uow.isFirstMessage) {
                uow.resources()["microscope_callback"] = eventRecorder.recordEvent("batch(size=${uow.messages.size})")
                uow.onCleanup {
                    (uow.resources()["microscope_callback"] as MicroscopeEventRecorder.RecordCallback).end()
                }
            }
        }
        val recording = eventRecorder.recordEvent("processEventMessage")
        try {
            return p1.proceed()
        } finally {
            recording.end()
        }
    }
}
