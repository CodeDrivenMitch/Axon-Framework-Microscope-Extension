package com.insidion.axon.microscope

import io.grpc.*
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import org.axonframework.axonserver.connector.ManagedChannelCustomizer
import org.axonframework.tracing.SpanFactory
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.time.Duration

@Configuration
@ComponentScan("com.insidion.axon.microscope")
@ConditionalOnProperty(name = ["axon.microscope.enabled"], havingValue = "true", matchIfMissing = true)
class MicroscopeGrpcConfiguration {
    private val logger = LoggerFactory.getLogger("Microscope")

    @Bean
    @Primary
    fun channelCustomizer2(
        metricFactory: MicroscopeMetricFactory,
        eventRecorder: MicroscopeEventRecorder,
        spanFactory: SpanFactory
    ): ManagedChannelCustomizer {
        return ManagedChannelCustomizer { t ->
            t.intercept(object : ClientInterceptor {

                override fun <ReqT : Any?, RespT : Any?> interceptCall(
                    method: MethodDescriptor<ReqT, RespT>,
                    callOptions: CallOptions?,
                    next: Channel
                ): ClientCall<ReqT, RespT> {
                    val name = method.bareMethodName ?: method.fullMethodName
                    val recording = if (!name.contains("OpenStream")) eventRecorder.recordEvent("grpc.$name") else null
                    return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                        next.newCall(
                            method,
                            callOptions
                        )
                    ) {
                        override fun start(responseListener: Listener<RespT>, headers: Metadata?) {
                            val startTime = System.currentTimeMillis()
                            super.start(object : SimpleForwardingClientCallListener<RespT>(responseListener) {
                                override fun onClose(status: Status?, trailers: Metadata?) {
                                    recording?.end()
                                    metricFactory.createTimer(
                                        "grpc_duration",
                                        Tags.of(Tag.of("method", name), Tag.of("type", method.type.name))
                                    ).record(Duration.ofMillis(System.currentTimeMillis() - startTime))
                                    super.onClose(status, trailers)
                                }
                            }, headers)
                        }
                    }
                }
            })
        }
    }
}
