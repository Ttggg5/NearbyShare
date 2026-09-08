package com.nearbyshare.protocol

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A bidirectional protocol channel over a byte-stream pair.
 *
 * The same connection carries both framed control messages (PROTOCOL.md §3)
 * and, once an `OFFER` is accepted, raw file bytes with no framing at all
 * (PROTOCOL.md §5 step 4) -- so this exposes both. [rawIn] / [rawOut] are the
 * *same* streams the frames travel over; a caller must only touch them at a
 * point in the flow where both sides agree raw bytes are next.
 *
 * Writes are serialised by an internal lock so a `PROGRESS` or `CANCEL` emitted
 * from another thread can never interleave inside another frame.
 *
 * Pure JVM on purpose: the transport is just a pair of streams, which keeps the
 * whole message exchange testable over loopback sockets or in-memory pipes.
 */
class MessageChannel(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit = {},
) : Closeable {

    private val writeLock = ReentrantLock()

    /** The raw inbound stream, for reading file bytes. */
    val rawIn: InputStream get() = input

    /** The raw outbound stream, for writing file bytes. Guard with [withRawOutput]. */
    val rawOut: OutputStream get() = output

    /** Encode and write [message], then flush. */
    fun send(message: Message) = writeLock.withLock {
        MessageCodec.writeFrame(output, message)
        output.flush()
    }

    /**
     * Read the next control frame.
     *
     * @throws EndOfStreamException on a clean close at a frame boundary.
     */
    fun receive(): Message = MessageCodec.readFrame(input)

    /** As [receive], but `null` on a clean close instead of throwing. */
    fun receiveOrNull(): Message? = MessageCodec.readFrameOrNull(input)

    /**
     * Run [block] holding the write lock, so a run of raw file bytes cannot be
     * split by a concurrent [send].
     */
    fun <T> withRawOutput(block: (OutputStream) -> T): T = writeLock.withLock {
        val result = block(output)
        output.flush()
        result
    }

    override fun close() = onClose()
}
