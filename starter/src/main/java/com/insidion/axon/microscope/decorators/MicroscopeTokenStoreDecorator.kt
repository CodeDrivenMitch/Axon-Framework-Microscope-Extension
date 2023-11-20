package com.insidion.axon.microscope.decorators

import com.insidion.axon.microscope.MicroscopeEventRecorder
import org.axonframework.eventhandling.Segment
import org.axonframework.eventhandling.TrackingToken
import org.axonframework.eventhandling.tokenstore.TokenStore
import java.util.*

class MicroscopeTokenStoreDecorator(
    private val delegate: TokenStore,
    private val eventRecorder: MicroscopeEventRecorder,
) : TokenStore by delegate {
    override fun storeToken(token: TrackingToken?, processorName: String, segment: Int) {
        eventRecorder.recordEvent("storeToken", processorName, segment)
                .record {
                    delegate.storeToken(token, processorName, segment)
                }
    }

    override fun fetchToken(processorName: String, segment: Int): TrackingToken? {
        return eventRecorder.recordEvent("fetchToken", processorName, segment)
            .record {
                    delegate.fetchToken(processorName, segment)
            }
    }

    override fun fetchToken(processorName: String, segment: Segment): TrackingToken? {
        return eventRecorder.recordEvent("fetchToken", processorName, segment.segmentId)
            .record {
                    delegate.fetchToken(processorName, segment)
            }
    }

    override fun extendClaim(processorName: String, segment: Int) {
        delegate.extendClaim(processorName, segment)
    }

    override fun requiresExplicitSegmentInitialization(): Boolean {
        return delegate.requiresExplicitSegmentInitialization()
    }

    override fun initializeTokenSegments(processorName: String, segmentCount: Int) {
        delegate.initializeTokenSegments(processorName, segmentCount)
    }

    override fun initializeTokenSegments(processorName: String, segmentCount: Int, initialToken: TrackingToken?) {
        delegate.initializeTokenSegments(processorName, segmentCount, initialToken)
    }

    override fun initializeSegment(token: TrackingToken?, processorName: String, segment: Int) {
        delegate.initializeSegment(token, processorName, segment)
    }

    override fun deleteToken(processorName: String, segment: Int) {
        delegate.deleteToken(processorName, segment)
    }

    override fun fetchAvailableSegments(processorName: String): MutableList<Segment> {
        return delegate.fetchAvailableSegments(processorName)
    }

    override fun retrieveStorageIdentifier(): Optional<String> {
        return delegate.retrieveStorageIdentifier()
    }
}
