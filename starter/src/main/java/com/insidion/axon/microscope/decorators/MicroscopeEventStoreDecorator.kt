package com.insidion.axon.microscope.decorators

import com.insidion.axon.microscope.MicroscopeMetricFactory
import io.micrometer.core.instrument.Tags
import org.axonframework.common.Registration
import org.axonframework.common.stream.BlockingStream
import org.axonframework.eventhandling.EventMessage
import org.axonframework.eventhandling.TrackedEventMessage
import org.axonframework.eventhandling.TrackingToken
import org.axonframework.eventsourcing.eventstore.DomainEventStream
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.messaging.MessageDispatchInterceptor
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.Supplier
import java.util.stream.Collectors

class MicroscopeEventStoreDecorator(
    private val delegate: EventStore,
    private val metricFactory: MicroscopeMetricFactory,
    private val aggregateName: String
) : EventStore by delegate {
    override fun readEvents(s: String): DomainEventStream {
        val tags = Tags.of("aggregate", aggregateName)
        val events = metricFactory.createTimer("eventStore.readEvents", tags).record(Supplier {
            delegate.readEvents(s).asStream().collect(Collectors.toList())
        })!!
        // Record number of events as seconds, no other way around it
        metricFactory.createTimer("eventStore.aggregateStreamSize", tags)
                .record(events.size.toLong(), TimeUnit.SECONDS)
        return DomainEventStream.of(events)
    }

    override fun publish(vararg events: EventMessage<*>?) {
        delegate.publish(*events)
    }

    override fun createTailToken(): TrackingToken {
        return delegate.createTailToken()
    }

    override fun createHeadToken(): TrackingToken {
        return delegate.createHeadToken()
    }

    override fun createTokenAt(dateTime: Instant?): TrackingToken {
        return delegate.createTokenAt(dateTime)
    }

    override fun createTokenSince(duration: Duration?): TrackingToken {
        return delegate.createTokenSince(duration)
    }
}
