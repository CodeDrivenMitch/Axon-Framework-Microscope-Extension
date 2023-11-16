package com.insidion.axon.microscope.decorators

import com.insidion.axon.microscope.MicroscopeEventRecorder
import org.axonframework.eventhandling.Segment
import org.axonframework.eventhandling.TrackingToken
import org.axonframework.eventhandling.tokenstore.TokenStore

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
}
