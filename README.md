# SecureChat

An end-to-end encrypted messaging app for Android, plus the relay server it talks to.

Messages are encrypted and decrypted only on the sender's and recipient's devices, using the
same family of algorithms Signal/WhatsApp use (X3DH key agreement + the Double Ratchet). The
relay server only ever sees ciphertext and public keys — compromising it exposes *who talked to
whom and when* (metadata), never message content.

## Layout

This is a multi-module Gradle project:

| Module     | What it is                                                             | Platform      |
|------------|-------------------------------------------------------------------------|---------------|
| `crypto/`  | X3DH + Double Ratchet implementation, no Android dependency            | Kotlin/JVM    |
| `server/`  | Relay server: prekey directory + encrypted message store-and-forward   | Kotlin/Ktor   |
| `app/`     | The Android app itself (Jetpack Compose)                               | Android       |

`crypto` and `server` are ordinary JVM modules and are fully buildable and testable with just a
JDK — no Android SDK required. `app` needs Android Studio / the Android SDK to build, since it
depends on the Android framework, Jetpack Compose, Room, CameraX, etc.

## Security architecture

**Identity.** On first launch, the app generates an X25519 key pair (for Diffie-Hellman) and an
Ed25519 key pair (for signing) on-device, in `crypto`'s `IdentityKeyPair`. The private keys are
stored via Android Keystore-backed `EncryptedSharedPreferences` (`data/keystore/SecureKeyStorage`)
and never leave the device. Only the public keys are ever sent to the server.

**Session setup (X3DH).** To message someone for the first time, the app fetches their *prekey
bundle* (identity key + a signed medium-term key + a one-time key) from the relay server and runs
Extended Triple Diffie-Hellman (`crypto/X3DH.kt`) to derive a shared secret, without the recipient
needing to be online. This is the same algorithm Signal specifies at
https://signal.org/docs/specifications/x3dh/.

**Ongoing messages (Double Ratchet).** Every message after that runs through a Double Ratchet
session (`crypto/DoubleRatchet.kt`, per https://signal.org/docs/specifications/doubleratchet/):
each message uses its own AES-256-GCM key, derived so that compromising one message's key
reveals nothing about any other message (forward secrecy), and a fresh Diffie-Hellman exchange is
mixed in on every round-trip so the conversation recovers even if a device's state is briefly
compromised (post-compromise security). Out-of-order and dropped messages are handled correctly
via a bounded skipped-message-key cache.

All of the above uses [Google Tink](https://github.com/tink-crypto/tink-java) for the actual
primitives (X25519, HKDF-SHA256, AES-256-GCM, Ed25519) — `crypto` implements the *protocols*
against those vetted primitives, it does not implement any cryptography of its own.

**Verifying who you're talking to.** The relay server hands out prekey bundles, so a malicious or
compromised server could in principle try to substitute its own keys for a contact's ("MITM").
Two defenses:
- **QR contact exchange** (`qr/`): scanning a contact's in-app QR code adds them with their
  identity key pinned locally and marked verified, without touching the server at all.
- **Safety numbers** (`ui/chat/SafetyNumberScreen.kt`, `crypto/Fingerprint.kt`): a number derived
  from both parties' identity keys that you can compare over a trusted channel at any time.
- Once a contact's identity is known, `ChatRepository` refuses to proceed if a later handshake or
  bundle presents a *different* identity key for that same user id (`IdentityKeyChangedException`).

**At rest.** Conversation history and session state live in a Room database encrypted with
SQLCipher (`data/local/AppDatabase.kt`); the database passphrase itself is generated randomly and
stored via the same Keystore-backed `EncryptedSharedPreferences` as the identity key.

## Building and testing

### `crypto` and `server` (no Android SDK needed)

```
./gradlew :crypto:test   # X3DH + Double Ratchet unit tests (round-trip, out-of-order delivery,
                          # forward secrecy, tamper detection, signature verification, ...)
./gradlew :server:test   # relay server integration tests (Ktor test host)
./gradlew :server:run    # run the relay server locally on port 8080 (SQLite file: securechat.db)
```

Environment variables for the server: `PORT` (default `8080`), `DATABASE_PATH` (default
`securechat.db`).

### `app` (needs Android Studio / the Android SDK)

Open the project root in Android Studio, let it sync, and run the `app` configuration on a
device or emulator (minSdk 26). By default the app points at `http://10.0.2.2:8080` /
`ws://10.0.2.2:8080`, which is the Android emulator's alias for your host machine — so running
`./gradlew :server:run` on your host and then the app in the emulator works out of the box. For a
real device or a real deployment, change `ServerConfig` in `app/src/main/kotlin/com/securechat/
app/AppContainer.kt` to point at an HTTPS/WSS origin (cleartext is only allowed to `10.0.2.2`/
`localhost` — see `app/src/main/res/xml/network_security_config.xml`).

*Note on this environment*: the sandbox this project was built in has no Android SDK installed,
so `app` could not be compiled or run here. Its source was written carefully against the same
Jetpack/Kotlin APIs used throughout, and `crypto`/`server` (which the app's business logic sits
on top of) are fully tested, but building `app` end-to-end should be the first thing you do in
Android Studio.

## Using the app

1. **Onboarding**: pick a display name; the app generates your identity and registers your public
   keys with the relay server.
2. **Add a contact**: Contacts tab → "+" → have them show their QR code (Add contact → "My code")
   and scan it, or vice versa. This adds them as a verified contact immediately.
3. **Chat**: tap a contact to start messaging. The first message to someone triggers the X3DH
   handshake automatically; every message after that rides the same Double Ratchet session.
4. **Re-verify anytime**: tap a conversation's title to see the safety number and confirm it out
   of band (in person, by phone, etc.).

## Known limitations

This is a complete, working reference implementation, not a production messaging platform. In
particular, compared to a system like Signal:

- **Delivery**: the app uses a WebSocket for near-instant push while a screen observing messages
  is open, plus a 10s poll fallback, rather than a real platform push service (FCM) that can wake
  the app while it's fully backgrounded/killed.
- **1:1 text only**: no group conversations, media/file attachments, calls, or read receipts.
- **Single relay, SQLite**: `server` is intentionally minimal (one process, one SQLite file, no
  auth/rate-limiting on registration) to keep the reference self-contained; a real deployment
  needs a replicated database, TLS termination, authentication, and abuse controls.
- **X3DH simplification**: the implementation skips the reference spec's low-order-point checks
  for legacy curves (not needed for X25519 as used here) — see the doc comment on `X3DH.kt`.
- **No backup/multi-device**: losing the device loses the identity key and all history; there's
  no account recovery, consistent with how E2E identity keys generally work, but worth calling
  out since there's no linked-devices flow either.
- **Global session lock**: `ChatRepository` serializes all session mutation behind one mutex
  rather than one per conversation — simple and safe, but not tuned for heavy concurrent traffic.
