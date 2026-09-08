package com.nearbyshare.protocol

/**
 * The MVP message types from PROTOCOL.md §5.
 *
 * Note that `type` is deliberately an *open* string set on the wire (see the
 * forward-compatibility rule in PROTOCOL.md §4): anything not in this enum
 * decodes to [Message.Unknown] rather than failing.
 */
enum class MessageType(val wire: String) {
    HELLO("HELLO"),
    OFFER("OFFER"),
    ACCEPT("ACCEPT"),
    REJECT("REJECT"),
    PROGRESS("PROGRESS"),
    DONE("DONE"),
    ERROR("ERROR"),
    CANCEL("CANCEL");

    override fun toString(): String = wire

    companion object {
        private val byWire: Map<String, MessageType> = entries.associateBy { it.wire }

        /** @return the known type for [wire], or `null` if this build does not recognise it. */
        fun fromWireOrNull(wire: String): MessageType? = byWire[wire]
    }
}
