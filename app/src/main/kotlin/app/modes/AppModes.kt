// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app.modes

const val RunModeVpnService = 0
const val RunModeTproxy = 1
const val RunModeTun2Socks = 2
const val RunModeBpf2Socks = 3

fun Int.isRootRunMode(): Boolean {
    return supportsRootEbpfMatcher() || this == RunModeBpf2Socks
}

fun Int.supportsRootEbpfMatcher(): Boolean {
    return this == RunModeTproxy || this == RunModeTun2Socks
}

const val ProxyAppListModeBlacklist = 0
const val ProxyAppListModeWhitelist = 1
const val ProxyAppListModeGlobal = 2

const val ProxyServerListLayoutSingle = 1
const val ProxyServerListLayoutDouble = 2
const val ProxyServerListLayoutMultiple = 3

const val ProxyServerListSortDefault = 0
const val ProxyServerListSortName = 1
const val ProxyServerListSortLatency = 2
