package com.insidion.axon.microscope

import com.insidion.axon.microscope.InstrumentUtils.instrument
import com.insidion.axon.microscope.decorators.MicroscopeCommandBusDecorator
import com.insidion.axon.microscope.decorators.MicroscopeQueryBusDecorator
import com.insidion.axon.microscope.decorators.MicroscopeTokenStoreDecorator
import com.insidion.axon.microscope.monitors.MicroscopeMetricsConfigurerModule
import io.grpc.*
import io.micrometer.core.instrument.MeterRegistry
import org.axonframework.axonserver.connector.command.AxonServerCommandBus
import org.axonframework.axonserver.connector.query.AxonServerQueryBus
import org.axonframework.commandhandling.CommandBus
import org.axonframework.eventhandling.tokenstore.TokenStore
import org.axonframework.queryhandling.QueryBus
import org.axonframework.tracing.SpanFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Configuration
@ComponentScan("com.insidion.axon.microscope")
@ConditionalOnProperty(name = ["axon.microscope.enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AlertConfigurationProperties::class)
class AxonMicroscopeAutoConfiguration {
    private val logger = LoggerFactory.getLogger("Microscope")


    @Bean
    fun metricFactory(meterRegistry: MeterRegistry): MicroscopeMetricFactory = MicroscopeMetricFactory(meterRegistry)

    @Bean
    fun queueMeasuringBeanPostProcessor(
        metricFactory: MicroscopeMetricFactory,
        eventRecorder: MicroscopeEventRecorder,
        configurationRegistry: MicroscopeConfigurationRegistry,
        spanFactory: SpanFactory
    ) = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
            if (bean is AxonServerCommandBus) {
                instrument(bean, metricFactory, eventRecorder, spanFactory)
            }
            if (bean is AxonServerQueryBus) {
                instrument(bean, metricFactory, eventRecorder, spanFactory)
            }
            if (bean is TokenStore && bean !is MicroscopeTokenStoreDecorator) {
                logger.info("Decorating {} of type {} for Microscope!", beanName, bean::class.java.simpleName)
                return MicroscopeTokenStoreDecorator(bean, eventRecorder)
            }
            if (bean is CommandBus && bean !is MicroscopeCommandBusDecorator) {
                logger.info("Decorating {} of type {} for Microscope!", beanName, bean::class.java.simpleName)
                return MicroscopeCommandBusDecorator(bean, eventRecorder, metricFactory.meterRegistry, configurationRegistry)
            }
            if (bean is QueryBus && bean !is MicroscopeQueryBusDecorator) {
                logger.info("Decorating {} of type {} for Microscope!", beanName, bean::class.java.simpleName)
                return MicroscopeQueryBusDecorator(bean, eventRecorder, metricFactory.meterRegistry, configurationRegistry)
            }
            return bean
        }
    }

    @Bean
    @Primary
    fun microscopeMetricsConfigurerModule(
        alertRecorder: MicroscopeAlertRecorder,
        metricFactory: MicroscopeMetricFactory,
        eventRecorder: MicroscopeEventRecorder,
        alertConfigurationProperties: AlertConfigurationProperties,
    ) = MicroscopeMetricsConfigurerModule(alertRecorder, metricFactory, eventRecorder, alertConfigurationProperties)
}
