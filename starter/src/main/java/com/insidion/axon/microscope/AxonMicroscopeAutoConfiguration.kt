package com.insidion.axon.microscope

import com.insidion.axon.microscope.InstrumentUtils.METADATA_FIELD
import com.insidion.axon.microscope.InstrumentUtils.instrument
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import org.axonframework.axonserver.connector.command.AxonServerCommandBus
import org.axonframework.axonserver.connector.query.AxonServerQueryBus
import org.axonframework.commandhandling.CommandBus
import org.axonframework.config.AggregateConfiguration
import org.axonframework.config.Configurer
import org.axonframework.config.MessageMonitorFactory
import org.axonframework.eventhandling.EventMessage
import org.axonframework.eventhandling.EventProcessor
import org.axonframework.eventhandling.EventTrackerStatus
import org.axonframework.eventhandling.StreamingEventProcessor
import org.axonframework.eventhandling.tokenstore.TokenStore
import org.axonframework.eventsourcing.EventSourcingRepository
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.messaging.Message
import org.axonframework.messaging.MessageDispatchInterceptor
import org.axonframework.messaging.deadletter.SequencedDeadLetterQueue
import org.axonframework.micrometer.*
import org.axonframework.monitoring.MessageMonitor
import org.axonframework.monitoring.MultiMessageMonitor
import org.axonframework.queryhandling.QueryBus
import org.axonframework.queryhandling.QueryUpdateEmitter
import org.axonframework.serialization.Serializer
import org.axonframework.tracing.MultiSpanFactory
import org.axonframework.tracing.SpanFactory
import org.axonframework.tracing.opentelemetry.OpenTelemetrySpanFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.*
import java.util.function.BiFunction
import java.util.function.Consumer
import java.util.function.ToDoubleFunction
import org.axonframework.config.Configuration as AxonConfiguration

@Configuration
@ComponentScan("com.insidion.axon.microscope")
class AxonMicroscopeAutoConfiguration {
    private val logger = LoggerFactory.getLogger("Microscope")
    private val interceptor = MessageDispatchInterceptor { _ -> BiFunction { _, m: Message<*> -> m.andMetaData(mapOf(METADATA_FIELD to System.currentTimeMillis())) } }

    @Bean
    fun metricFactory(meterRegistry: MeterRegistry): MicroscopeMetricFactory {
        return MicroscopeMetricFactory(meterRegistry)
    }

    @Bean
    fun microscopeSpanFactory(metricFactory: MicroscopeMetricFactory, meterRegistry: MicroscopeMetricFactory): SpanFactory {
        return MultiSpanFactory(listOf(MicroscopeSpanFactory(meterRegistry), OpenTelemetrySpanFactory.builder().build()))
    }

