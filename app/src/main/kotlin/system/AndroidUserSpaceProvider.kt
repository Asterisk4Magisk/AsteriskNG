// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package system

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import system.user.AndroidUserSpace
import system.user.AndroidUserSpaceRepository

class AndroidUserSpaceProvider(
    context: Context,
    private val rootAccess: AndroidRootShellGateway,
) {
    private val appContext = context.applicationContext
    private val userSpaceRepository = AndroidUserSpaceRepository(appContext)

    suspend fun getCurrentAndroidUser(): AndroidUser = withContext(Dispatchers.Default) {
        appContext.currentAndroidUser()
    }

    suspend fun listAndroidUsers(): List<AndroidUser> {
        val result = rootAccess.exec("cmd user list")
        if (result.errno != 0) {
            error(result.stderr.ifBlank { "Unable to read Android users" })
        }
        return parseAndroidUsers(result.stdout)
    }

    suspend fun currentAndroidUserSpace(): AndroidUserSpace {
        return userSpaceRepository.currentAndroidUserSpace()
    }

    suspend fun listAndroidUserSpaces(): List<AndroidUserSpace> {
        return userSpaceRepository.listAndroidUserSpaces()
    }
}
