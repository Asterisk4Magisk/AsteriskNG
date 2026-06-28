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
) : Closeable {
    private val channel: ManagedChannel = OkHttpChannelBuilder
        .forAddress(listenAddress, port)
        .usePlaintext()
        .build()
    private val stub = StatsServiceGrpc.newBlockingStub(channel)

    fun queryOutboundTraffic(reset: Boolean = true): XrayTrafficBytes {
        val response = stub
            .withDeadlineAfter(QueryDeadlineSeconds, TimeUnit.SECONDS)
            .queryStats(
                QueryStatsRequest.newBuilder()
                    .setPattern(XrayOutboundTrafficStatsPattern)
                    .setReset(reset)
                    .build(),
            )
        val stats = response.statList.mapNotNull { stat ->
            parseXrayTrafficStat(
                name = stat.name,
                bytes = stat.value,
            )
        }
        return aggregateProxyTraffic(stats)
    }

    override fun close() {
        channel.shutdownNow()
    }
}

private const val XrayOutboundTrafficStatsPattern = "outbound>>>"
private const val QueryDeadlineSeconds = 2L
