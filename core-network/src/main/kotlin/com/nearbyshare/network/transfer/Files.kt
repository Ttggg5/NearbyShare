package com.nearbyshare.network.transfer

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * One file this device is offering to send.
 *
 * An interface, not a `File` or a `Uri`, so the whole send path is independent
 * of Android's storage APIs -- the production implementation reads through a
 * `ContentResolver`, tests read from a byte array, and neither leaks into
 * [TransferSession].
 */
interface FileSource {

    /** File name as it should appear on the receiver (no path separators). */
    val name: String

    /** Exact byte count. The receiver relies on this to know where the file ends. */
    val size: Long

    /** MIME type, or `null` if unknown. */
    val mime: String?

    /**
     * Optional precomputed SHA-256, lowercase hex (PROTOCOL.md §5).
     *
     * `null` means the offer omits it and the receiver skips verification --
     * computing it would mean reading every byte twice before the transfer even
     * starts, which is a poor trade on a phone for a multi-gigabyte file.
     */
    val sha256: String? get() = null

    /** Open a fresh stream positioned at the first byte. */
    fun openStream(): InputStream
}

/** A file being written on the receiving side. */
interface IncomingFile : Closeable {

    /** Where the bytes go. */
    val outputStream: OutputStream

    /**
     * Make the file visible and final.
     *
     * @return a user-facing description of where it landed (e.g. a path or a
     *   content URI), for the completion notification.
     */
    fun commit(): String

    /**
     * Discard the partial file.
     *
     * Called on cancellation, checksum mismatch, or I/O failure -- PROTOCOL.md
     * §5 step 6 requires partial output to be discarded, so a half-written file
     * must never be left visible in Downloads.
     */
    fun abort()
}

/**
 * Creates files on the receiving side.
 *
 * The production implementation writes into `MediaStore.Downloads`; tests write
 * into a temp directory.
 */
interface FileSink {

    /**
     * Begin writing a file.
     *
     * @param declaredSize the size from the `OFFER`, so an implementation can
     *   pre-allocate or fail fast on insufficient space.
     * @throws java.io.IOException if the file cannot be created (no space, bad
     *   name, permission denied).
     */
    fun create(name: String, declaredSize: Long, mime: String?): IncomingFile
}

/**
 * Strip anything that could let a peer write outside the download folder.
 *
 * A remote peer controls `OFFER.files[].name`, so it is untrusted input:
 * `../../secret` or an absolute path must not be honoured. Only the final path
 * segment survives, with separators and NUL removed.
 */
fun sanitizeIncomingFileName(raw: String, fallback: String = "received_file"): String {
    val lastSegment = raw
        .replace('\\', '/')
        .substringAfterLast('/')
        .replace("\u0000", "")
        .trim()

    val cleaned = lastSegment
        .filterNot { it.isISOControl() }
        .trim('.', ' ')

    if (cleaned.isEmpty()) return fallback
    // Keep well inside common filesystem limits, preserving the extension.
    if (cleaned.length <= MAX_FILE_NAME_LENGTH) return cleaned

    val extension = cleaned.substringAfterLast('.', missingDelimiterValue = "")
    return if (extension.isEmpty() || extension.length > 16) {
        cleaned.take(MAX_FILE_NAME_LENGTH)
    } else {
        cleaned.take(MAX_FILE_NAME_LENGTH - extension.length - 1) + "." + extension
    }
}

private const val MAX_FILE_NAME_LENGTH = 200
