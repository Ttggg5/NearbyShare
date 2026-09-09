# NearbyShare (Android)

A LAN file-sharing app for Android that talks the same wire protocol as its
Windows counterpart (`NearbyShareWindows`). Two devices on the same local
network discover each other over mDNS, negotiate a TLS connection with
trust-on-first-use certificate pinning, and transfer files directly — no
internet, cloud relay, or account required.

The wire protocol — discovery, handshake, trust model, and message set — is
specified in [`PROTOCOL.md`](PROTOCOL.md). It is the source of truth shared
byte-for-byte with the Windows repository; read it before changing anything
under `core-protocol`, `core-network/discovery`, or `core-network/tls`.

## Project layout

| Module           | What it is                                                                                          |
|------------------|------------------------------------------------------------------------------------------------------|
| `core-protocol`  | Pure Kotlin/JVM: the message envelope, typed payloads, and length-prefixed framing codec (PROTOCOL.md §3-4). No Android dependency — it's the executable spec. |
| `core-network`   | Android library: TLS/TOFU certificate pinning (`AndroidKeystoreCertificateManager`, `TofuTrustManager`, `TlsSocketFactory`), mDNS discovery (`NsdDiscoveryService`), and the transfer state machine (`TransferClient`/`TransferServer`/`TransferSession`). |
| `core-data`      | Android library: `DataStore`-backed settings (`DataStoreSettingsRepository`) and the transfer-history seam (`TransferHistoryRepository`, intentionally a no-op in the MVP — see below). |
| `app`            | The Jetpack Compose application. |

```
NearbyShareAndroid/
├── PROTOCOL.md                    # wire protocol spec (source of truth)
├── core-protocol/
│   └── src/main/kotlin/com/nearbyshare/protocol/   # Message, MessageCodec, Payloads
├── core-network/
│   └── src/main/kotlin/com/nearbyshare/network/
│       ├── identity/               # CertificateProvider, fingerprints
│       ├── tls/                    # TofuTrustManager, TlsSocketFactory, PeerTrustPolicy
│       ├── discovery/               # DiscoveryService, NsdDiscoveryService, ManualPeerStore
│       ├── transfer/                 # TransferClient/Server/Session, FileSource/FileSink
│       └── android/                   # AndroidKeystoreCertificateManager, AndroidFiles (MediaStore)
├── core-data/
│   └── src/main/kotlin/com/nearbyshare/data/         # SettingsRepository, TransferHistoryRepository
└── app/
    └── src/main/kotlin/com/nearbyshare/app/
        ├── AppContainer.kt          # hand-rolled service locator (no DI framework)
        ├── IncomingTransferGate.kt  # bridges TransferServer's accept thread to the UI
        ├── MainActivity.kt
        ├── NearbyShareApplication.kt
        ├── service/TransferForegroundService.kt
        ├── ui/                      # DeviceListScreen, SendProgressScreen, ReceiveProgressScreen,
        │                            # IncomingTransferDialog, SettingsScreen, NearbyShareApp (nav)
        └── viewmodel/               # DeviceListViewModel, SendViewModel, IncomingTransferViewModel,
                                      # ReceiveViewModel, SettingsViewModel, ViewModelFactory
```

## The app's UI flow

`MainActivity` hosts a single Compose tree; navigation is a plain sealed-class
state switch in `NearbyShareApp` rather than Navigation-Compose — with four
destinations and one of them really an overlay, a `when` is less machinery to
get right than a nav graph:

- **Device list** (`DeviceListScreen`) — the discovered-peer list (live via
  mDNS), a button that opens `ActivityResultContracts.OpenMultipleDocuments`
  and sends an `OFFER` to the tapped peer, and a **manual IP/port fallback**
  for connecting straight to an address, bypassing discovery entirely.
  PROTOCOL.md recommends this as a debug/testing escape hatch, useful for
  proving an end-to-end transfer works before mDNS is confirmed working
  across two particular devices/networks.
- **Send progress** (`SendProgressScreen`) — progress for the outbound
  transfer just started, backed by `SendViewModel`.
- **Receive progress** (`ReceiveProgressScreen`) — progress for whatever
  `TransferServer` is currently receiving, backed by `ReceiveViewModel`.
- **Settings** (`SettingsScreen`) — the device name advertised to peers.

An inbound `OFFER` shows `IncomingTransferDialog` with the offered file
names/total size and Accept/Decline buttons, as an overlay on top of whatever
screen is open — the TLS accept loop and mDNS advertising run for the app's
whole lifetime via `TransferForegroundService`, not just while a particular
screen is visible.

### Dependency injection

There's no Hilt/Dagger/annotation processor. `AppContainer` (held by
`NearbyShareApplication`) is a hand-rolled service locator: every
`core-network`/`core-data` singleton is a `by lazy` property, and
`ViewModelFactory` hands the container to each ViewModel's constructor. This
was a deliberate simplification to keep the module compiling without adding a
KSP/kapt dependency to the build.

### The foreground service

`TransferForegroundService` does **not** itself run the TLS accept loop or own
a `TransferSession` — those live in `AppContainer`'s `TransferServer`/
`TransferClient` singletons, which are already independent of any Android
`Service`. The service's job is narrower: call `AppContainer.startSharing()`
once per process, keep the process classified as foreground (so Doze/App
Standby don't kill it) for as long as either direction might be active, and
render whichever of `TransferServer.lastState` or `AppContainer.outboundSession`
changed most recently as a notification.

## Building and running

```
./gradlew build
./gradlew test
```

- `core-protocol` is pure Kotlin/JVM and needs nothing beyond the JDK.
- `core-network`, `core-data`, and `app` are Android modules and need the
  Android Gradle Plugin plus the Android SDK/AndroidX/Compose artifacts from
  Google's Maven repository (`google()`) — see the note in
  [`TESTING.md`](TESTING.md) about environments (including the sandbox this
  module was authored in) where that repository isn't reachable.

To install and run on a device or emulator:

```
./gradlew :app:installDebug
```

`minSdk` is 26 (Android 8.0); `compileSdk`/`targetSdk` are 35.

### Permissions

Declared in `app/src/main/AndroidManifest.xml`:

| Permission | Why |
|---|---|
| `INTERNET` | The TLS transfer connection is plain sockets, which Android treats as network access requiring this permission even on the LAN. |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | mDNS is multicast traffic; Wi-Fi hardware filters it unless a multicast lock is held while discovering (PROTOCOL.md §1). |
| `POST_NOTIFICATIONS` | The foreground service's progress notification, requested at runtime on API 33+ (a denial doesn't stop sharing from working — it just means no visible notification). |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Lets `TransferForegroundService` keep running (and the process alive) while the app is backgrounded. |

See [`TESTING.md`](TESTING.md) for how to exercise both the automated test
suite and the parts of the app that can only be checked by hand on real
devices, including end-to-end testing against the Windows app.
