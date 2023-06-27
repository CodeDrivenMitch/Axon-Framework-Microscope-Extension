package com.insidion.axon.microscope

import io.micrometer.core.instrument.Tags
import org.axonframework.messaging.Message
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork
import org.axonframework.micrometer.TagsUtil
import org.axonframework.tracing.Span
import org.axonframework.tracing.SpanAttributesProvider
import org.axonframework.tracing.SpanFactory
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

class MicroscopeSpanFactory(private val metricFactory: MicroscopeMetricFactory) : SpanFactory {
    override fun createRootTrace(operationNameSupplier: Supplier<String>): Span {
        return NoOpSpan()
    }

    override fun createHandlerSpan(operationNameSupplier: Supplier<String>, parentMessage: Message<*>?, isChildTrace: Boolean, vararg linkedParents: Message<*>?): Span {
        return NoOpSpan()
    }

    override fun createDispatchSpan(operationNameSupplier: Supplier<String>, parentMessage: Message<*>?, vararg linkedSiblings: Message<*>?): Span {
        return NoOpSpan()
    }

    override fun createInternalSpan(operationNameSupplier: Supplier<String>): Span {
        val name = operationNameSupplier.get()
        if (name == "LockingRepository.obtainLock") {
            return TimeRecordingSpan("aggregateLockTime")
        }
        if (name.contains(".load ")) {
            return TimeRecordingSpan("aggregateLoadTime")
        }
        return if (name.endsWith(".commit")) {
            TimeRecordingSpan("eventCommitTime")
        } else NoOpSpan()
    }

    override fun createInternalSpan(operationNameSupplier: Supplier<String>, message: Message<*>?): Span {
        return NoOpSpan()
    }

    override fun registerSpanAttributeProvider(provider: SpanAttributesProvider) {}
    override fun <M : Message<*>?> propagateContext(message: M): M {
        return message
    }

    private inner class TimeRecordingSpan internal constructor(private val timerName: String) : Span {
        private var start: Long = 0
        override fun start(): Span {
            start = System.currentTimeMillis()
            return this
        }

        override fun end() {
            val time = System.currentTimeMillis() - start
            metricFactory.createTimer(timerName, Tags.of(TagsUtil.PAYLOAD_TYPE_TAG, CurrentUnitOfWork.get().message.payloadType.simpleName))
                    .record(time, TimeUnit.MILLISECONDS)
        }

        override fun recordException(t: Throwable): Span {
            return this
        }
    }

    private inner class NoOpSpan : Span {
        override fun start(): Span {
            return this
        }

        override fun end() {}
        override fun recordException(t: Throwable): Span {
            return this
        }
    }
}
