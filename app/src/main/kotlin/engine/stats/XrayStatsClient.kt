// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.stats

import com.xray.app.stats.command.QueryStatsRequest
import com.xray.app.stats.command.StatsServiceGrpc
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import java.io.Closeable
import java.util.concurrent.TimeUnit

internal class XrayStatsClient(
    listenAddress: String,
    port: Int,
    apiTag: String,
) : Closeable {
    private val channel: ManagedChannel = OkHttpChannelBuilder
        .forAddress(listenAddress, port)
        .usePlaintext()
        .build()
    private val stub = StatsServiceGrpc.newBlockingStub(channel)
    private val excludedInboundTags = xrayTrafficExcludedInboundTags(apiTag)

    fun queryInboundTraffic(reset: Boolean = true): XrayTrafficBytes {
        val response = stub
            .withDeadlineAfter(QueryDeadlineSeconds, TimeUnit.SECONDS)
            .queryStats(
                buildXrayInboundTrafficStatsRequest(reset),
            )
        val stats = response.statList.mapNotNull { stat ->
            parseXrayInboundTrafficStat(
                name = stat.name,
                bytes = stat.value,
            )
        }
        return aggregateInboundTraffic(
            stats = stats,
            excludedInboundTags = excludedInboundTags,
        )
    }

    override fun close() {
        channel.shutdownNow()
    }
}

internal fun buildXrayInboundTrafficStatsRequest(reset: Boolean): QueryStatsRequest {
    return QueryStatsRequest.newBuilder()
        .setPattern(XrayInboundTrafficStatsPattern)
        .setReset(reset)
        .build()
}

private const val XrayInboundTrafficStatsPattern = "inbound>>>"
private const val QueryDeadlineSeconds = 2L
