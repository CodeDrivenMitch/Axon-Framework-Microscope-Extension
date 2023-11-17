package com.insidion.axon.microscope

import org.slf4j.LoggerFactory

class MicroscopeConfigurationRegistry {
    private val logger = LoggerFactory.getLogger("MicroscopeConfigurationRegistry")
    private val configurations = mutableMapOf<String, String>()

    fun getConfig() = configurations

    fun registerConfigurationValue(key: String, value: String) {
        logger.info("Found configuration value $key=$value")
        configurations[key] = value
    }
}