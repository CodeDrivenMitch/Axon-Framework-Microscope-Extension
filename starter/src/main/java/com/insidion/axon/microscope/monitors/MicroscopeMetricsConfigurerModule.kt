package com.insidion.axon.microscope.monitors

import com.insidion.axon.microscope.*
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import org.axonframework.commandhandling.CommandBus
import org.axonframework.config.AggregateConfiguration
import org.axonframework.config.Configuration
import org.axonframework.config.Configurer
import org.axonframework.config.MessageMonitorFactory
import org.axonframework.eventhandling.EventMessage
import org.axonframework.eventhandling.EventProcessor
import org.axonframework.eventhandling.EventTrackerStatus
import org.axonframework.eventhandling.StreamingEventProcessor
import org.axonframework.eventsourcing.EventSourcingRepository
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.messaging.InterceptorChain
import org.axonframework.messaging.Message
import org.axonframework.messaging.MessageDispatchInterceptor
import org.axonframework.messaging.MessageHandlerInterceptor
import org.axonframework.messaging.deadletter.SequencedDeadLetterQueue
import org.axonframework.messaging.unitofwork.UnitOfWork
import org.axonframework.micrometer.*
import org.axonframework.monitoring.MessageMonitor
import org.axonframework.monitoring.MultiMessageMonitor
import org.axonframework.queryhandling.QueryBus
import org.axonframework.queryhandling.QueryUpdateEmitter
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction
import java.util.function.Consumer
import java.util.function.ToDoubleFunction

