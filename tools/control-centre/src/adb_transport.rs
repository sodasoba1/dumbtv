//! Direct TCP ADB inspection and original-state capture for one owner-selected Fire TV.
//!
//! The UI supplies a single private/local IP. All remote commands are fixed by
//! Rust code or assembled from validated catalogue/package identifiers; there
//! is no user-provided shell-command path.

use std::{
    collections::{BTreeMap, BTreeSet},
    net::{IpAddr, SocketAddr},
    path::Path,
    time::Duration,
};

use libadb::{Connection, ConnectionConfig, HostBanner, TokioTcp};
use tokio::{net::TcpStream, time::timeout};

use crate::{
    catalogue::FireOsCatalogue,
    cng_auth::WindowsCngAdbKey,
    device_profile::{Compatibility, DeviceProfile},
    package_inventory::PackageInventory,
    settings_catalogue::SettingsCatalogue,
    snapshot::{AppOpState, DeviceSnapshot, PackageState},
};

const ADB_PORT: u16 = 5555;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(8);
const AUTH_TIMEOUT: Duration = Duration::from_secs(60);
const COMMAND_TIMEOUT: Duration = Duration::from_secs(20);
const COMMAND_OUTPUT_LIMIT: usize = 256 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceInventory {
    pub profile: DeviceProfile,
    pub packages: Option<PackageInventory>,
}

/// Read-only connection and package inventory. No device state is changed.
pub async fn inspect_device(ip: IpAddr) -> Result<DeviceInventory, String> {
    let mut connection = connect(ip).await?;
    let profile = read_profile(&mut connection).await?;
    if profile.cleanup_compatibility() != Compatibility::Supported {
        return Ok(DeviceInventory { profile, packages: None });
    }
    let packages = read_package_inventory(&mut connection).await?;
    Ok(DeviceInventory { profile, packages: Some(packages) })
}

/// Capture and persist the original state before any device-changing operation.
/// Capture fails closed if a required state source cannot be parsed; it never
/// writes a partial snapshot that could later be mistaken for a complete backup.
pub async fn capture_original_snapshot(
    ip: IpAddr,
    data_root: &Path,
    snapshot_path: &Path,
) -> Result<(), String> {
    let mut connection = connect(ip).await?;
    let profile = read_profile(&mut connection).await?;
    if profile.cleanup_compatibility() != Compatibility::Supported {
        return Err("original-state capture is available only for validated Amazon Fire OS SDK 25–30 devices".into());
    }

    let packages = read_package_inventory(&mut connection).await?;
    let catalogue = FireOsCatalogue::load(data_root, profile.sdk)?;
    let settings_catalogue = SettingsCatalogue::load(data_root)?;

    let mut secure_keys = BTreeSet::new();
    let mut global_keys = BTreeSet::new();
    for item in settings_catalogue.privacy.iter()
        .chain(settings_catalogue.alexa_off.iter())
        .chain(settings_catalogue.restore_defaults.iter())
    {
        match item.namespace {
            crate::settings_catalogue::SettingsNamespace::Secure => { secure_keys.insert(item.key.clone()); }
            crate::settings_catalogue::SettingsNamespace::Global => { global_keys.insert(item.key.clone()); }
        }
    }
    // The existing scripts also change/read these keys during screensaver and
    // legacy VPN cleanup. Capture them even though those workflows remain gated.
    secure_keys.extend([
        "screensaver_components".to_owned(),
        "screensaver_default_component".to_owned(),
        "always_on_vpn_app".to_owned(),
    ]);

    let secure_values = parse_settings_list(
        &run_command(&mut connection, "settings list secure").await?,
    )?;
    let global_values = parse_settings_list(
        &run_command(&mut connection, "settings list global").await?,
    )?;
    let secure_settings = secure_keys.into_iter()
        .map(|key| { let value = secure_values.get(&key).cloned(); (key, value) })
        .collect();
    let global_settings = global_keys.into_iter()
        .map(|key| { let value = global_values.get(&key).cloned(); (key, value) })
        .collect();

    let mut app_ops = Vec::new();
    let mut operations = vec!["RUN_IN_BACKGROUND"];
    if profile.sdk >= 28 {
        operations.push("RUN_ANY_IN_BACKGROUND");
    }
    for package in &catalogue.protected {
        if !packages.installed.contains(package) {
            continue;
        }
        for operation in &operations {
            let command = format!("appops get {package} {operation}");
            let output = run_command(&mut connection, &command).await?;
            let mode = parse_appop_mode(&output, operation)?;
            app_ops.push(AppOpState {
                package: package.clone(),
                operation: (*operation).to_owned(),
                mode,
            });
        }
    }

    let restrict_background = parse_restrict_background(
        &run_command(&mut connection, "cmd netpolicy get restrict-background").await?,
    )?;
    let metered_blacklist_uids = parse_uid_blacklist(
        &run_command(&mut connection, "cmd netpolicy list restrict-background-blacklist").await?,
    )?;

    let package_states = packages.installed.iter().map(|package| {
        let state = if packages.disabled.contains(package) {
            PackageState::Disabled
        } else {
            PackageState::Enabled
        };
        (package.clone(), state)
    }).collect::<BTreeMap<_, _>>();

    let snapshot = DeviceSnapshot {
        device_label: format!("{} {}", profile.manufacturer, profile.model).trim().to_owned(),
        manufacturer: profile.manufacturer,
        model: profile.model,
        android_sdk: profile.sdk,
        packages: package_states,
        secure_settings,
        global_settings,
        app_ops,
        restrict_background: Some(restrict_background),
        metered_blacklist_uids: Some(metered_blacklist_uids),
        user_apps_uninstalled_by_tool: BTreeSet::new(),
    };
    snapshot.save_once(snapshot_path)?;
    Ok(())
}

