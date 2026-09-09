# Testing

## Automated: `core-protocol`, `core-network`, `core-data`

```
./gradlew test
```

or per module:

```
./gradlew :core-protocol:test
./gradlew :core-network:testDebugUnitTest
./gradlew :core-data:testDebugUnitTest
```

This covers:

- **Protocol** (`core-protocol/src/test`) — message envelope encode/decode via
  `MessageCodec`, the length-prefix framing rules from PROTOCOL.md §3
  (including the 1 MiB max-payload rejection), the `UNSUPPORTED_TYPE`
  forward-compatibility behavior from §4, and the fixture contract tests that
  decode every JSON file under `core-protocol/src/test/resources/fixtures/`
  into the expected in-memory message.
- **Networking** (`core-network/src/test`) — certificate generation and
  fingerprinting, the TLS handshake, TOFU pinning (first-use, match, and
  identity-changed cases) over loopback sockets, the `TransferSession` state
  machine end to end, and discovery's TXT-record parsing/instance-name rules.

There is no dedicated test module for `app` — the ViewModels are thin
adapters over `core-network`/`core-data`, whose interesting logic (discovery,
trust, the transfer state machine) already has direct coverage above. The
`app`-specific behavior below has to be checked by hand on a device.

## Cross-implementation fixtures

`core-protocol/src/test/resources/fixtures/*.json` — one file per PROTOCOL.md
§5 message type — is meant to be **byte-identical** to the equivalent
fixtures in the Windows repository (`Core.Tests/Fixtures/*.json`). If you
change a payload's JSON shape, update both repositories' copies together and
re-run both test suites; the fixture contract tests are what would catch a
drift here.

## A note on this sandbox

`core-network`, `core-data`, and `app` are Android modules: building any of
them requires the Android Gradle Plugin and AndroidX/Compose artifacts, which
are published to Google's Maven repository (`google()`/`dl.google.com`), plus
an installed Android SDK to compile against `android.jar`. In the sandbox this
`app` module was authored in, outbound access to `dl.google.com` is blocked by
the environment's egress policy (confirmed via the proxy status endpoint —
Gradle's plugin portal and Maven Central *are* reachable, only Google's own
repository is not) and no Android SDK is installed, so `./gradlew build`
cannot get past resolving the `com.android.application`/`com.android.library`
plugins declared in the root `build.gradle.kts`, let alone compile against the
`android.jar` stubs or link Compose. This is an environment limitation, not
something introduced by any module's code — the same root `build.gradle.kts`
plugin block (present since the very first commit) hits the identical failure
for every module, `core-protocol` included, because Gradle resolves the root
project's declared plugins before running any task at all.

If you're picking this up in a normal development environment (Android
Studio, or CI with `google()` reachable and an SDK installed via
`sdkmanager`/`ANDROID_HOME`), `./gradlew build` and `./gradlew test` should
run cleanly end to end. If you hit the same blocked-repository symptom
elsewhere, that's the thing to fix first (network egress policy or missing
`ANDROID_HOME`), not the build scripts.

## Manual: the `app` UI (real devices)

The following needs two devices (or two Android devices plus a Windows
machine — see the end-to-end section) on the same LAN/Wi-Fi segment. Some
routers, guest networks, and corporate/campus Wi-Fi block multicast traffic
outright, which breaks mDNS while leaving ordinary TCP connections (and thus
the manual-IP fallback) working — see the mDNS troubleshooting note below
before assuming a transfer bug.

### 1. Discovery and identity

1. Install the app on both devices (`./gradlew :app:installDebug`, or run
   from Android Studio) and open it. On API 33+, grant the notification
   permission when prompted (declining is fine — sharing still works, there's
   just no visible progress notification).
2. On the **device list** screen, confirm the status line changes from
   "Starting discovery…" to `Visible as "<name>"` within a few seconds.
3. Within a few more seconds each device should appear in the other's peer
   list, showing its name and platform (`android`/`windows`).
4. Open **Settings**, change the device name, and tap **Save**. Confirm the
   *other* device's peer list picks up the new name without either app
   restarting (this exercises `AppContainer.refreshAdvertisement`, which
   re-publishes the mDNS TXT record without restarting the TLS listener).
5. Force-stop and reopen the app; confirm the same device name and identity
   (and thus the peer's pinned fingerprint) survive — the device certificate
   lives in the Android Keystore, not app-private storage that a reinstall
   would wipe.

### 2. Manual IP/port fallback

Useful to isolate whether a problem is in mDNS discovery or in the transfer
itself, and the only way to test if multicast is blocked on your network.

1. Find the other device's IP address (Wi-Fi settings → network details, or
   `adb shell ip addr show wlan0` over USB) and note the port from its status
   line if visible, or from the other device's own logs — in practice, just
   try discovery first and fall back to this if a peer never appears.
2. On the device list screen's "Can't see a device?" section, type the IP and
   port and tap **+**.
3. Confirm the manually-added row appears (labeled "manual"), and that
   tapping its send icon and picking a file drives the same offer/accept/
   transfer flow as a discovered peer.
4. Confirm a manual entry is superseded (not duplicated) once the same
   address is also discovered over mDNS (`mergePeers` in
   `core-network/discovery/DiscoveryService.kt`).

### 3. Sending and accepting a transfer

1. Tap a peer's send icon and pick one or more files of mixed sizes via the
   system document picker (include at least one large enough to take a few
   seconds over Wi-Fi, to actually observe progress rather than an instant
   finish).
