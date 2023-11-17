package com.insidion.axon.microscope.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MicroscopeAlert(
    @JsonProperty("c")
    val component: String,
    @JsonProperty("d")
    val description: String,
    @JsonProperty("t")
    val timestamp: Long,
    @JsonProperty("p")
    val parentTrace: String? = null,
)