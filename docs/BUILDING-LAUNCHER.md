# Building and releasing dumbtv home

The launcher APK is built from this repository's source. The release workflow
publishes it to this repository's GitHub Releases; `launcher/install.sh`
downloads that APK and checks it against the release's SHA-256 sidecar.

## One-time signing-key setup

Android requires every update of an installed app to use the same signing
certificate. The release workflow therefore needs one persistent keystore.
Do not regenerate it, commit it, upload it as a normal workflow artifact, or
discard the backup. Losing it means future APKs cannot update this installation
without uninstalling the app and losing its local settings.

On a Windows PC with JDK 17 installed, open PowerShell and create the key in a
safe folder outside the repository. Use a strong password and keep a separate
offline backup:

```powershell
keytool -genkeypair -v -keystore dumbtv-release.keystore -alias home -keyalg RSA -keysize 2048 -validity 10000 -storepass "CHOOSE_A_STRONG_PASSWORD" -keypass "CHOOSE_A_STRONG_PASSWORD" -dname "CN=dumbtv home"
```

The store password and key password must be the same because the build script
uses one password for both. Encode the keystore as one Base64 string:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\dumbtv-release.keystore")) | Set-Clipboard
```

In the GitHub repository, open **Settings → Secrets and variables → Actions**
and add these repository secrets:

- `DUMBTV_KEYSTORE_BASE64`: paste the Base64 value copied above.
- `DUMBTV_KEYPASS`: the same strong password used when creating the keystore.

Never put either value in a commit, issue, pull request, workflow log or chat.
Keep the original `dumbtv-release.keystore` and password backed up securely.
The keystore alias must be `home`.

## Build and test

Every push and pull request runs the existing validation workflow. It builds
the launcher with JDK 17, Android platform 25 and pinned Android build-tools
34.0.0, then checks the source tests, package permissions and Fire OS 6 target.
The release workflow performs additional checks on the signed APK:
package ID, version code/name, min SDK 25, target SDK 25, ZIP alignment,
signature verification and SHA-256 sidecar.

For a local build, install JDK 17 and Android SDK platform 25/build-tools
34.0.0, then run:

```sh
ANDROID_BUILD_TOOLS_VERSION=34.0.0 ./launcher/build.sh
./launcher/release-check.sh --local
```

A local build without `DUMBTV_KEYSTORE` uses a local `launcher/release.keystore`
and the development password default. Do not use that automatically generated
key for releases unless you have deliberately secured and backed it up. The
GitHub release workflow uses only the persistent Actions secrets.

## Publish a release

1. Update `VERSION`, `VERSION_CODE` and `VERSION_NAME` together in
   `launcher/version.env`. Increase the version code for every update.
2. Merge the change to `main` after its validation workflow passes.
3. Create and push a tag whose name exactly matches the version, for example:

   ```sh
   git checkout main
   git pull
   git tag launcher-v1.11
   git push origin launcher-v1.11
   ```

4. The `release launcher` workflow builds and verifies the signed APK, then
   creates or updates the matching GitHub Release with:
   - `dumbtv-home.apk`
   - `dumbtv-home.apk.sha256`

5. Check the published assets with:

   ```sh
   ./launcher/release-check.sh --remote
   ```

The checksum sidecar detects a damaged or incomplete APK download. It is
published alongside the release asset, so it is a transfer-integrity check,
not an independent signature of the release publisher.

## Important upgrade note

This fork's release signing key will differ from the original
`humblemuzzu/dumbtv` release key and from any locally generated key. Android
will reject an in-place update across different signing certificates. The
installer deliberately stops on `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; it does
not uninstall the existing app or erase its settings. If this is a first
installation, there is no previous signature to conflict with. If a different
build is already installed, back up/record its settings and decide whether to
uninstall it before installing the fork-signed APK.
