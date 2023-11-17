package com.insidion.axon.microscope

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.insidion.axon.microscope.api.MicroscopeAlert
import com.insidion.axon.microscope.api.MicroscopeEvent
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.distribution.HistogramSupport
import io.micrometer.prometheus.PrometheusMeterRegistry
import org.axonframework.messaging.responsetypes.ResponseTypes
import org.axonframework.queryhandling.GenericQueryMessage
import org.axonframework.queryhandling.QueryBus
import org.axonframework.queryhandling.QueryHandler
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusScrapeEndpoint
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
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
        queryBus.subscribe<String>("$nodeName-events", String::class.java) {
            serialize(EventsResponse(nodeName, eventRecorder.getEvents()))
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
    fun getPackage(): CompleteResponse {
        val nodes = queryBus.scatterGather(
            GenericQueryMessage(
                MicroscopeNodeQuery(),
                "microscopeConnectedNodes",
                ResponseTypes.instanceOf(String::class.java)
            ),
            5000, TimeUnit.MILLISECONDS
        ).toList()

        val alerts = nodes.map {
            deserialize(
                queryBus.query(
                    GenericQueryMessage(
                        MicroscopeAlertQuery(),
                        "${it.payload}-alerts",
                        ResponseTypes.instanceOf(String::class.java)
                    )
                ).get().payload, AlertsResponse::class.java
            )
        }


        val events = nodes.map {
            deserialize(
                queryBus.query(
                    GenericQueryMessage(
                        MicroscopeEventQuery(),
                        "${it.payload}-events",
                        ResponseTypes.instanceOf(String::class.java)
                    )
                ).get().payload, EventsResponse::class.java
            )
        }
        val configurations = nodes.map {
            deserialize(
                queryBus.query(
                    GenericQueryMessage(
                        MicroscopeEventQuery(),
                        "${it.payload}-config",
                        ResponseTypes.instanceOf(String::class.java)
                    )
                ).get().payload, ConfigurationResponse::class.java
            )
        }
        val stackdumps = nodes.map {
            deserialize(
                queryBus.query(
                    GenericQueryMessage(
                        MicroscopeEventQuery(),
                        "${it.payload}-stackdump",
                        ResponseTypes.instanceOf(String::class.java)
                    )
                ).get().payload, StackDumpResponse::class.java
            )
        }
        val metrics = nodes.map {
            deserialize(
                queryBus.query(
                    GenericQueryMessage(
                        MicroscopeEventQuery(),
                        "${it.payload}-metrics",
                        ResponseTypes.instanceOf(String::class.java)
                    )
                ).get().payload, MetricsResponse::class.java
            )
        }

        return CompleteResponse(alerts, events, configurations, stackdumps, metrics)
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

data class EventsResponse(
    val nodeName: String,
    val alerts: List<MicroscopeEvent>
)

data class StackDumpResponse(
    val nodeName: String,
    val stackDump: String,
)

data class ConfigurationResponse(
    val nodeName: String,
    val configuration: Map<String, String>,
)

data class CompleteResponse(
    val alerts: List<AlertsResponse>,
    val events: List<EventsResponse>,
    val configurations: List<ConfigurationResponse>,
    val stackDumps: List<StackDumpResponse>,
    val metrics: List<MetricsResponse>,
)

data class MetricsResponse(
    val nodeName: String,
    val metrics: String
)