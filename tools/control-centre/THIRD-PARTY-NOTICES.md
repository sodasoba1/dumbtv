# Third-party notices

This is a provisional direct-dependency inventory, not release approval. The
complete resolved dependency graph has not yet been frozen, audited or
generated into a final notice bundle. Licence identifiers below are the
upstream package declarations; transitive dependencies and bundled assets
still require exact-version verification against the eventual Cargo.lock.

| Crate | Upstream-declared licence | Use |
|---|---|---|
| eframe / egui | MIT OR Apache-2.0 | Native desktop GUI |
| reqwest | MIT OR Apache-2.0 | HTTPS GitHub API and archive download |
| serde / serde_json | MIT OR Apache-2.0 | JSON metadata |
| atomicwrites | MIT | Atomic active-source manifest replacement |
| dirs | MIT OR Apache-2.0 | Windows Local AppData path |
| tempfile | MIT OR Apache-2.0 | Safe staging directory |
| zip | MIT | ZIP archive reading |
| libadb | MIT | Rust ADB wire protocol and TCP transport |
| tokio | MIT | Async TCP and bounded operation timeouts |
| windows-sys | MIT OR Apache-2.0 | Windows CNG key storage and RSA signing API |
| base64 | MIT OR Apache-2.0 | ADB public-key wire encoding |
| md-5 | MIT OR Apache-2.0 | Legacy Android 7 ADB prompt fingerprint only |
| num-bigint | MIT | ADB mincrypt public-key arithmetic |
| sha1 | MIT OR Apache-2.0 | SHA-1 digest required by legacy ADB AUTH |
| sha2 | MIT OR Apache-2.0 | SHA-256 host-key fingerprint |

The MD5 and SHA-1 uses are protocol/display compatibility requirements:
MD5 is only the legacy Android authorisation-dialog fingerprint, and SHA-1
is only the ADB AUTH signature digest. Neither is used for new data integrity,
password storage or application security decisions.

No Google Android SDK Platform Tools, ADB executable, Android APK, launcher
binary or third-party script is bundled by this prototype. Windows CNG is an
operating-system API, not a redistributed binary.

Before publishing a user-facing release:
- Generate and commit a Cargo.lock file from a clean, reproducible Windows build.
- Audit every direct and transitive dependency's exact licence and advisory status.
- Verify that the optional `rsa 0.9` feature of libadb is not enabled or pulled
  into the resolved build graph; do not suppress RUSTSEC-2023-0071.
- Generate notices for the exact locked graph and include required licence texts.
- Resolve all security advisories rather than suppressing them without a
  documented security decision.
- Review all upstream licence texts and the repository MIT licence separately
  from the catalogue's CC BY-SA 4.0 terms.
