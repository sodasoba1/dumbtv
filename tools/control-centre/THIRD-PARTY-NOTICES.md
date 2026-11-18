# Third-party notices

This prototype depends on the following direct Rust crates. The resolved transitive dependency graph has not yet been frozen or approved for redistribution; a generated complete notice file and automated licence/advisory audit are release gates.

| Crate | Licence declared by upstream | Use |
|---|---|---|
| eframe / egui | MIT OR Apache-2.0 | Native desktop GUI |
| reqwest | MIT OR Apache-2.0 | HTTPS GitHub API and archive download |
| serde / serde_json | MIT OR Apache-2.0 | JSON metadata |
| atomicwrites | MIT OR Apache-2.0 | Atomic active-source manifest replacement |
| dirs | MIT OR Apache-2.0 | Windows Local AppData path |
| tempfile | MIT OR Apache-2.0 | Safe staging directory |
| zip | MIT | ZIP archive reading |

No Google Android SDK Platform Tools, ADB executable, Android APK, launcher binary or third-party script is bundled by this prototype.

Before publishing a user-facing release:
- Generate a Cargo.lock file from a clean, reproducible build.
- Audit every direct and transitive dependency's licence and advisory status.
- Generate notices for the exact locked graph.
- Resolve all security advisories rather than suppressing them without a documented security decision.