class MicroscopeMetricsConfigurerModule(
    private val alertRecorder: MicroscopeAlertRecorder,
    private val metricFactory: MicroscopeMetricFactory,
    private val eventRecorder: MicroscopeEventRecorder,
    private val alertConfigurationProperties: AlertConfigurationProperties,
) : MetricsConfigurerModule(GlobalMetricRegistry(metricFactory.meterRegistry)) {
    private val interceptor = MessageDispatchInterceptor { _ ->
        BiFunction { _, m: Message<*> ->
            m.andMetaData(
                mapOf(
                    TIME_METADATA_KEY to System.currentTimeMillis(),
                    TRACE_METADATA_KEY to eventRecorder.currentIdentifier(),
                )
            )
        }
    }

    private val handlerInterceptor = object : MessageHandlerInterceptor<Message<*>> {
        override fun handle(p0: UnitOfWork<out Message<*>>, p1: InterceptorChain): Any? {
            val recording = eventRecorder.recordEvent("handle", msg = p0.message)
            try {
                return p1.proceed()
            } finally {
                recording.end()
            }
        }

    }

    override fun configureModule(c: Configurer) {
        c.configureMessageMonitor(EventStore::class.java, createEventStoreMonitorFactory())
        c.configureMessageMonitor(
            StreamingEventProcessor::class.java,
            createEventProcessorMonitorFactory()
        )
        c.configureMessageMonitor(CommandBus::class.java, createCommandBusMonitorFactory())
        c.configureMessageMonitor(QueryBus::class.java, createQueryBusMonitorFactory())
        c.configureMessageMonitor(QueryUpdateEmitter::class.java, createQueryUpdateEmitterMonitorFactory())
        c.onInitialize { conf: Configuration ->
            conf.onStart {
                conf.commandBus().registerDispatchInterceptor(interceptor)
                conf.commandBus().registerHandlerInterceptor(handlerInterceptor)
                conf.queryUpdateEmitter().registerDispatchInterceptor(interceptor)
                conf.queryBus().registerDispatchInterceptor(interceptor)
                conf.queryBus().registerHandlerInterceptor(handlerInterceptor)
                conf.eventStore().registerDispatchInterceptor(interceptor)
                conf.findModules(AggregateConfiguration::class.java)
                    .forEach(Consumer { ac: AggregateConfiguration<*> -> instrumentRepository(ac) })
                conf.eventProcessingConfiguration().eventProcessors()
                    .forEach { (s: String, eventProcessor: EventProcessor?) ->
                        if (eventProcessor is StreamingEventProcessor) {
                            instrumentProcessorSegmentClaimed(conf, s, eventProcessor)
                        }
                        eventProcessor.registerHandlerInterceptor(handlerInterceptor)
                        eventProcessor.registerHandlerInterceptor(
                            MicroscopeProcessorEventHandlerInterceptor(
                                eventRecorder
                            )
                        )
                    }
            }
        }
    }

    private fun instrumentRepository(ac: AggregateConfiguration<*>) {
        val repository = ac.repository()
        if (repository is EventSourcingRepository<*>) {
            InstrumentUtils.instrument(repository, metricFactory, ac)
        }
    }

    private fun createEventStoreMonitorFactory(): MessageMonitorFactory {
        return MessageMonitorFactory { _, _, componentName: String ->
            val latencyMonitor = genericLatencyMonitor("eventStore", componentName)
            val messageCountingMonitor = genericMessageCounter(componentName)
            MultiMessageMonitor(latencyMonitor, messageCountingMonitor)
        }
    }

    private fun createEventProcessorMonitorFactory(): MessageMonitorFactory {
        return MessageMonitorFactory { _, componentType: Class<*>, componentName: String ->
            val monitors: MutableList<MessageMonitor<in Message<*>>> = ArrayList()
            monitors.add(
                MessageCountingMonitor.buildMonitor(
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
                CapacityMonitor.buildMonitor(
                    EVENT_PROCESSOR_METRIC_PREFIX,
                    metricFactory.meterRegistry,
                    1,
                    TimeUnit.MINUTES
                ) {
                    Tags.of(TagsUtil.PROCESSOR_NAME_TAG, componentName)
                })
            monitors.add(genericLatencyMonitor(componentType.simpleName, componentName))
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

    private fun createCommandBusMonitorFactory(): MessageMonitorFactory {
        return MessageMonitorFactory { _, componentType: Class<*>, componentName: String ->
            val messageCounter = genericMessageCounter(componentName)
            val messageTimer = MessageTimerMonitor
                .builder()
                .timerCustomization { timer: Timer.Builder ->
                    timer.distributionStatisticExpiry(
                        Duration.ofMinutes(1)
                    )
                }
                .meterRegistry(metricFactory.meterRegistry)
                .meterNamePrefix(componentName)
                .tagsBuilder { message: Message<*> ->
                    Tags.of(
                        TagsUtil.PAYLOAD_TYPE_TAG,
                        message.payloadType.simpleName
                    )
                }
                .build()
            val capacityMonitor1Minute = CapacityMonitor.buildMonitor(
                componentName,
                metricFactory.meterRegistry,
                1,
                TimeUnit.MINUTES
            ) { message: Message<*> -> Tags.empty() }
            val latencyMonitor = genericLatencyMonitor(componentType.simpleName, componentName)
            MultiMessageMonitor(messageCounter, messageTimer, capacityMonitor1Minute, latencyMonitor)
        }
    }

    private fun createQueryBusMonitorFactory(): MessageMonitorFactory {
        return MessageMonitorFactory { _, componentType: Class<*>, componentName: String ->
            val messageCounter = genericMessageCounter(componentName)
            val messageTimer = MessageTimerMonitor
                .builder()
                .timerCustomization { timer: Timer.Builder -> timer.distributionStatisticExpiry(Duration.ofMinutes(1)) }
                .meterRegistry(metricFactory.meterRegistry)
                .meterNamePrefix(componentName)
                .tagsBuilder { message: Message<*> ->
                    Tags.of(
                        TagsUtil.PAYLOAD_TYPE_TAG,
                        message.payloadType.simpleName
                    )
                }
                .build()
            val capacityMonitor1Minute = CapacityMonitor.buildMonitor(
                componentName, metricFactory.meterRegistry
            ) { message: Message<*> -> Tags.empty() }
            val latencyMonitor = genericLatencyMonitor(componentType.simpleName, componentName)
            MultiMessageMonitor(messageCounter, messageTimer, capacityMonitor1Minute, latencyMonitor)
        }
    }

    private fun createQueryUpdateEmitterMonitorFactory(): MessageMonitorFactory {
        return MessageMonitorFactory { _, componentType: Class<*>, componentName: String ->
            val messageCounter = genericMessageCounter(componentName)
            val messageTimer = MessageTimerMonitor
                .builder()
                .timerCustomization { timer: Timer.Builder -> timer.distributionStatisticExpiry(Duration.ofMinutes(1)) }
                .meterRegistry(metricFactory.meterRegistry)
                .meterNamePrefix(componentName)
                .tagsBuilder { message: Message<*> ->
                    Tags.of(
                        TagsUtil.PAYLOAD_TYPE_TAG,
                        message.payloadType.simpleName
                    )
                }
                .build()
            val latencyMonitor = genericLatencyMonitor(componentType.simpleName, componentName)
            MultiMessageMonitor(messageCounter, messageTimer, latencyMonitor)
        }
    }

    private fun genericMessageCounter(
        componentName: String
    ): MessageCountingMonitor {
        return MessageCountingMonitor.buildMonitor(
            componentName, metricFactory.meterRegistry
        ) { message: Message<*> -> Tags.of(TagsUtil.PAYLOAD_TYPE_TAG, message.payloadType.simpleName) }
    }

    private fun genericLatencyMonitor(
        componentType: String,
        componentName: String
    ): MicroscopeLatencyMessageMonitor {
        return MicroscopeLatencyMessageMonitor(
            alertRecorder,
            alertConfigurationProperties,
            componentType,
            metricFactory,
        ) { message: Message<*> -> Tags.of("componentName", componentName) }
    }

    private fun instrumentProcessorSegmentClaimed(
        conf: Configuration,
        s: String,
        streamingEventProcessor: StreamingEventProcessor
    ) {
        metricFactory.meterRegistry.gauge(
            "eventProcessor.segments.claimed",
            Tags.of("eventProcessor", s),
            streamingEventProcessor
        ) { value: StreamingEventProcessor ->
            value.processingStatus().values.stream()
                .filter { segment: EventTrackerStatus -> !segment.isErrorState }
                .mapToDouble { segment: EventTrackerStatus -> 1.0 / (segment.segment.mask + 1) }
                .sum()
        }
        conf.eventProcessingConfiguration().deadLetterQueue(s)
            .ifPresent { dlq: SequencedDeadLetterQueue<EventMessage<*>?> ->
                metricFactory.meterRegistry.gauge("eventProcessor.dlq.size",
                    Tags.of("eventProcessor", s),
                    dlq, ToDoubleFunction { obj: SequencedDeadLetterQueue<EventMessage<*>?> -> obj.size().toDouble() })
            }
    }
}