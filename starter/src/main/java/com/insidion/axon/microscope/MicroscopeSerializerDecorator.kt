package com.insidion.axon.microscope

import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import org.axonframework.serialization.SerializedObject
import org.axonframework.serialization.Serializer
import org.axonframework.tracing.SpanFactory
import java.time.Duration
import java.util.function.Supplier

class MicroscopeSerializerDecorator(
        private val delegate: Serializer,
        private val metricFactory: MicroscopeMetricFactory,
        private val spanFactory: SpanFactory,
) : Serializer by delegate {
    private val serializeBaseName = "${delegate.javaClass.simpleName}.serialize"
    private val deserializeBaseName = "${delegate.javaClass.simpleName}.deserialize"
    override fun <T : Any?> serialize(obj: Any?, expectedRepresentation: Class<T>): SerializedObject<T> {

        return spanFactory.createInternalSpan { "$serializeBaseName(${obj?.javaClass?.simpleName} -> ${expectedRepresentation.simpleName})" }
                .runSupplier {
                    val result = metricFactory.createTimer(serializeBaseName,
                            Tags.of(Tag.of("origin", obj?.javaClass?.simpleName ?: "null"),
                                    Tag.of("target", expectedRepresentation.simpleName)))
                            .record(Supplier { delegate.serialize(obj, expectedRepresentation) })!!
                    val data = result.data
                    if (data is ByteArray) {
                        metricFactory.createTimer("${delegate.javaClass.simpleName}.messageSize",
                                Tags.of(Tag.of("target", expectedRepresentation.simpleName)))
                                .record(Duration.ofSeconds(data.size.toLong()))
                    }

                    result
                }
    }

    override fun <S : Any?, T : Any?> deserialize(serializedObject: SerializedObject<S>): T {
        return spanFactory.createInternalSpan { "$deserializeBaseName(${serializedObject.type.name})" }
                .runSupplier {
                    metricFactory.createTimer(deserializeBaseName,
                            Tags.of(Tag.of("type", serializedObject.type.name)))
                            .record(Supplier {
                                delegate.deserialize(serializedObject)
                            })!!
                }
    }
}
