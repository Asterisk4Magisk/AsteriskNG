// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app.navigation

import data.PersistedProxyServer
import data.decodeProxyServer
import data.toPersistedProxyServer
import features.proxy.server.model.ProxyServer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

internal object ProxyServerRouteSerializer : KSerializer<ProxyServer<*>> {
    private val delegate = PersistedProxyServer.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: ProxyServer<*>) {
        encoder.encodeSerializableValue(delegate, value.toPersistedProxyServer())
    }

    override fun deserialize(decoder: Decoder): ProxyServer<*> {
        return decoder.decodeSerializableValue(delegate).decodeProxyServer()
    }
}
