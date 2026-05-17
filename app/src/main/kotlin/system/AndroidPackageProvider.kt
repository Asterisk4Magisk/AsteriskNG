// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package system

import android.content.Context

class AndroidPackageProvider(
    context: Context,
    rootAccess: AndroidRootShellGateway,
    userSpaces: AndroidUserSpaceProvider,
) {
    private val packageRepository = AndroidPackageRepository(
        context = context.applicationContext,
        exec = { command -> rootAccess.exec(command) },
        listAndroidUsers = { userSpaces.listAndroidUsers() },
    )

    suspend fun listPackages(type: String): List<String> {
        return packageRepository.listPackages(type)
    }

    suspend fun getPackagesInfo(packages: List<String>): List<InstalledPackageInfo> {
        return packageRepository.getPackagesInfo(packages)
    }

    suspend fun getCurrentUserPackagesInfo(type: String): List<InstalledPackageInfo> {
        return packageRepository.getCurrentUserPackagesInfo(type)
    }
}
