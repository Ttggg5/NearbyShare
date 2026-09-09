package com.nearbyshare.network.discovery

import com.nearbyshare.network.identity.CertificateFingerprints
import com.nearbyshare.protocol.Protocol

/** How we learned about a peer. */
enum class PeerSource {
    /** Seen in an mDNS advertisement. */
    DISCOVERED,

    /** Typed in by hand on the device list -- the debug/testing escape hatch. */
    MANUAL,
}

/**
 * A peer we can send to.
 *
 * For a [PeerSource.MANUAL] peer only [host] and [port] are known: there is no
 * TXT record, so [deviceId] is a locally-minted placeholder and [fingerprint]
 * is `null`, which means the first connection pins whatever certificate the
 * peer presents rather than cross-checking it against an advertisement.
 */
data class PeerDevice(
    /** The peer's stable UUID (`id` TXT record), or a local placeholder. */
    val deviceId: String,
    /** Display name -- the mDNS service instance name. */
    val name: String,
    /** Resolved IP address literal. */
    val host: String,
    /** TCP port its TLS server listens on (`port` TXT record). */
    val port: Int,
    /** SHA-256 certificate fingerprint (`fp` TXT record), lowercase hex. */
    val fingerprint: String? = null,
    /** Platform identifier (`os` TXT record): `android`, `windows`, or unknown. */
    val os: String? = null,
    /** Protocol version the peer advertised (`v` TXT record). */
    val protocolVersion: Int = Protocol.VERSION,
    val source: PeerSource = PeerSource.DISCOVERED,
    /** Elapsed-realtime-ish timestamp of the last advertisement, for staleness. */
    val lastSeenAtMillis: Long = 0L,
) {
    /** False when the peer speaks a version this build cannot talk to. */
    val isCompatible: Boolean get() = protocolVersion == Protocol.VERSION

    /** Short fingerprint for the UI, e.g. `a1b2c3d4…9f0e`. */
    val shortFingerprint: String? get() = fingerprint?.let(CertificateFingerprints::abbreviate)
}

/**
 * What this device publishes about itself over mDNS (PROTOCOL.md §1).
 */
data class ServiceAdvertisement(
    /** DNS-SD service instance name; also the peer-visible device name. */
    val instanceName: String,
    val deviceId: String,
    val fingerprint: String,
    val port: Int,
    val os: String = Protocol.Txt.OS_ANDROID,
    val protocolVersion: Int = Protocol.VERSION,
) {
    /** The TXT record key/value pairs to publish. */
    fun txtRecords(): Map<String, String> = mapOf(
        Protocol.Txt.VERSION to protocolVersion.toString(),
        Protocol.Txt.DEVICE_ID to deviceId,
        Protocol.Txt.FINGERPRINT to fingerprint,
        Protocol.Txt.PORT to port.toString(),
        Protocol.Txt.OS to os,
    )
}
