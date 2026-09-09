package com.nearbyshare.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID

/** The app's single preferences DataStore. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "nearbyshare_settings")

/**
 * Jetpack DataStore implementation of [DeviceSettingsRepository].
 *
 * @param dataStore the backing store; injected so tests can supply their own.
 * @param defaultDeviceName produces the name used before the user picks one.
 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val defaultDeviceName: () -> String = ::platformDeviceName,
) : DeviceSettingsRepository {

    override val deviceName: Flow<String> =
        observe(DeviceSettingsKeys.DeviceName)
            .map { it.ifBlank { defaultDeviceName() } }
            .distinctUntilChanged()

    override suspend fun deviceName(): String =
        get(DeviceSettingsKeys.DeviceName).ifBlank { defaultDeviceName() }

    override suspend fun setDeviceName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            remove(DeviceSettingsKeys.DeviceName)
        } else {
            set(DeviceSettingsKeys.DeviceName, trimmed)
        }
    }

    override suspend fun deviceId(): String =
        getOrPut(DeviceSettingsKeys.DeviceId) { UUID.randomUUID().toString() }

    // ------------------------------------------------------- SettingsRepository --

    override fun <T> observe(key: SettingsKey<T>): Flow<T> =
        dataStore.data
            // A corrupt or unreadable file must not take the whole app down;
            // fall back to defaults and let the next write repair it.
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .map { preferences -> preferences[key.preferencesKey()] ?: key.defaultValue }
            .distinctUntilChanged()

    override suspend fun <T> get(key: SettingsKey<T>): T = observe(key).first()

    override suspend fun <T> set(key: SettingsKey<T>, value: T) {
        dataStore.edit { preferences -> preferences[key.preferencesKey()] = value }
    }

    override suspend fun <T> remove(key: SettingsKey<T>) {
        dataStore.edit { preferences -> preferences.remove(key.preferencesKey()) }
    }

    override suspend fun <T> getOrPut(key: SettingsKey<T>, compute: () -> T): T {
        val preferencesKey = key.preferencesKey()
        // edit {} runs under DataStore's single-writer lock, so concurrent
        // callers see each other's write rather than both generating a value.
        val updated = dataStore.edit { preferences ->
            if (preferences[preferencesKey] == null) {
                preferences[preferencesKey] = compute()
            }
        }
        return updated[preferencesKey] ?: key.defaultValue
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> SettingsKey<T>.preferencesKey(): Preferences.Key<T> = when (this) {
        is SettingsKey.StringKey -> stringPreferencesKey(name)
        is SettingsKey.BooleanKey -> booleanPreferencesKey(name)
        is SettingsKey.IntKey -> intPreferencesKey(name)
        is SettingsKey.LongKey -> longPreferencesKey(name)
    } as Preferences.Key<T>

    companion object {
        /** Build the repository backed by the app-wide DataStore file. */
        fun create(context: Context): DataStoreSettingsRepository =
            DataStoreSettingsRepository(context.applicationContext.settingsDataStore)
    }
}

/** A sensible first-run device name, e.g. "Pixel 8". */
internal fun platformDeviceName(): String {
    val manufacturer = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    return when {
        model.isEmpty() -> manufacturer.ifEmpty { "Android device" }
        manufacturer.isEmpty() -> model
        model.startsWith(manufacturer, ignoreCase = true) -> model
        else -> "$manufacturer $model"
    }
}
