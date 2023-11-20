package com.insidion.axon.microscope

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.insidion.axon.microscope.api.MicroscopeAlert
import com.insidion.axon.microscope.api.MicroscopeEvent
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.distribution.HistogramSupport
import io.micrometer.prometheus.PrometheusMeterRegistry
import org.axonframework.messaging.GenericMessage
import org.axonframework.messaging.responsetypes.PublisherResponseType
import org.axonframework.messaging.responsetypes.ResponseTypes
import org.axonframework.queryhandling.*
import org.reactivestreams.Publisher
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusScrapeEndpoint
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct
import kotlin.streams.toList


@RestController
@RequestMapping("/microscope")
class WebInterfaceController(
    private val queryBus: QueryBus,
    private val alertRecorder: MicroscopeAlertRecorder,
    private val eventRecorder: MicroscopeEventRecorder,
    private val configurationRegistry: MicroscopeConfigurationRegistry,
    private val meterRegistry: PrometheusMeterRegistry,
) {
    private val nodeName = ManagementFactory.getRuntimeMXBean().name
    private val objectMapper =
        ObjectMapper().findAndRegisterModules().setSerializationInclusion(JsonInclude.Include.NON_NULL)

    @QueryHandler(queryName = "microscopeConnectedNodes")
    fun getAlerts(query: MicroscopeNodeQuery) = nodeName

    @PostConstruct
    fun setup() {
        queryBus.subscribe<String>("$nodeName-alerts", String::class.java) {
            serialize(AlertsResponse(nodeName, alertRecorder.getAlerts()))
        }
        queryBus.subscribe<Publisher<MicroscopeEvent>>("$nodeName-events", MicroscopeEvent::class.java) {
            Flux.fromIterable(eventRecorder.getEvents())
        }
        queryBus.subscribe<String>("$nodeName-config", String::class.java) {
            serialize(ConfigurationResponse(nodeName, configurationRegistry.getConfig()))
        }
        queryBus.subscribe<String>("$nodeName-stackdump", String::class.java) {
            serialize(StackDumpResponse(nodeName, threadDump()))
        }
        queryBus.subscribe<String>("$nodeName-metrics", String::class.java) {
            serialize(MetricsResponse(nodeName, meterRegistry.scrape()))
        }
    }

    private fun serialize(payload: Any): String {
        return objectMapper.writeValueAsString(payload)
    }

    private fun <T> deserialize(payload: String, clazz: Class<T>): T {
        return objectMapper.readValue(payload, clazz)
    }

    @RequestMapping("/package")
    fun getPackage(): Flux<NodeResponse> {
        return Flux.fromIterable(queryBus.scatterGather(
            GenericQueryMessage(
                MicroscopeNodeQuery(),
                "microscopeConnectedNodes",
                ResponseTypes.instanceOf(String::class.java)
            ),
            5000, TimeUnit.MILLISECONDS
        ).toList()).flatMap {
            val nodeName = it.payload
            Flux.from(queryBus.streamingQuery(
                GenericStreamingQueryMessage(
                    GenericMessage.asMessage(MicroscopeEventQuery()),
                    "${it.payload}-events",
                    MicroscopeEvent::class.java
                )
            ))
                .map { m -> m.payload}
                .collectList()
                .map { events -> nodeName to events}
        }.map {
            val alerts = deserialize(
                queryBus.query(
                    GenericQueryMessage(
                        MicroscopeAlertQuery(),
                        "${it.first}-alerts",
                        ResponseTypes.instanceOf(String::class.java)
                    )
                ).get().payload, AlertsResponse::class.java
            )
            val config = deserialize(
                queryBus.query(
                    GenericQueryMessage(
                        MicroscopeEventQuery(),
                        "${it.first}-config",
                        ResponseTypes.instanceOf(String::class.java)
                    )
                ).get().payload, ConfigurationResponse::class.java
            )

            val stackdump =  deserialize(
                queryBus.query(
                    GenericQueryMessage(
                        MicroscopeEventQuery(),
                        "${it.first}-stackdump",
                        ResponseTypes.instanceOf(String::class.java)
                    )
                ).get().payload, StackDumpResponse::class.java
            )

            val metrics = deserialize(
                queryBus.query(
                    GenericQueryMessage(
                        MicroscopeEventQuery(),
                        "${it.first}-metrics",
                        ResponseTypes.instanceOf(String::class.java)
                    )
                ).get().payload, MetricsResponse::class.java
            )
            NodeResponse(nodeName, alerts, it.second, config.configuration, stackdump.stackDump, metrics.metrics)
        }
    }

    private fun threadDump(): String {
        val threadDump = StringBuffer(System.lineSeparator())
        val threadMXBean: ThreadMXBean = ManagementFactory.getThreadMXBean()
        for (threadInfo in threadMXBean.dumpAllThreads(true, true)) {
            threadDump.append(threadInfo.toString())
        }
        return threadDump.toString()
    }
}


class MicroscopeNodeQuery {}
class MicroscopeAlertQuery {}
class MicroscopeEventQuery {}

data class AlertsResponse(
    val nodeName: String,
    val alerts: List<MicroscopeAlert>
)

data class StackDumpResponse(
    val nodeName: String,
    val stackDump: String,
)

data class ConfigurationResponse(
    val nodeName: String,
    val configuration: Map<String, String>,
)

data class NodeResponse(
    val nodeName: String,
    val alerts: AlertsResponse,
    val events: List<MicroscopeEvent>,
    val configurations: Map<String, String>,
    val stackDumps: String,
    val metrics: String,
)

data class MetricsResponse(
    val nodeName: String,
    val metrics: String
)