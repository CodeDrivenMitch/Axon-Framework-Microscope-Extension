package com.insidion.axon.microscope

import org.axonframework.messaging.unitofwork.CurrentUnitOfWork

const val EVENT_PROCESSOR_METRIC_PREFIX = "eventProcessor"
const val TRACE_METADATA_KEY = "msTraceId"

fun getCurrentResources() = CurrentUnitOfWork.map { it.resources() }.orElse(emptyMap())
fun getCurrentKey() = getCurrentResources().keys
    .firstOrNull { it.endsWith("/SegmentId") }
    ?: "Processor[UNKNOWN]/SegmentId"

fun getCurrentProcessorName() = getCurrentKey().substringBefore("]/SegmentId").removePrefix("Processor[")
fun getCurrentSegment() = getCurrentResources().getOrDefault(getCurrentKey(), -1) as Int