async fn connect(ip: IpAddr) -> Result<Connection<TokioTcp>, String> {
    if !is_local_target(ip) {
        return Err("refusing to connect to a non-local IP address".into());
    }
    let address = SocketAddr::new(ip, ADB_PORT);
    let stream = timeout(CONNECT_TIMEOUT, TcpStream::connect(address))
        .await
        .map_err(|_| "timed out connecting to the Fire TV ADB port".to_owned())?
        .map_err(|error| format!("could not connect to {address}: {error}"))?;
    stream.set_nodelay(true)
        .map_err(|error| format!("could not configure the ADB socket: {error}"))?;
    let transport = TokioTcp::new(stream);
    let key = WindowsCngAdbKey::open_or_create()?;
    timeout(
        AUTH_TIMEOUT,
        Connection::connect(
            transport,
            key,
            HostBanner::Features(&[]),
            ConnectionConfig::new()
                .with_max_payload(64 * 1024)
                .with_max_rx_per_channel(COMMAND_OUTPUT_LIMIT)
                .with_max_channels(1),
        ),
    )
    .await
    .map_err(|_| "ADB authentication timed out; check the TV's RSA authorisation prompt".to_owned())?
    .map_err(|error| format!("ADB handshake/authentication failed: {error}"))
}

async fn read_profile(connection: &mut Connection<TokioTcp>) -> Result<DeviceProfile, String> {
    let properties = run_command(connection, "getprop").await?;
    DeviceProfile::parse_getprop_output(&properties)
}

async fn read_package_inventory(connection: &mut Connection<TokioTcp>) -> Result<PackageInventory, String> {
    let all = run_command(connection, "pm list packages").await?;
    let system = run_command(connection, "pm list packages -s").await?;
    let disabled = run_command(connection, "pm list packages -d").await?;
    PackageInventory::from_pm_outputs(&all, &system, &disabled)
}

async fn run_command(connection: &mut Connection<TokioTcp>, command: &str) -> Result<String, String> {
    let mut output = vec![0u8; COMMAND_OUTPUT_LIMIT];
    let written = timeout(
        COMMAND_TIMEOUT,
        libadb::shell::v1::exec(connection, command, &mut output),
    )
    .await
    .map_err(|_| format!("timed out running device inventory command: {command}"))?
    .map_err(|error| format!("device inventory command failed ({command}): {error}"))?;
    if written >= COMMAND_OUTPUT_LIMIT {
        return Err(format!("device inventory output exceeded the {COMMAND_OUTPUT_LIMIT}-byte limit: {command}"));
    }
    Ok(String::from_utf8_lossy(&output[..written]).into_owned())
}

fn parse_settings_list(output: &str) -> Result<BTreeMap<String, String>, String> {
    let mut values = BTreeMap::new();
    for (index, line) in output.lines().enumerate() {
        let line = line.trim().trim_end_matches('\r');
        if line.is_empty() { continue; }
        let (key, value) = line.split_once('=').ok_or_else(|| format!("unrecognised settings-list output at line {}", index + 1))?;
        if key.is_empty() || !key.bytes().all(|b| b.is_ascii_alphanumeric() || b"_.:-".contains(&b))
            || value.chars().any(char::is_control)
        {
            return Err(format!("unsafe settings-list entry at line {}", index + 1));
        }
        if values.insert(key.to_owned(), value.to_owned()).is_some() {
            return Err(format!("duplicate settings key returned by device: {key}"));
        }
    }
    if values.is_empty() {
        return Err("device returned an empty settings list; refusing to assume every setting was absent".into());
    }
    Ok(values)
}

