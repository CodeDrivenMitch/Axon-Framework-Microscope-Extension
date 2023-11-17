package com.insidion.axon.microscope.decorators

import com.insidion.axon.microscope.*
import io.micrometer.core.instrument.Tags
import org.axonframework.axonserver.connector.PriorityRunnable
import org.axonframework.messaging.Message
import org.axonframework.micrometer.TagsUtil
import org.axonframework.tracing.Span
import org.axonframework.tracing.SpanFactory
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.function.Function

/**
 * Can decorate a blocking queue that processes messages. It will measure the time between creation of the message
 * and the time it is put into the queue, as well the time it was in the queue.
 *
 */
class MicroscopeWorkQueueDecorator(
    private val prefix: String,
    private val delegate: BlockingQueue<Runnable>,
    private val metricFactory: MicroscopeMetricFactory,
    private val eventRecorder: MicroscopeEventRecorder,
    private val messageSupplier: Function<PriorityRunnable, Message<*>?>,
    private val spanFactory: SpanFactory,
) : BlockingQueue<Runnable> by delegate {
    private val ingestTime: MutableMap<String, Long> = ConcurrentHashMap()
    private val spans: MutableMap<String, Span> = ConcurrentHashMap()
    private val recordings: MutableMap<String, MicroscopeEventRecorder.RecordCallback> = ConcurrentHashMap()

    private fun measureTake(runnable: Runnable): Runnable {
        try {
            if (runnable !is PriorityRunnable) {
                return runnable
            }
            val message = messageSupplier.apply(runnable)
            if (message != null && ingestTime.containsKey(message.identifier)) {
                metricFactory.createTimer(
                        name = "$prefix.ingest.queueTime",
                        tags = Tags.of(TagsUtil.PAYLOAD_TYPE_TAG, message.payloadType.simpleName)
                ).record(System.currentTimeMillis() - ingestTime[message.identifier]!!, TimeUnit.MILLISECONDS)
                ingestTime.remove(message.identifier)
                if(recordings.containsKey(message.identifier)) {
                    recordings[message.identifier]?.end()
                    recordings.remove(message.identifier)
                }
                val currentSpan = spans[message.identifier] ?: return runnable
                val handlingSpan = currentSpan.makeCurrent().use {
                    spanFactory.createInternalSpan({ "Handling" }, message)
                }
                currentSpan.end()
                spans.remove(message.identifier)
                return PriorityRunnable({
                    handlingSpan.run {
                        runnable.run()
                    }
                }, runnable.priority(), runnable.sequence())
            }
            return runnable
        } catch (e: Exception) {
            println("Error: ${e.message}")
            e.printStackTrace()
            return runnable
        }
    }

    private fun measurePut(runnable: Runnable?) {
        try {
            if (runnable !is PriorityRunnable) {
                return
            }
            val message = messageSupplier.apply(runnable)
            if (message != null && message.metaData.containsKey(TIME_METADATA_KEY)) {
                val time = message.metaData[TIME_METADATA_KEY] as Long?
                metricFactory.createTimer(
                        name = "$prefix.ingest.latency",
                        tags = Tags.of(TagsUtil.PAYLOAD_TYPE_TAG, message.payloadType.simpleName)
                ).record(System.currentTimeMillis() - time!!, TimeUnit.MILLISECONDS)
                ingestTime[message.identifier] = System.currentTimeMillis()

                spans[message.identifier] = spanFactory.createHandlerSpan({ "WorkQueue" }, message, true).start()
            }

            if(message != null && message.metaData.containsKey(TRACE_METADATA_KEY)) {
                recordings[message.identifier] = eventRecorder.recordEvent("WorkQueue", msg = message)
            }
        } catch (e: Exception) {
            println("Error: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun add(element: Runnable): Boolean {
        measurePut(element)
        return delegate.add(element)
    }

    override fun offer(runnable: Runnable): Boolean {
        measurePut(runnable)
        return delegate.offer(runnable)
    }

    override fun put(runnable: Runnable) {
        measurePut(runnable)
        delegate.put(runnable)
    }

    override fun offer(runnable: Runnable, timeout: Long, unit: TimeUnit): Boolean {
        measurePut(runnable)
        return delegate.offer(runnable, timeout, unit)
    }

    override fun take(): Runnable {
        return measureTake(delegate.take())
    }

    override fun poll(timeout: Long, unit: TimeUnit): Runnable? {
        val runnable = delegate.poll(timeout, unit) ?: return null
        return measureTake(runnable)
    }

}
