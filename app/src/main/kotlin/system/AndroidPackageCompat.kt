// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package system

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

internal fun PackageManager.getApplicationInfoCompat(packageName: String): ApplicationInfo {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
    } else {
        getApplicationInfoDeprecated(packageName)
    }
}

internal fun PackageManager.getInstalledApplicationsCompat(): List<ApplicationInfo> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        getInstalledApplicationsDeprecated()
    }
}

@Suppress("DEPRECATION")
private fun PackageManager.getApplicationInfoDeprecated(packageName: String): ApplicationInfo {
    return getApplicationInfo(packageName, 0)
}

@Suppress("DEPRECATION")
private fun PackageManager.getInstalledApplicationsDeprecated(): List<ApplicationInfo> {
    return getInstalledApplications(0)
}
