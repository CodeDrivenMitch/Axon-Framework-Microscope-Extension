package com.insidion.axon.microscope.decorators

import com.insidion.axon.microscope.MicroscopeEventRecorder
import org.axonframework.queryhandling.QueryBus
import org.axonframework.queryhandling.QueryMessage
import org.axonframework.queryhandling.QueryResponseMessage
import java.util.concurrent.CompletableFuture

class MicroscopeQueryBusDecorator(
    private val delegate: QueryBus,
    private val recorder: MicroscopeEventRecorder
) : QueryBus by delegate {

    override fun <Q : Any?, R : Any?> query(query: QueryMessage<Q, R>): CompletableFuture<QueryResponseMessage<R>> {
        val recording = recorder.recordEvent("query", msg=query)
        return delegate.query(query)
            .whenComplete { _, _ ->
                recording.end()
            }
    }
}