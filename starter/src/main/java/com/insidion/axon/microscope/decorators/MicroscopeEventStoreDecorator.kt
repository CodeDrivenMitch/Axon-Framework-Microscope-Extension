package com.insidion.axon.microscope.decorators

import com.insidion.axon.microscope.MicroscopeMetricFactory
import io.micrometer.core.instrument.Tags
import org.axonframework.eventsourcing.eventstore.DomainEventStream
import org.axonframework.eventsourcing.eventstore.EventStore
import java.util.concurrent.TimeUnit
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
}
