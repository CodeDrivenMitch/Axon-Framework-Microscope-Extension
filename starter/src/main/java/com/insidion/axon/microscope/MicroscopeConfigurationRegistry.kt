package com.insidion.axon.microscope

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class MicroscopeConfigurationRegistry {
    private val configurations = mutableMapOf<String, String>()
    private val dynamicConfigurations = mutableMapOf<String, () -> String>()

    fun getConfig(): Map<String, String> {
        return configurations + dynamicConfigurations.mapValues { it.value() }
    }

    fun registerConfigurationValue(key: String, block: () -> String) {
        dynamicConfigurations[key] = block
    }
}