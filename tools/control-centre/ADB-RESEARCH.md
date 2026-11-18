# Rust ADB dependency assessment

## Preferred candidate for a Windows-only Control Centre

**Candidate: `libadb` 0.9.x with its built-in `keys` and `host-keys` features disabled.**

The library provides direct TCP ADB transport, packet framing, connection
handshake, shell v1/v2, file sync and a caller-supplied `Authenticator`
trait. The custom authenticator is important: it lets the application avoid
the library's optional `rsa 0.9` key implementation entirely.

The proposed Windows authentication design is:

1. Use Windows CNG to create and retain a dedicated 2048-bit RSA host key.
2. Use CNG for PKCS#1 v1.5 / SHA-1 token signing, as required by legacy ADB.
3. Export only the public modulus and exponent to construct Android's
   mincrypt `adbkey.pub` payload.
4. Implement `libadb::auth::Authenticator` in Rust and keep the private key
   in the Windows-protected user profile. Do not reuse or overwrite another
   application's ADB key.
5. Connect directly to the TV's TCP ADB endpoint; no ADB server or
   `adb.exe` is required.

This keeps protocol handling and all application orchestration in Rust while
delegating the legacy RSA primitive to Windows' maintained cryptographic
provider. It also avoids carrying the affected RustCrypto `rsa 0.9` signing
implementation into the executable.

## Compatibility caveat

The `libadb` project currently documents Android 9+ as its supported device
range. Fire OS 6 / Android 7.1.2 / API 25 is below that stated range. Its
wire protocol implementation negotiates older ADB protocol versions and
includes shell v1, but that is not evidence that its complete connection
handshake works with the AFTMM firmware.

Therefore this is a **candidate, not an approved dependency**. Before any
device-changing operation is enabled, test the actual AFTMM for:

- TCP connection to legacy ADB port 5555;
- initial CNXN/AUTH handshake and RSA authorisation prompt;
- reconnect using the saved key after first authorisation;
- shell v1 fallback and getprop inventory;
- bounded read/write behaviour when the TV reboots or drops Wi-Fi.

If the library cannot interoperate with API 25, use its protocol code only
after review or implement the required legacy protocol path in Rust. Do not
fall back to a bundled Google ADB executable or a shell runtime.

## Rejected for now: rsadb 0.1.1

`rsadb` is an attractive pure-Rust direct TCP/USB ADB client with shell v2,
file sync and an in-process device emulator. However, its manifest currently
depends on `rsa 0.9`, and its own `deny.toml` suppresses
RUSTSEC-2023-0071 with a single-signature-per-connection rationale. That
rationale is a documented risk acceptance by the upstream maintainer, not a
security fix. The Control Centre will not silently inherit that suppression.

## Other candidates

- `adb_client` has a larger history and direct TCP/USB support, but its
  current manifest also depends on `rsa 0.9`; it is not a clean solution to
  the signing advisory.
- `radb-core` advertises direct TCP, shell v2 and sync, but its documented
  dependency graph also includes `rsa 0.9`.
- `libadb` is pre-1.0 and its public support range excludes API 25, so its
  API stability and backward compatibility must be verified before adoption.

## Security and release requirements

- Do not enable the library's built-in RSA key features unless their full
  dependency and advisory state is re-reviewed.
- Do not suppress a RustSec advisory merely to make CI green.
- Store a dedicated host key securely; never log, export or include private
  key material in a report or backup.
- Show the ADB key fingerprint and require the owner to approve the matching
  prompt on the TV. Never accept a prompt for an unexpected device.
- Keep the first connected-device milestone read-only.
- Audit the exact locked dependency graph and licences before distributing
  any Windows binary.