2. On the receiving device, confirm `IncomingTransferDialog` appears — from
   *any* screen, including if the app was backgrounded and reopened — showing
   the sender's name, file name(s), and total size, with **Accept**/
   **Decline** buttons.
3. Accept. On both devices, confirm the progress screen's headline and
   progress bar update through the phases (waiting for acceptance →
   transferring, with a percentage → complete), and that the foreground
   service's notification updates in step.
4. Confirm the received file(s) land in the device's **Downloads** app/folder
   (`MediaStore.Downloads` on API 29+; the app's own external files directory
   on API 26-28) with their original names, and that no partial/`.part` file
   is left behind.
5. Repeat with **Decline** instead of **Accept**; confirm the sender's
   progress screen reports the peer declined, with nothing written to the
   receiver's Downloads.

### 4. Cancellation

1. Start an outbound send of a large file, then tap **Cancel** while bytes
   are still moving.
2. Confirm the sender reports "Cancelled" and the receiver discards the
   partial file rather than keeping a truncated one (the receiving device's
   Downloads should show no entry for it).

### 5. Foreground service behavior under Doze / battery optimization

The whole point of `TransferForegroundService` is that a transfer survives
the screen turning off or the app being backgrounded. To verify this
specifically (rather than trusting it because nothing crashed while you were
watching):

1. Start a large enough outbound transfer that it will still be running a
   minute from now, then immediately press the device's Home button (don't
   force-stop the app).
2. Turn the screen off and leave the device alone for a minute or two.
   Confirm the notification is still present and its progress has advanced
   when you check again (pull down the notification shade without unlocking,
   if possible, so you don't reset any idle timers).
3. For a stronger check via `adb` (requires a debug build and USB/Wi-Fi
   debugging):
   ```
   adb shell dumpsys battery unplug
   adb shell dumpsys deviceidle force-idle
   ```
   then wait and confirm the transfer still completes; clean up afterwards
   with:
   ```
   adb shell dumpsys deviceidle unforce
   adb shell dumpsys battery reset
   ```
4. If the device manufacturer has an aggressive background-kill policy on top
   of stock Doze (common on some OEM skins), check that the app isn't
   subject to it: Settings → Apps → NearbyShare → Battery, and allow
   unrestricted/background usage. A `FOREGROUND_SERVICE_DATA_SYNC` service
   should be exempt from most of this by design, but OEM behavior varies.
5. Confirm tapping the notification reopens the app to wherever it left off,
   and that the **Stop sharing** notification action stops discovery and
   closes the listening socket (a subsequent send/receive attempt should
   fail until the app is reopened, which restarts sharing).

### 6. Identity-changed warning (TOFU)

This simulates PROTOCOL.md §2's "device reinstalled" scenario:

1. Complete at least one transfer with a peer, so its certificate fingerprint
   gets pinned in this device's settings store
   (`SettingsKnownPeerStore`/`DataStore`).
2. On the *peer*, clear the app's storage (Settings → Apps → NearbyShare →
   Storage → Clear storage) and reopen it. Because the peer's TLS identity
   lives in the **Android Keystore**, not app-private storage, clearing
   storage alone will *not* reproduce this — the identity survives. To
   actually force a new certificate, you need a fresh install on a different
   device/emulator, or a peer running the Windows app with its certificate
   file deleted.
3. Try sending to that peer again. Confirm the sender's transfer fails with
   an identity-mismatch error rather than silently overwriting the pinned
   fingerprint or silently proceeding (`PeerIdentityChangedException` /
   `Protocol.ErrorCode.IDENTITY_MISMATCH`).

### 7. Error surfaces

- Try connecting to a manual address that isn't running the app at all
  (nothing listening on that port); confirm a clear failure rather than the
  UI hanging indefinitely (the connect timeout in `TlsSocketFactory` is 10s).
- Try sending a file mid-transfer while disabling Wi-Fi on one side; confirm
  both ends report a failure rather than hanging.

## End-to-end: testing against the Windows app

Both apps implement the same PROTOCOL.md; this is the most valuable manual
test because it's the only one that actually proves cross-implementation
compatibility rather than each app just being consistent with itself.

1. Build and run `NearbyShareWindows`'s `App` on a Windows machine (see that
   repository's own `README.md`/`TESTING.md` — it requires Visual Studio with
   the Windows App SDK workload; it cannot be built on Linux/macOS).
2. Put the Windows machine and the Android device on the same LAN/Wi-Fi
   segment. Allow the Windows app through Windows Firewall on the private
   network profile if prompted.
3. Repeat the discovery, manual-fallback, send/accept, and cancellation
   sections above with the Windows app standing in for the "other device" —
   each side should show the other with the correct name and `os` (`android`/
   `windows`), and a transfer initiated from either side should complete with
   byte-identical content on the other.
4. Specifically worth checking because it's where a subtle field-naming or
   casing bug between `kotlinx.serialization` and `System.Text.Json` would
   surface: send a file with a name containing spaces and non-ASCII
   characters, and confirm it survives round-trip with the same name (modulo
   each side's own filesystem-safe sanitization).
5. If discovery doesn't work between the two but both apps work fine against
   a same-platform peer, suspect the network before the code: some
   home routers isolate wired and wireless clients, or block multicast
   between VLANs/SSIDs (common on guest networks and some mesh systems). The
   manual IP/port fallback (section 2 above) isolates this: if a manual
   connection transfers successfully but discovery never shows the peer, the
   problem is mDNS/multicast reachability, not the app.