    @Bean
    fun queueMeasuringBeanPostProcessor(metricFactory: MicroscopeMetricFactory, spanFactory: SpanFactory) = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
            if (bean is AxonServerCommandBus) {
                instrument(bean, metricFactory, spanFactory)
            }
            if (bean is AxonServerQueryBus) {
                instrument(bean, metricFactory, spanFactory)
            }
            if (bean is Serializer && bean !is MicroscopeSerializerDecorator) {
                logger.info("Decorating {} of type {} for Microscope!", beanName, bean::class.java.simpleName)
                return MicroscopeSerializerDecorator(bean, metricFactory, spanFactory)
            }
            if (bean is TokenStore && bean !is MicroscopeTokenStoreDecorator) {
                logger.info("Decorating {} of type {} for Microscope!", beanName, bean::class.java.simpleName)
                return MicroscopeTokenStoreDecorator(bean, metricFactory)
            }
            return bean
        }
    }

    @Bean
    fun metricsConfigurerModule(metricFactory: MicroscopeMetricFactory) = object : MetricsConfigurerModule(GlobalMetricRegistry(metricFactory.meterRegistry)) {
        override fun configureModule(c: Configurer) {
            c.configureMessageMonitor(EventStore::class.java, createEventStoreMonitorFactory(metricFactory))
            c.configureMessageMonitor(StreamingEventProcessor::class.java, createEventProcessorMonitorFactory(metricFactory))
            c.configureMessageMonitor(CommandBus::class.java, createCommandBusMonitorFactory(metricFactory))
            c.configureMessageMonitor(QueryBus::class.java, createQueryBusMonitorFactory(metricFactory))
            c.configureMessageMonitor(QueryUpdateEmitter::class.java, createQueryUpdateEmitterMonitorFactory(metricFactory))
            c.onInitialize { conf: AxonConfiguration ->
                conf.onStart {
                    conf.commandBus().registerDispatchInterceptor(interceptor)
                    conf.queryUpdateEmitter().registerDispatchInterceptor(interceptor)
                    conf.queryBus().registerDispatchInterceptor(interceptor)
                    conf.findModules(AggregateConfiguration::class.java).forEach(Consumer { ac: AggregateConfiguration<*> -> instrumentRepository(metricFactory, ac) })
                    conf.eventProcessingConfiguration().eventProcessors().forEach { (s: String, eventProcessor: EventProcessor?) ->
                        if (eventProcessor is StreamingEventProcessor) {
                            instrumentProcessorSegmentClaimed(metricFactory, conf, s, eventProcessor)
                        }
                    }
                }
            }
        }
    }

    private fun instrumentRepository(metricFactory: MicroscopeMetricFactory, ac: AggregateConfiguration<*>) {
        val repository = ac.repository()
        if (repository is EventSourcingRepository<*>) {
            instrument(repository, metricFactory, ac)
        }
    }

    private fun createEventStoreMonitorFactory(meterRegistry: MicroscopeMetricFactory): MessageMonitorFactory {
        return MessageMonitorFactory { _, _, componentName: String ->
            val latencyMonitor = genericLatencyMonitor(meterRegistry, "eventStore")
            val messageCountingMonitor = genericMessageCounter(meterRegistry, componentName)
            MultiMessageMonitor(latencyMonitor, messageCountingMonitor)
        }
    }

    private fun createEventProcessorMonitorFactory(metricFactory: MicroscopeMetricFactory): MessageMonitorFactory {
        return MessageMonitorFactory { _, componentType: Class<*>, componentName: String ->
            val monitors: MutableList<MessageMonitor<in Message<*>>> = ArrayList()
            monitors.add(MessageCountingMonitor.buildMonitor(
                    EVENT_PROCESSOR_METRIC_PREFIX, metricFactory.meterRegistry
            ) { message: Message<*> ->
                Tags.of(
                        TagsUtil.PAYLOAD_TYPE_TAG, message.payloadType.simpleName,
                        TagsUtil.PROCESSOR_NAME_TAG, componentName
                )
            })
            monitors.add(MessageTimerMonitor
                    .builder()
                    .timerCustomization { timer: Timer.Builder -> timer.distributionStatisticExpiry(Duration.ofMinutes(1)) }
                    .meterRegistry(metricFactory.meterRegistry)
                    .meterNamePrefix(EVENT_PROCESSOR_METRIC_PREFIX)
                    .tagsBuilder { message: Message<*> ->
                        Tags.of(
                                TagsUtil.PAYLOAD_TYPE_TAG, message.payloadType.simpleName,
                                TagsUtil.PROCESSOR_NAME_TAG, componentName
                        )
                    }
                    .build())
            monitors.add(
                    CapacityMonitor.buildMonitor(EVENT_PROCESSOR_METRIC_PREFIX, metricFactory.meterRegistry, 1, TimeUnit.MINUTES) {
                        Tags.of(TagsUtil.PROCESSOR_NAME_TAG, componentName)
                    })
            monitors.add(genericLatencyMonitor(metricFactory, componentType.simpleName))
            monitors.add(EventProcessorLatencyMonitor
                    .builder()
                    .meterRegistry(metricFactory.meterRegistry)
                    .meterNamePrefix(EVENT_PROCESSOR_METRIC_PREFIX)
                    .tagsBuilder {
                        Tags.of(
                                TagsUtil.PROCESSOR_NAME_TAG, componentName
                        )
                    }
                    .build() as MessageMonitor<Message<*>>)
            MultiMessageMonitor(monitors)
        }
    }

    private fun createCommandBusMonitorFactory(metricFactory: MicroscopeMetricFactory): MessageMonitorFactory {
        return MessageMonitorFactory { _, componentType: Class<*>, componentName: String ->
            val messageCounter = genericMessageCounter(metricFactory, componentName)
            val messageTimer = MessageTimerMonitor
                    .builder()
                    .timerCustomization { timer: Timer.Builder ->
                        timer.distributionStatisticExpiry(
                                Duration.ofMinutes(1))
                    }
                    .meterRegistry(metricFactory.meterRegistry)
                    .meterNamePrefix(componentName)
                    .tagsBuilder { message: Message<*> ->
                        Tags.of(TagsUtil.PAYLOAD_TYPE_TAG,
                                message.payloadType.simpleName)
                    }
                    .build()
            val capacityMonitor1Minute = CapacityMonitor.buildMonitor(
                    componentName, metricFactory.meterRegistry,
                    1, TimeUnit.MINUTES
            ) { message: Message<*> -> Tags.of(TagsUtil.PAYLOAD_TYPE_TAG, message.payloadType.simpleName) }
            val latencyMonitor = genericLatencyMonitor(metricFactory, componentType.simpleName)
            MultiMessageMonitor(messageCounter, messageTimer, capacityMonitor1Minute, latencyMonitor)
        }
    }

    private fun createQueryBusMonitorFactory(metricFactory: MicroscopeMetricFactory): MessageMonitorFactory {
        return MessageMonitorFactory { _, componentType: Class<*>, componentName: String ->
            val messageCounter = genericMessageCounter(metricFactory, componentName)
            val messageTimer = MessageTimerMonitor
                    .builder()
                    .timerCustomization { timer: Timer.Builder -> timer.distributionStatisticExpiry(Duration.ofMinutes(1)) }
                    .meterRegistry(metricFactory.meterRegistry)
                    .meterNamePrefix(componentName)
                    .tagsBuilder { message: Message<*> ->
                        Tags.of(TagsUtil.PAYLOAD_TYPE_TAG,
                                message.payloadType.simpleName)
                    }
                    .build()
            val capacityMonitor1Minute = CapacityMonitor.buildMonitor(
                    componentName, metricFactory.meterRegistry
            ) { message: Message<*> -> Tags.of(TagsUtil.PAYLOAD_TYPE_TAG, message.payloadType.simpleName) }
            val latencyMonitor = genericLatencyMonitor(metricFactory, componentType.simpleName)
            MultiMessageMonitor(messageCounter, messageTimer, capacityMonitor1Minute, latencyMonitor)
        }
    }

    private fun createQueryUpdateEmitterMonitorFactory(metricFactory: MicroscopeMetricFactory): MessageMonitorFactory {
        return MessageMonitorFactory { _, componentType: Class<*>, componentName: String ->
            val messageCounter = genericMessageCounter(metricFactory, componentName)
            val messageTimer = MessageTimerMonitor
                    .builder()
                    .timerCustomization { timer: Timer.Builder -> timer.distributionStatisticExpiry(Duration.ofMinutes(1)) }
                    .meterRegistry(metricFactory.meterRegistry)
                    .meterNamePrefix(componentName)
                    .tagsBuilder { message: Message<*> ->
                        Tags.of(TagsUtil.PAYLOAD_TYPE_TAG,
                                message.payloadType.simpleName)
                    }
                    .build()
            val latencyMonitor = genericLatencyMonitor(metricFactory, componentType.simpleName)
            MultiMessageMonitor(messageCounter, messageTimer, latencyMonitor)
        }
    }

    private fun genericMessageCounter(metricFactory: MicroscopeMetricFactory, componentName: String): MessageCountingMonitor {
        return MessageCountingMonitor.buildMonitor(
                componentName, metricFactory.meterRegistry
        ) { message: Message<*> -> Tags.of(TagsUtil.PAYLOAD_TYPE_TAG, message.payloadType.simpleName) }
    }

    private fun genericLatencyMonitor(meterRegistry: MicroscopeMetricFactory, componentName: String): MicroscopeLatencyMessageMonitor {
        return MicroscopeLatencyMessageMonitor(
                componentName,
                meterRegistry,
                METADATA_FIELD
        ) { message: Message<*> -> Tags.of(TagsUtil.PAYLOAD_TYPE_TAG, message.payloadType.simpleName) }
    }

    private fun instrumentProcessorSegmentClaimed(metricFactory: MicroscopeMetricFactory, conf: AxonConfiguration, s: String, streamingEventProcessor: StreamingEventProcessor) {
        metricFactory.meterRegistry.gauge("eventProcessor.segments.claimed",
                Tags.of("eventProcessor", s),
                streamingEventProcessor
        ) { value: StreamingEventProcessor ->
            value.processingStatus().values.stream()
                    .filter { segment: EventTrackerStatus -> !segment.isErrorState }
                    .mapToDouble { segment: EventTrackerStatus -> 1.0 / (segment.segment.mask + 1) }
                    .sum()
        }
        conf.eventProcessingConfiguration().deadLetterQueue(s).ifPresent { dlq: SequencedDeadLetterQueue<EventMessage<*>?> ->
            metricFactory.meterRegistry.gauge("eventProcessor.dlq.size",
                    Tags.of("eventProcessor", s),
                    dlq, ToDoubleFunction { obj: SequencedDeadLetterQueue<EventMessage<*>?> -> obj.size().toDouble() })
        }
    }
}
