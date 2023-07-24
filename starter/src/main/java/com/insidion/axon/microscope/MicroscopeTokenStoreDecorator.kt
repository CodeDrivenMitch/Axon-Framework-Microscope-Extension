package com.insidion.axon.microscope

import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import org.axonframework.eventhandling.Segment
import org.axonframework.eventhandling.TrackingToken
import org.axonframework.eventhandling.tokenstore.TokenStore
import java.util.function.Supplier

class MicroscopeTokenStoreDecorator(
        private val delegate: TokenStore,
        private val metricFactory: MicroscopeMetricFactory
) : TokenStore by delegate {
    override fun storeToken(token: TrackingToken?, processorName: String, segment: Int) {
        metricFactory.createTimer("${delegate.javaClass.simpleName}.storeToken", Tags.of(Tag.of("processor", processorName)))
                .record {
                    delegate.storeToken(token, processorName, segment)
                }
    }

    override fun fetchToken(processorName: String, segment: Int): TrackingToken? {
        return metricFactory.createTimer("${delegate.javaClass.simpleName}.fetchToken", Tags.of(Tag.of("processor", processorName)))
                .record(Supplier {
                    delegate.fetchToken(processorName, segment)
                })
    }

    override fun fetchToken(processorName: String, segment: Segment): TrackingToken? {
        return metricFactory.createTimer("${delegate.javaClass.simpleName}.fetchToken", Tags.of(Tag.of("processor", processorName)))
                .record(Supplier {
                    delegate.fetchToken(processorName, segment)
                })
    }

    override fun extendClaim(processorName: String, segment: Int) {
        metricFactory.createTimer("${delegate.javaClass.simpleName}.extendClaim", Tags.of(Tag.of("processor", processorName)))
                .record {
                    delegate.extendClaim(processorName, segment)
                }
    }

    override fun requiresExplicitSegmentInitialization(): Boolean {
        return delegate.requiresExplicitSegmentInitialization()
    }
}
