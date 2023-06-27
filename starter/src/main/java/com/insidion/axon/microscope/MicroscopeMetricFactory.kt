package com.insidion.axon.microscope

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.time.temporal.ChronoUnit

class MicroscopeMetricFactory(
        val meterRegistry: MeterRegistry,
        private val metricExpiryInMinutes: Long = 1,
        private val distributionPercentiles: DoubleArray = doubleArrayOf(0.5, 0.75, 0.95, 0.98, 0.99),
) {

    fun createTimer(name: String, tags: Tags): Timer {
        return Timer.builder(name)
                .distributionStatisticExpiry(Duration.of(metricExpiryInMinutes, ChronoUnit.MINUTES))
                .publishPercentiles(*distributionPercentiles)
                .tags(tags)
                .register(meterRegistry)
    }
}
