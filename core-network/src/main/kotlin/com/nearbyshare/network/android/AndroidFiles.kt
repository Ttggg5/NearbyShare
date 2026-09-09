package com.nearbyshare.network.android

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.nearbyshare.network.transfer.FileSink
import com.nearbyshare.network.transfer.FileSource
import com.nearbyshare.network.transfer.IncomingFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A file picked through the Storage Access Framework.
 *
 * The name and size are read once up front (the `OFFER` needs them before any
 * bytes move), while [openStream] reopens the URI on demand so a retry does not
 * need the picker again.
 */
class ContentUriFileSource(
    private val context: Context,
    private val uri: Uri,
    override val name: String,
    override val size: Long,
    override val mime: String?,
) : FileSource {

    override fun openStream(): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open $name for reading")

    companion object {

        /**
         * Build sources for URIs returned by `OpenMultipleDocuments`.
         *
         * URIs whose metadata cannot be read are skipped rather than failing
         * the whole selection: one revoked permission should not cost the user
         * the other nine files they picked.
         */
        fun forUris(context: Context, uris: List<Uri>): List<FileSource> =
            uris.mapNotNull { uri -> runCatching { forUri(context, uri) }.getOrNull() }

        fun forUri(context: Context, uri: Uri): FileSource {
            val resolver = context.contentResolver
            var name: String? = null
            var size: Long = -1

            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                }

            val resolvedName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            // The receiver relies on an exact size to know where the file ends,
            // so a provider that will not report one cannot be offered.
            if (size < 0) {
                size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
            }
            if (size < 0) throw IOException("Could not determine the size of $resolvedName")

            val mime = resolver.getType(uri) ?: guessMime(resolvedName)
            return ContentUriFileSource(context.applicationContext, uri, resolvedName, size, mime)
        }

        private fun guessMime(name: String): String? {
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension.isEmpty()) return null
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }
    }
}

/**
 * Writes received files into the shared Downloads collection.
 *
 * On API 29+ this goes through `MediaStore.Downloads` with `IS_PENDING`, so no
 * storage permission is needed and a partially written file is invisible to
 * other apps until [IncomingFile.commit].
 *
 * On API 26-28 `MediaStore.Downloads` does not exist and writing to the public
 * Downloads folder would require `WRITE_EXTERNAL_STORAGE`. Rather than ask for
 * a broad, scary permission to support two old releases, files land in the
 * app's own external `Download` directory, which needs no permission at all.
 */
class MediaStoreDownloadsFileSink(context: Context) : FileSink {

    private val appContext = context.applicationContext

    override fun create(name: String, declaredSize: Long, mime: String?): IncomingFile =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createViaMediaStore(name, mime)
        } else {
            createInAppExternalDownloads(name)
        }

    private fun createViaMediaStore(name: String, mime: String?): IncomingFile {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            mime?.takeIf { it.isNotBlank() }?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            // Hides the row until commit(), so a cancelled transfer never
            // leaves a truncated file in the user's Downloads.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        // MediaStore itself de-duplicates a colliding display name by appending
        // " (1)", so no manual uniquifying is needed.
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Could not create $name in Downloads")

        val stream = try {
            resolver.openOutputStream(uri) ?: throw IOException("Could not open $name for writing")
        } catch (e: IOException) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        return object : IncomingFile {
            override val outputStream: OutputStream = stream

            override fun commit(): String {
                stream.flush()
                stream.close()
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                return "${Environment.DIRECTORY_DOWNLOADS}/$name"
            }

            override fun abort() {
                runCatching { stream.close() }
                runCatching { resolver.delete(uri, null, null) }
            }

            override fun close() {
                runCatching { stream.close() }
            }
        }
    }

    private fun createInAppExternalDownloads(name: String): IncomingFile {
        val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: appContext.filesDir
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create ${directory.absolutePath}")
        }

        val target = uniqueFile(directory, name)
        // Write to a sibling temp file so a failed transfer leaves nothing
        // behind that looks like a finished download.
        val partial = File(directory, "${target.name}.part")
        val stream = FileOutputStream(partial)

        return object : IncomingFile {
            override val outputStream: OutputStream = stream

            override fun commit(): String {
                stream.flush()
                stream.close()
                if (!partial.renameTo(target)) {
                    throw IOException("Could not finalise ${target.name}")
                }
                return target.absolutePath
            }

            override fun abort() {
                runCatching { stream.close() }
                runCatching { partial.delete() }
            }

            override fun close() {
                runCatching { stream.close() }
            }
        }
    }

    private fun uniqueFile(directory: File, name: String): File {
        val candidate = File(directory, name)
        if (!candidate.exists()) return candidate

        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var counter = 1
        while (counter < 1000) {
            val suffixed = if (extension.isEmpty()) "$base ($counter)" else "$base ($counter).$extension"
            val next = File(directory, suffixed)
            if (!next.exists()) return next
            counter++
        }
        throw IOException("Too many files named $name")
    }
}
