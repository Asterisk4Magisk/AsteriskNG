package system

const val ANDROID_USER_UID_RANGE = 100_000
const val ANDROID_PACKAGE_SCOPE_ALL = "all"
const val ANDROID_PACKAGE_SCOPE_SYSTEM = "system"
const val ANDROID_PACKAGE_SCOPE_USER = "user"
const val ANDROID_APP_ICON_SIZE_DP = 44

fun Int.toAndroidUserId(): Int = this / ANDROID_USER_UID_RANGE

fun Int.toAndroidAppId(): Int = this % ANDROID_USER_UID_RANGE

