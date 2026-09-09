package com.nearbyshare.data

import kotlinx.coroutines.flow.Flow

/**
 * A typed settings key.
 *
 * Keys are values, not enum constants, so a feature module can declare its own
 * without touching this file -- the storage layer only needs to know the
 * primitive type, which the sealed subclasses encode.
 */
sealed class SettingsKey<T>(
    /** Stable storage name. Changing it silently resets the setting. */
    val name: String,
    /** Value returned when nothing has been stored yet. */
    val defaultValue: T,
) {
    class StringKey(name: String, defaultValue: String = "") : SettingsKey<String>(name, defaultValue)
    class BooleanKey(name: String, defaultValue: Boolean = false) : SettingsKey<Boolean>(name, defaultValue)
    class IntKey(name: String, defaultValue: Int = 0) : SettingsKey<Int>(name, defaultValue)
    class LongKey(name: String, defaultValue: Long = 0L) : SettingsKey<Long>(name, defaultValue)

    override fun toString(): String = "SettingsKey($name)"
}

/**
 * A general key/value settings store.
 *
 * Deliberately not shaped around the one setting the MVP has: new preferences
 * (auto-accept from known peers, download folder, discovery visibility) are
 * added by declaring a new [SettingsKey], with no change to this interface or
 * its implementation.
 */
interface SettingsRepository {

    /** Emits the current value of [key], then again on every change. */
    fun <T> observe(key: SettingsKey<T>): Flow<T>

    /** The current value of [key], or its default if nothing is stored. */
    suspend fun <T> get(key: SettingsKey<T>): T

    /** Store [value] under [key]. */
    suspend fun <T> set(key: SettingsKey<T>, value: T)

    /** Forget [key], so it reads back as its default. */
    suspend fun <T> remove(key: SettingsKey<T>)

    /**
     * Read [key], or atomically store and return `compute()` if it is unset.
     *
     * Atomicity matters for identity values such as the device UUID: two
     * concurrent callers must not each generate one and disagree.
     */
    suspend fun <T> getOrPut(key: SettingsKey<T>, compute: () -> T): T
}
