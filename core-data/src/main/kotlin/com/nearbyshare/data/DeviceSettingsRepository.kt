package com.nearbyshare.data

import kotlinx.coroutines.flow.Flow

/** Settings keys owned by the device-identity feature. */
object DeviceSettingsKeys {

    /** The name advertised as the mDNS service instance name (PROTOCOL.md §1). */
    val DeviceName = SettingsKey.StringKey("device_name", defaultValue = "")

    /** Stable device UUID advertised in the `id` TXT record (PROTOCOL.md §1). */
    val DeviceId = SettingsKey.StringKey("device_id", defaultValue = "")
}

/**
 * The device's own identity as advertised over mDNS.
 *
 * This is a thin, intention-revealing facade over [SettingsRepository]; it
 * extends rather than wraps it so callers that need an arbitrary setting can
 * still reach one without a second injection point.
 */
interface DeviceSettingsRepository : SettingsRepository {

    /** The user-visible device name, emitting again whenever it changes. */
    val deviceName: Flow<String>

    /**
     * The current device name, falling back to a platform-derived default the
     * first time the app runs.
     */
    suspend fun deviceName(): String

    /** Set the advertised device name. Blank input restores the default. */
    suspend fun setDeviceName(name: String)

    /**
     * The stable device UUID (PROTOCOL.md §1 `id`), generated on first call and
     * persisted from then on.
     */
    suspend fun deviceId(): String
}
