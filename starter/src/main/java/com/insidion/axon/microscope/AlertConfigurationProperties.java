package com.insidion.axon.microscope;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "axon.microscope.thresholds")
public class AlertConfigurationProperties {
    public Long ingestLatency = 5000L;
    public Long commitLatency = 5000L;

}
