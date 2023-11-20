package com.insidion.axon.microscope

import org.axonframework.eventhandling.EventMessage
import org.axonframework.messaging.Message
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork

const val EVENT_PROCESSOR_METRIC_PREFIX = "eventProcessor"
const val TIME_METADATA_KEY = "msTime"
const val TRACE_METADATA_KEY = "msTraceId"

fun getCurrentResources() = CurrentUnitOfWork.map { it.resources() }.orElse(emptyMap())
fun getCurrentKey() = getCurrentResources().keys
    .firstOrNull { it.endsWith("/SegmentId") }
    ?: "Processor[UNKNOWN]/SegmentId"

fun getCurrentProcessorName() = getCurrentKey().substringBefore("]/SegmentId").removePrefix("Processor[")
fun getCurrentSegment() = getCurrentResources().getOrDefault(getCurrentKey(), -1) as Int
fun getTimestampFromMessage(message: Message<*>): Long? {

    return if (message.metaData.containsKey(TIME_METADATA_KEY)) {
        message.metaData[TIME_METADATA_KEY] as Long
    } else if (message is EventMessage<*>) {
        return message.timestamp.toEpochMilli()
    } else null
}