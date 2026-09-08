package com.nearbyshare.protocol

/**
 * Constants shared by both implementations of the NearbyShare wire protocol.
 *
 * See PROTOCOL.md -- these values are normative and must stay in sync with the
 * Windows (C#) implementation.
 */
object Protocol {

    /** Envelope-level protocol version (`v`). Bumped only for breaking changes. */
    const val VERSION: Int = 1

    /** DNS-SD service type advertised and browsed for. See PROTOCOL.md §1. */
    const val SERVICE_TYPE: String = "_nearbyshare._tcp"

    /** Fully qualified form, as it appears on the wire. */
    const val SERVICE_TYPE_FQDN: String = "_nearbyshare._tcp.local."

    /** Number of bytes in the big-endian length prefix of a control frame. */
    const val LENGTH_PREFIX_BYTES: Int = 4

    /** Maximum control-message payload size: 1 MiB. See PROTOCOL.md §3. */
    const val MAX_PAYLOAD_BYTES: Int = 1 * 1024 * 1024

    /** DNS-SD TXT record keys. See PROTOCOL.md §1. */
    object Txt {
        const val VERSION = "v"
        const val DEVICE_ID = "id"
        const val FINGERPRINT = "fp"
        const val PORT = "port"
        const val OS = "os"

        const val OS_ANDROID = "android"
        const val OS_WINDOWS = "windows"

        /** DNS-SD limits a single TXT key/value entry to 255 bytes. */
        const val MAX_ENTRY_BYTES = 255
    }

    /** Maximum length in bytes of a DNS-SD service instance name. */
    const val MAX_INSTANCE_NAME_BYTES: Int = 63

    /**
     * `ERROR` payload codes. `UNSUPPORTED_TYPE` and `UNSUPPORTED_VERSION` are
     * mandated by PROTOCOL.md §4/§6; the rest are shared conventions for the
     * failure modes listed in PROTOCOL.md §5 step 7.
     */
    object ErrorCode {
        const val UNSUPPORTED_TYPE = "UNSUPPORTED_TYPE"
        const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
        const val MALFORMED_MESSAGE = "MALFORMED_MESSAGE"
        const val FRAME_TOO_LARGE = "FRAME_TOO_LARGE"
        const val IDENTITY_MISMATCH = "IDENTITY_MISMATCH"
        const val PROTOCOL_VIOLATION = "PROTOCOL_VIOLATION"
        const val IO_ERROR = "IO_ERROR"
        const val DISK_FULL = "DISK_FULL"
        const val CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH"
        const val CANCELLED = "CANCELLED"
        const val REJECTED = "REJECTED"
        const val INTERNAL_ERROR = "INTERNAL_ERROR"
    }
}
