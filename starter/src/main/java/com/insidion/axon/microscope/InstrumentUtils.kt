package com.insidion.axon.microscope

import com.insidion.axon.microscope.decorators.MicroscopeEventStoreDecorator
import com.insidion.axon.microscope.decorators.MicroscopeWorkQueueDecorator
import io.axoniq.axonserver.grpc.command.Command
import io.axoniq.axonserver.grpc.query.QueryRequest
import org.axonframework.axonserver.connector.PriorityRunnable
import org.axonframework.axonserver.connector.command.AxonServerCommandBus
import org.axonframework.axonserver.connector.command.CommandSerializer
import org.axonframework.axonserver.connector.query.AxonServerQueryBus
import org.axonframework.axonserver.connector.query.QuerySerializer
import org.axonframework.common.AxonThreadFactory
import org.axonframework.common.ReflectionUtils
import org.axonframework.config.AggregateConfiguration
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.modelling.command.Repository
import org.axonframework.tracing.SpanFactory
import java.util.concurrent.*

object InstrumentUtils {
    const val METADATA_FIELD = "microscope_time"
    private val taskField = PriorityRunnable::class.java.getDeclaredField("task")

    fun instrument(bean: AxonServerCommandBus, metricFactory: MicroscopeMetricFactory, eventRecorder: MicroscopeEventRecorder, spanFactory: SpanFactory) {
        instrument(bean, "executorService", "CommandProcessor") {
            val serializer = ReflectionUtils.getFieldValue<CommandSerializer>(AxonServerCommandBus::class.java.getDeclaredField("serializer"), bean)
            MicroscopeWorkQueueDecorator("axonServerCommandBus", it, metricFactory, eventRecorder, { r ->
                val task = ReflectionUtils.getFieldValue<Any>(taskField, r)
                val command = ReflectionUtils.getFieldValue<Command>(task.javaClass.getDeclaredField("command"), task)
                serializer.deserialize(command)
            }, spanFactory)
        }
    }

    fun instrument(bean: AxonServerQueryBus, metricFactory: MicroscopeMetricFactory, eventRecorder: MicroscopeEventRecorder, spanFactory: SpanFactory) {
        instrument(bean, "queryExecutor", "QueryProcessor") {
            val serializer = ReflectionUtils.getFieldValue<QuerySerializer>(AxonServerQueryBus::class.java.getDeclaredField("serializer"), bean)
            MicroscopeWorkQueueDecorator("axonServerQueryBus", it, metricFactory, eventRecorder, { r ->
                val task = ReflectionUtils.getFieldValue<Any>(taskField, r) ?: return@MicroscopeWorkQueueDecorator null
                val queryRequestField = try {
                    task.javaClass.getDeclaredField("queryRequest")
                } catch (e: Exception) {
                    null
                } ?: return@MicroscopeWorkQueueDecorator null
                val queryRequest = ReflectionUtils.getFieldValue<QueryRequest>(queryRequestField, task)
                        ?: return@MicroscopeWorkQueueDecorator null
                serializer.deserializeRequest<Any, Any>(queryRequest)
            }, spanFactory)
        }
    }


    fun instrument(repository: Repository<out Any>, metricFactory: MicroscopeMetricFactory, ac: AggregateConfiguration<*>) {
        val field = repository.javaClass.getDeclaredField("eventStore")
        val current = ReflectionUtils.getFieldValue<EventStore>(field, repository)
        if (current != null) {
            ReflectionUtils.setFieldValue(field, repository, MicroscopeEventStoreDecorator(current, metricFactory, ac.aggregateType().simpleName))
        }
    }

    private fun instrument(bean: Any, executorFieldName: String, threadGroupName: String, decoratorCreator: (BlockingQueue<Runnable>) -> BlockingQueue<Runnable>) {
        val executorField = bean::class.java.getDeclaredField(executorFieldName)
        val executor = ReflectionUtils.getFieldValue<ExecutorService>(executorField, bean)
        if (executor !is ThreadPoolExecutor) {
            return
        }
        val decoratedQueue = decoratorCreator.invoke(PriorityBlockingQueue(1000))
        val newExecutor = ThreadPoolExecutor(executor.corePoolSize, executor.corePoolSize, 100L, TimeUnit.MILLISECONDS, decoratedQueue, AxonThreadFactory(threadGroupName))
        ReflectionUtils.setFieldValue(executorField, bean, newExecutor)
    }
}
