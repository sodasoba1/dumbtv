//! Pinned APK release metadata and checksum validation.
//!
//! This module deliberately does not install anything. The launcher and
//! optional YouTube workflows consume these validated descriptors only after
//! their device-state snapshot and explicit owner approval are implemented.

use sha2::{Digest, Sha256};

pub const LAUNCHER_PACKAGE: &str = "tv.dumbtv.home";
pub const SMARTTUBE_PACKAGE: &str = "org.smarttube.stable";
pub const STOCK_YOUTUBE_PACKAGE: &str = "com.amazon.firetv.youtube";
pub const SMARTTUBE_VERSION_CODE: u64 = 2446;
pub const SMARTTUBE_URL: &str = "https://github.com/yuliskov/SmartTube/releases/download/32.56s/SmartTube_stable_32.56_universal.apk";
pub const SMARTTUBE_SHA256: &str = "4a35b90940612e048e4e9cb9e10379f7db1e84f30375994129f10a571ee04ce5";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LauncherRelease {
    pub version: String,
    pub version_code: u64,
    pub version_name: String,
}

pub fn parse_launcher_version_env(contents: &str) -> Result<LauncherRelease, String> {
    let mut version = None;
    let mut version_code = None;
    let mut version_name = None;
    for line in contents.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') { continue; }
        let (key, value) = line.split_once('=')
            .ok_or_else(|| "launcher version manifest contains a malformed line".to_owned())?;
        let value = value.trim();
        if value.is_empty() || value.chars().any(char::is_control) {
            return Err("launcher version manifest contains an empty or invalid value".into());
        }
        let slot = match key.trim() {
            "VERSION" => &mut version,
            "VERSION_CODE" => &mut version_code,
            "VERSION_NAME" => &mut version_name,
            _ => return Err(format!("unsupported launcher version key: {}", key.trim())),
        };
        if slot.replace(value.to_owned()).is_some() {
            return Err(format!("duplicate launcher version key: {}", key.trim()));
        }
    }
    let version = version.ok_or("launcher manifest is missing VERSION")?;
    let version_code = version_code.ok_or("launcher manifest is missing VERSION_CODE")?
        .parse::<u64>().map_err(|_| "launcher VERSION_CODE is not a positive integer")?;
    let version_name = version_name.ok_or("launcher manifest is missing VERSION_NAME")?;
    if version_code == 0 || !valid_dotted_version(&version) || version_name != version {
        return Err("launcher version fields are inconsistent or invalid".into());
    }
    Ok(LauncherRelease { version, version_code, version_name })
}

fn valid_dotted_version(value: &str) -> bool {
    if value.len() > 24 { return false; }
    let mut parts = value.split('.');
    let first = parts.next();
    first.is_some_and(|part| !part.is_empty() && part.bytes().all(|byte| byte.is_ascii_digit()))
        && parts.all(|part| !part.is_empty() && part.bytes().all(|byte| byte.is_ascii_digit()))
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    digest.iter().map(|byte| format!("{byte:02x}")).collect()
}

pub fn verify_sha256(bytes: &[u8], expected: &str) -> Result<(), String> {
    if expected.len() != 64 || !expected.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err("expected SHA-256 must be exactly 64 hexadecimal characters".into());
    }
    let actual = sha256_hex(bytes);
    if !actual.eq_ignore_ascii_case(expected) {
        return Err(format!("SHA-256 mismatch: received {actual}, expected {expected}"));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_the_repository_launcher_version_manifest() {
        let release = parse_launcher_version_env(
            "# version\nVERSION=1.13\nVERSION_CODE=14\nVERSION_NAME=1.13\n"
        ).unwrap();
        assert_eq!(release, LauncherRelease {
            version: "1.13".into(), version_code: 14, version_name: "1.13".into(),
        });
    }

    #[test]
    fn rejects_duplicate_unknown_and_inconsistent_launcher_fields() {
        assert!(parse_launcher_version_env("VERSION=1.13\nVERSION=1.14\nVERSION_CODE=14\nVERSION_NAME=1.13").is_err());
        assert!(parse_launcher_version_env("VERSION=1.13\nVERSION_CODE=14\nVERSION_NAME=1.13\nEXEC=rm").is_err());
        assert!(parse_launcher_version_env("VERSION=1.13\nVERSION_CODE=14\nVERSION_NAME=1.12").is_err());
        assert!(parse_launcher_version_env("VERSION=1.13\nVERSION_CODE=0\nVERSION_NAME=1.13").is_err());
    }

    #[test]
    fn verifies_sha256_and_rejects_bad_sidecars() {
        let bytes = b"dumbtv test asset";
        let digest = sha256_hex(bytes);
        assert_eq!(digest.len(), 64);
        assert!(verify_sha256(bytes, &digest).is_ok());
        assert!(verify_sha256(bytes, &"0".repeat(64)).is_err());
        assert!(verify_sha256(bytes, "not-a-hash").is_err());
    }

    #[test]
    fn smarttube_metadata_matches_the_maintained_script() {
        assert_eq!(SMARTTUBE_PACKAGE, "org.smarttube.stable");
        assert_eq!(STOCK_YOUTUBE_PACKAGE, "com.amazon.firetv.youtube");
        assert_eq!(SMARTTUBE_VERSION_CODE, 2446);
        assert_eq!(SMARTTUBE_SHA256.len(), 64);
        assert!(SMARTTUBE_URL.starts_with("https://github.com/yuliskov/SmartTube/releases/download/"));
    }
}