fn parse_appop_mode(output: &str, operation: &str) -> Result<String, String> {
    if output.to_ascii_lowercase().contains("no operations") {
        return Ok("default".into());
    }
    for line in output.lines() {
        let Some((name, rest)) = line.trim().split_once(':') else { continue; };
        if name.trim() != operation { continue; }
        let mode = rest.split_whitespace().next().unwrap_or("").trim_matches(|c: char| !c.is_ascii_alphabetic());
        if matches!(mode, "allow" | "ignore" | "deny" | "default" | "foreground" | "errored") {
            return Ok(mode.to_owned());
        }
        return Err(format!("device returned an unknown AppOps mode for {operation}"));
    }
    Err(format!("device did not report the requested AppOps mode: {operation}"))
}

fn parse_restrict_background(output: &str) -> Result<bool, String> {
    let mut found = BTreeSet::new();
    for token in output.to_ascii_lowercase().split(|c: char| !c.is_ascii_alphanumeric()) {
        match token {
            "true" | "enabled" => { found.insert(true); }
            "false" | "disabled" => { found.insert(false); }
            _ => {}
        }
    }
    if found.len() == 1 { return Ok(*found.iter().next().unwrap()); }
    Err("device did not return one unambiguous Data Saver state".into())
}

fn parse_uid_blacklist(output: &str) -> Result<BTreeSet<u32>, String> {
    let trimmed = output.trim();
    let lower = trimmed.to_ascii_lowercase();
    if lower.contains("none") || lower.contains("empty") || trimmed.ends_with("[]") {
        return Ok(BTreeSet::new());
    }
    let values = if let (Some(start), Some(end)) = (trimmed.find('['), trimmed.find(']')) {
        if end < start { return Err("malformed metered-data UID blacklist".into()); }
        &trimmed[start + 1..end]
    } else if trimmed.lines().all(|line| line.trim().is_empty() || line.trim().bytes().all(|b| b.is_ascii_digit())) {
        trimmed
    } else {
        return Err("device returned an unrecognised metered-data UID blacklist format".into());
    };
    let mut result = BTreeSet::new();
    for token in values.split(|c: char| !c.is_ascii_digit()) {
        if token.is_empty() { continue; }
        let uid = token.parse::<u32>().map_err(|_| "invalid UID in metered-data blacklist")?;
        result.insert(uid);
    }
    Ok(result)
}

fn is_local_target(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(address) => address.is_private() || address.is_loopback() || address.is_link_local(),
        IpAddr::V6(address) => address.is_unique_local() || address.is_loopback() || address.is_unicast_link_local(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_private_or_link_local_addresses_are_allowed() {
        assert!(is_local_target("192.168.1.25".parse().unwrap()));
        assert!(is_local_target("10.0.0.5".parse().unwrap()));
        assert!(is_local_target("169.254.1.2".parse().unwrap()));
        assert!(is_local_target("fd00::1234".parse().unwrap()));
        assert!(!is_local_target("8.8.8.8".parse().unwrap()));
        assert!(!is_local_target("2001:4860:4860::8888".parse().unwrap()));
    }

    #[test]
    fn parses_settings_with_equals_and_preserves_empty_values() {
        let values = parse_settings_list("safe=1\nempty=\ncolon:key=value=with=equals\n").unwrap();
        assert_eq!(values.get("empty").map(String::as_str), Some(""));
        assert_eq!(values.get("colon:key").map(String::as_str), Some("value=with=equals"));
    }

    #[test]
    fn parses_appops_and_network_policy_without_guessing() {
        assert_eq!(parse_appop_mode("RUN_IN_BACKGROUND: ignore; time=+1h", "RUN_IN_BACKGROUND").unwrap(), "ignore");
        assert_eq!(parse_appop_mode("No operations.", "RUN_IN_BACKGROUND").unwrap(), "default");
        assert!(parse_appop_mode("RUN_IN_BACKGROUND: maybe", "RUN_IN_BACKGROUND").is_err());
        assert!(parse_restrict_background("Restrict background data usage: true").unwrap());
        assert!(!parse_restrict_background("Restrict background data usage: false").unwrap());
        assert!(parse_restrict_background("unknown").is_err());
        assert_eq!(parse_uid_blacklist("Restrict background blacklist uids: [10001, 10002]").unwrap(), [10001, 10002].into_iter().collect());
        assert!(parse_uid_blacklist("unknown format").is_err());
    }
}
