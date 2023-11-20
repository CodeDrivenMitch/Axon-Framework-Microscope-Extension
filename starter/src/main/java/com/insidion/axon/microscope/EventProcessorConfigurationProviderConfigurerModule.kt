package com.insidion.axon.microscope

import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import org.axonframework.common.ReflectionUtils
import org.axonframework.config.Configuration
import org.axonframework.eventhandling.EventProcessor
import org.axonframework.eventhandling.MultiStreamableMessageSource
import org.axonframework.eventhandling.StreamingEventProcessor
import org.axonframework.eventhandling.pooled.PooledStreamingEventProcessor
import org.axonframework.eventhandling.tokenstore.TokenStore
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.messaging.StreamableMessageSource
import org.springframework.stereotype.Component
import java.lang.reflect.Field
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAmount
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import javax.annotation.PostConstruct

@Component
class EventProcessorConfigurationProviderConfigurerModule(
    private val configuration: Configuration,
    private val configurationRegistry: MicroscopeConfigurationRegistry,
    private val metricFactory: MicroscopeMetricFactory
) {
    @PostConstruct
    fun setup() {
        // This is not a ConfigurerModule because it triggers a circular dependency
        configuration.eventProcessingConfiguration().eventProcessors().forEach { this.provide(it) }
    }

    private fun provide(entry: Map.Entry<String, EventProcessor>) {
        val (name, processor) = entry
        configurationRegistry.registerConfigurationValue("eventProcessor.$name.type") { processor.javaClass.name }

        if (processor is StreamingEventProcessor) {
            configurationRegistry.registerConfigurationValue(
                "eventProcessor.$name.batchSize"
            ) { processor.getBatchSize().toString() }
            configurationRegistry.registerConfigurationValue(
                "eventProcessor.$name.messageSource"
            ) { processor.getMessageSource() }
            configurationRegistry.registerConfigurationValue(
                "eventProcessor.$name.tokenClaimInterval"
            ) { processor.getTokenClaimInterval().toString() }
            configurationRegistry.registerConfigurationValue(
                "eventProcessor.$name.tokenClaimTimeout"
            ) { processor.getStoreTokenClaimTimeout().toString() }
            configurationRegistry.registerConfigurationValue(
                "eventProcessor.$name.segmentCount"
            ) { processor.getSegmentCount().toString() }
            configurationRegistry.registerConfigurationValue(
                "eventProcessor.$name.segmentsClaimed"
            ) { processor.processingStatus().filter { it.value.error == null }.keys.joinToString() }

            configurationRegistry.registerConfigurationValue(
                "eventProcessor.$name.capacity"
            ) { processor.maxCapacity().toString() }
            configurationRegistry.registerConfigurationValue(
                "eventProcessor.$name.tokenStoreType"
            ) { processor.getStoreTokenStoreType() }
            configurationRegistry.registerConfigurationValue(
                "eventProcessor.$name.tokenStoreId"
            ) { processor.tokenStoreIdentifier }
            configurationRegistry.registerConfigurationValue(
                "eventProcessor.$name.interceptors"
            ) { processor.getInterceptors("interceptors") }
            configurationRegistry.registerConfigurationValue(
                "eventProcessor.$name.contexts"
            ) { processor.contexts().joinToString() }
        }
        if (processor is PooledStreamingEventProcessor) {
            providePsep(name, processor)
        }
    }

    private fun providePsep(name: String, processor: PooledStreamingEventProcessor) {
        val workerExecutor = processor.getPropertyValue<ScheduledExecutorService>("workerExecutor") ?: return
        ExecutorServiceMetrics.monitor(metricFactory.meterRegistry, workerExecutor, "eventProcessor.$name.worker")

        configurationRegistry.registerConfigurationValue("eventProcessor.$name.workerPoolSharedWith") {
            configuration.eventProcessingConfiguration().eventProcessors().filterValues {
                it is PooledStreamingEventProcessor && it.getPropertyValue<ScheduledExecutorService>("workerExecutor") === workerExecutor
            }.keys.joinToString()
        }
        if (workerExecutor is ThreadPoolExecutor) {
            configurationRegistry.registerConfigurationValue("eventProcessor.$name.workerPoolSize") {
                "${workerExecutor.poolSize}"
            }
            configurationRegistry.registerConfigurationValue("eventProcessor.$name.workerMaxPoolSize") {
                "${workerExecutor.maximumPoolSize}"
            }
            configurationRegistry.registerConfigurationValue("eventProcessor.$name.workerPoolQueueSize") {
                "${workerExecutor.queue.size}"
            }
        }

        val coordinatorExecutor = processor.getCoordinatorPool() ?: return
        ExecutorServiceMetrics.monitor(metricFactory.meterRegistry, workerExecutor, "eventProcessor.$name.coordinator")

        if (coordinatorExecutor is ThreadPoolExecutor) {
            configurationRegistry.registerConfigurationValue("eventProcessor.$name.coordinatorPoolSize") {
                "${coordinatorExecutor.poolSize}"
            }
            configurationRegistry.registerConfigurationValue("eventProcessor.$name.coordinatorMaxPoolSize") {
                "${coordinatorExecutor.maximumPoolSize}"
            }
            configurationRegistry.registerConfigurationValue("eventProcessor.$name.coordinatorQueueSize") {
                "${coordinatorExecutor.queue.size}"
            }
        }
        configurationRegistry.registerConfigurationValue("eventProcessor.$name.coordinatorPoolSharedWith") {
            configuration.eventProcessingConfiguration().eventProcessors().filterValues {
                it is PooledStreamingEventProcessor && it.getCoordinatorPool() === coordinatorExecutor
            }.keys.joinToString()
        }
    }

    private fun PooledStreamingEventProcessor.getCoordinatorPool(): ScheduledExecutorService? {
        val coordinator = getPropertyValue<Any>("coordinator") ?: return null
        return coordinator.getPropertyValue<ScheduledExecutorService>("executorService")
    }

    private fun StreamingEventProcessor.getSegmentCount(): Int {
        return getPropertyValue<TokenStore>("tokenStore")?.fetchSegments(this.name)?.size ?: -1
    }

    private fun StreamingEventProcessor.getBatchSize(): Int = getPropertyValue("batchSize") ?: -1
    private fun StreamingEventProcessor.getMessageSource(): String =
        getPropertyTypeNested("messageSource", EventStore::class.java)

    private fun StreamingEventProcessor.getTokenClaimInterval(): Long = getPropertyValue("tokenClaimInterval") ?: -1
    private fun StreamingEventProcessor.getStoreTokenStoreType(): String =
        getPropertyTypeNested("tokenStore", TokenStore::class.java)

    private fun StreamingEventProcessor.getStoreTokenClaimTimeout(): Long = getPropertyValue<Any>("tokenStore")
        ?.getPropertyValue<TemporalAmount>("claimTimeout")?.let { it.get(ChronoUnit.SECONDS) * 1000 } ?: -1


    private fun Any.getInterceptors(vararg fieldNames: String): String {

        val interceptors = fieldNames.firstNotNullOfOrNull { this.getPropertyValue<Any>(it) } ?: return "unknown"
        if (interceptors::class.java.name == "org.axonframework.axonserver.connector.DispatchInterceptors") {
            return interceptors.getInterceptors("dispatchInterceptors")
        }
        if (interceptors !is List<*>) {
            return "none"
        }
        return interceptors
            .filterNotNull()
            .joinToString { it::class.qualifiedName ?: "unknown" }
    }

    private fun StreamingEventProcessor.contexts(): List<String> {
        val messageSource = getPropertyValue<StreamableMessageSource<*>>("messageSource") ?: return emptyList()
        val sources = if (messageSource is MultiStreamableMessageSource) {
            messageSource.getPropertyValue<List<StreamableMessageSource<*>>>("eventStreams") ?: emptyList()
        } else {
            listOf(messageSource)
        }
        return sources.mapNotNull { toContext(it) }.distinct()
    }

    private fun toContext(it: StreamableMessageSource<*>): String? {
        if (it::class.java.simpleName == "AxonServerEventStore") {
            return it.getPropertyValue<Any>("storageEngine")?.getPropertyValue("context")
        }
        if (it::class.java.simpleName == "AxonIQEventStorageEngine") {
            return it.getPropertyValue("context")
        }
        // Fallback
        return it.getPropertyValue("context")
    }


    private fun <T> Any.getPropertyValue(fieldName: String): T? {
        val field = ReflectionUtils.fieldsOf(this::class.java).firstOrNull { it.name == fieldName } ?: return null
        return ReflectionUtils.getMemberValue(
            field,
            this
        )
    }

    private fun Any.getPropertyType(fieldName: String): String {
        return ReflectionUtils.getMemberValue<Any>(
            ReflectionUtils.fieldsOf(this::class.java).first { it.name == fieldName },
            this
        ).let { it::class.java.name }
    }

    private fun Any.getPropertyTypeNested(fieldName: String, clazz: Class<out Any>): String {
        return ReflectionUtils.getMemberValue<Any>(
            ReflectionUtils.fieldsOf(this::class.java).first { it.name == fieldName },
            this
        )
            .let { it.unwrapPossiblyDecoratedClass(clazz) }
            .let { it::class.java.name }
    }

    fun <T : Any> T.unwrapPossiblyDecoratedClass(clazz: Class<out T>): T {
        return fieldOfMatchingType(clazz)
            ?.let { ReflectionUtils.getFieldValue(it, this) as T }
            ?.unwrapPossiblyDecoratedClass(clazz)
        // No field of provided type - reached end of decorator chain
            ?: this
    }

    private fun <T : Any> T.fieldOfMatchingType(clazz: Class<out T>): Field? {
        // When we reach our own AS-classes, stop unwrapping
        if (this::class.java.name.startsWith("org.axonframework") && this::class.java.simpleName.startsWith("AxonServer")) return null
        return ReflectionUtils.fieldsOf(this::class.java)
            .firstOrNull { f -> f.type.isAssignableFrom(clazz) }
    }
}