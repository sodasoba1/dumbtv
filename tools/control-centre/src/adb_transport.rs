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
    operation_journal::{OperationJournal, WorkflowKind},
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
    pub protected_uids: BTreeMap<String, u32>,
}

/// Read-only connection and package inventory. No device state is changed.
pub async fn inspect_device(ip: IpAddr, data_root: &Path) -> Result<DeviceInventory, String> {
    let mut connection = connect(ip).await?;
    let profile = read_profile(&mut connection).await?;
    if profile.cleanup_compatibility() != Compatibility::Supported {
        return Ok(DeviceInventory { profile, packages: None, protected_uids: BTreeMap::new() });
    }
    let packages = read_package_inventory(&mut connection).await?;
    let catalogue = FireOsCatalogue::load(data_root, profile.sdk)?;
    let mut protected_uids = BTreeMap::new();
    let uid_targets: BTreeSet<String> = catalogue.packages.iter().chain(catalogue.protected.iter()).cloned().collect();
    for package in &uid_targets {
        if !packages.installed.contains(package) { continue; }
        let output = run_command(&mut connection, &format!("dumpsys package {package}")).await?;
        if let Some(uid) = parse_package_uid(&output) {
            protected_uids.insert(package.clone(), uid);
        }
    }
    Ok(DeviceInventory { profile, packages: Some(packages), protected_uids })
}

/// Capture and persist the original state before any device-changing operation.
/// Capture fails closed if a required state source cannot be parsed; it never
/// writes a partial snapshot that could later be mistaken for a complete backup.
pub async fn capture_original_snapshot(
    ip: IpAddr,
    expected: &DeviceProfile,
    data_root: &Path,
    source_commit: &str,
    snapshot_path: &Path,
) -> Result<(), String> {
    let mut connection = connect(ip).await?;
    let profile = read_profile(&mut connection).await?;
    if !same_device(&profile, expected) {
        return Err("the device at the selected IP changed after inspection; snapshot capture aborted".into());
    }
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
    let appop_targets: BTreeSet<String> = catalogue.packages.iter().chain(catalogue.protected.iter()).cloned().collect();
    for package in &appop_targets {
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
        build_display_id: profile.build_display_id,
        android_sdk: profile.sdk,
        source_commit: source_commit.to_owned(),
        packages: package_states,
        user_packages: packages.user.clone(),
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



#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExecutionReport {
    pub completed: Vec<String>,
    pub failure: Option<String>,
}

/// Execute a reviewed list in order over one authenticated connection. The
/// first failed command or failed read-back stops the sequence; completed steps
/// remain in the report so the owner can see partial progress and restore.
pub async fn execute_plan(
    ip: IpAddr,
    expected: &DeviceProfile,
    original: &DeviceSnapshot,
    operations: &[crate::operations::PlannedOperation],
    never_disable: &BTreeSet<String>,
    journal_path: &Path,
) -> Result<ExecutionReport, String> {
    let mut journal = OperationJournal::load(journal_path)?;
    if journal.workflow != WorkflowKind::Cleanup || journal.operations.iter().map(|item| &item.description).ne(operations.iter().map(|item| &item.description)) {
        return Err("cleanup journal does not match the reviewed operation plan".into());
    }
    let mut connection = connect(ip).await?;
    let actual = read_profile(&mut connection).await?;
    if !same_device(&actual, expected) {
        return Err("the device at the selected IP no longer matches the inspected Fire TV; operation aborted".into());
    }
    validate_original_snapshot_state(&mut connection, original).await?;
    let mut report = ExecutionReport { completed: Vec::new(), failure: None };
    for (index, operation) in operations.iter().enumerate() {
        journal.mark_running(journal_path, index)?;
        match execute_one(&mut connection, &operation.command, never_disable).await {
            Ok(detail) => {
                journal.mark_verified_with_detail(journal_path, index, Some(&detail))?;
                report.completed.push(operation.description.clone());
            }
            Err(error) => {
                journal.mark_failed(journal_path, index, &error)?;
                journal.finish_failed(journal_path)?;
                report.failure = Some(format!("{}: {error}", operation.description));
                break;
            }
        }
    }
    if report.failure.is_none() {
        if let Err(error) = verify_cleanup_operations(&mut connection, operations, Some(&journal)).await {
            let detail = format!("final post-change verification failed: {error}");
            journal.finish_with_error(journal_path, &detail)?;
            report.failure = Some(detail);
        }
    }
    if report.failure.is_none() { journal.finish(journal_path)?; }
    Ok(report)
}

/// Re-read every persistent cleanup result after the complete sequence. The
/// individual writes already have read-back checks; this second pass catches
/// later steps that unexpectedly changed an earlier setting or policy.
async fn verify_cleanup_operations(
    connection: &mut Connection<TokioTcp>,
    operations: &[crate::operations::PlannedOperation],
    journal: Option<&OperationJournal>,
) -> Result<(), String> {
    use crate::device_command::DeviceCommand;
    let mut package_inventory: Option<PackageInventory> = None;
    let mut secure: Option<BTreeMap<String, String>> = None;
    let mut global: Option<BTreeMap<String, String>> = None;
    let mut running: Option<BTreeSet<String>> = None;
    for operation in operations {
        // Journals are local files and may be edited. Re-validate every typed
        // argument before using it to construct even a read-only shell query.
        operation.command.to_adb_shell(&BTreeSet::new())?;
        match &operation.command {
            DeviceCommand::DisablePackage(package) => {
                let disabled = crate::package_inventory::parse_pm_list_output(
                    &run_command(connection, "pm list packages -d").await?,
                )?;
                if !disabled.contains(package) { return Err(format!("{package} is no longer disabled")); }
            }
            DeviceCommand::UninstallPackageForUser { package, .. } => {
                if package_inventory.is_none() {
                    package_inventory = Some(read_package_inventory(connection).await?);
                }
                if package_inventory.as_ref().is_some_and(|items| items.installed.contains(package)) {
                    return Err(format!("owner-selected user app {package} is still installed for user 0"));
                }
            }
            DeviceCommand::PutSetting { namespace, key, value } => {
                let cache = match namespace {
                    crate::settings_catalogue::SettingsNamespace::Secure => &mut secure,
                    crate::settings_catalogue::SettingsNamespace::Global => &mut global,
                };
                if cache.is_none() {
                    let command = match namespace {
                        crate::settings_catalogue::SettingsNamespace::Secure => "settings list secure",
                        crate::settings_catalogue::SettingsNamespace::Global => "settings list global",
                    };
                    *cache = Some(parse_settings_list(&run_command(connection, command).await?)?);
                }
                if cache.as_ref().and_then(|values| values.get(key)) != Some(value) {
                    return Err(format!("setting {key} no longer has the reviewed value"));
                }
            }
            DeviceCommand::SetAppOp { package, operation: appop, mode } => {
                let output = run_command(connection, &format!("appops get {package} {appop}")).await?;
                if parse_appop_mode(&output, appop)? != *mode {
                    return Err(format!("AppOp {appop} for {package} no longer has mode {mode}"));
                }
            }
            DeviceCommand::SetRestrictBackground(enabled) => {
                let actual = parse_restrict_background(
                    &run_command(connection, "cmd netpolicy get restrict-background").await?,
                )?;
                if actual != *enabled { return Err("Data Saver no longer has the reviewed state".into()); }
            }
            DeviceCommand::AddMeteredBlacklistUid(uid) => {
                let actual = parse_uid_blacklist(
                    &run_command(connection, "cmd netpolicy list restrict-background-blacklist").await?,
                )?;
                if !actual.contains(uid) { return Err(format!("metered-data UID {uid} is no longer blacklisted")); }
            }
            DeviceCommand::ForceStopPackage(package) => {
                if running.is_none() { running = Some(read_running_names(connection).await?); }
                if running.as_ref().is_some_and(|names| names.contains(package)) {
                    return Err(format!("{package} is running again after cleanup"));
                }
            }
            DeviceCommand::ForceStopAndRestrictIfRunning { package, uid, sdk } => {
                if running.is_none() { running = Some(read_running_names(connection).await?); }
                if running.as_ref().is_some_and(|names| names.contains(package)) {
                    return Err(format!("{package} is running again after cleanup"));
                }
                let restrictions_applied = journal
                    .and_then(|journal| journal.operations.iter().find(|item| item.description == operation.description))
                    .and_then(|item| item.detail.as_deref())
                    .is_some_and(|detail| detail.starts_with("Restricted background execution"));
                if restrictions_applied {
                    let mut appops = vec!["RUN_IN_BACKGROUND"];
                    if *sdk >= 28 { appops.push("RUN_ANY_IN_BACKGROUND"); }
                    for appop in appops {
                        let output = run_command(connection, &format!("appops get {package} {appop}")).await?;
                        if parse_appop_mode(&output, appop)? != "ignore" {
                            return Err(format!("background AppOp {appop} for {package} no longer matches cleanup"));
                        }
                    }
                    if let Some(uid) = uid {
                        let blacklist = parse_uid_blacklist(
                            &run_command(connection, "cmd netpolicy list restrict-background-blacklist").await?,
                        )?;
                        if !blacklist.contains(uid) {
                            return Err(format!("metered-data UID {uid} for {package} is no longer blacklisted"));
                        }
                    }
                }
            }
            DeviceCommand::EnablePackage(_)
            | DeviceCommand::DeleteSetting { .. }
            | DeviceCommand::RemoveMeteredBlacklistUid(_) => {
                return Err("unexpected command type in cleanup verification plan".into());
            }
        }
    }
    Ok(())
}

/// Reboot only after the owner has explicitly enabled the option. Require an
/// observed ADB disconnect before accepting a reconnect, then re-check identity.
pub async fn reboot_and_reconnect(
    ip: IpAddr,
    expected: &DeviceProfile,
    operations: &[crate::operations::PlannedOperation],
    journal: &OperationJournal,
) -> Result<DeviceProfile, String> {
    let mut connection = connect(ip).await?;
    let before = read_profile(&mut connection).await?;
    if !same_device(&before, expected) {
        return Err("the device identity changed before reboot; reboot was not sent".into());
    }
    let _ = run_command(&mut connection, "reboot").await;
    drop(connection);

    let address = SocketAddr::new(ip, ADB_PORT);
    let mut observed_disconnect = false;
    for _ in 0..15 {
        match timeout(Duration::from_secs(2), TcpStream::connect(address)).await {
            Ok(Err(_)) | Err(_) => { observed_disconnect = true; break; }
            Ok(Ok(stream)) => drop(stream),
        }
        tokio::time::sleep(Duration::from_secs(2)).await;
    }
    if !observed_disconnect {
        return Err("the ADB port did not disconnect; reboot could not be confirmed".into());
    }

    let deadline = tokio::time::Instant::now() + Duration::from_secs(120);
    while tokio::time::Instant::now() < deadline {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if let Ok(Ok(stream)) = timeout(remaining.min(Duration::from_secs(2)), TcpStream::connect(address)).await {
            drop(stream);
            let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
            if remaining.is_zero() { break; }
            if let Ok(Ok(mut connection)) = timeout(remaining.min(Duration::from_secs(12)), connect(ip)).await {
                if let Ok(profile) = read_profile(&mut connection).await {
                    if same_device(&profile, expected) {
                        verify_cleanup_operations(&mut connection, operations, Some(journal)).await?;
                        return Ok(profile);
                    }
                    return Err("a different device answered after reboot; identity check failed".into());
                }
            }
        }
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() { break; }
        tokio::time::sleep(remaining.min(Duration::from_secs(2))).await;
    }
    Err("the Fire TV did not complete ADB reconnect within 120 seconds".into())
}

/// Push a verified APK into the app-private staging area using ADB sync.
/// The caller must verify the APK checksum before calling this function.
/// Kept isolated until the launcher/YouTube snapshot and rollback flows are wired.
#[allow(dead_code)]
pub async fn push_staged_apk(
    ip: IpAddr,
    expected: &DeviceProfile,
    remote_path: &str,
    apk_bytes: &[u8],
) -> Result<(), String> {
    if !valid_apk_staging_path(remote_path) {
        return Err("APK staging path must be a single named file under /data/local/tmp/dumbtv-".into());
    }
    if apk_bytes.is_empty() || apk_bytes.len() > 128 * 1024 * 1024 {
        return Err("APK is empty or exceeds the 128 MiB transfer limit".into());
    }
    let mut connection = connect(ip).await?;
    let actual = read_profile(&mut connection).await?;
    if !same_device(&actual, expected) {
        return Err("the device at the selected IP no longer matches the inspected Fire TV".into());
    }
    let mut receive_buffer = vec![0_u8; 65_544];
    let mut sync = libadb::sync::open(&mut connection, &mut receive_buffer).await
        .map_err(|error| format!("could not open ADB file-sync channel: {error}"))?;
    timeout(Duration::from_secs(180), sync.send(remote_path, 0o100600, 0, apk_bytes))
        .await
        .map_err(|_| "timed out transferring the APK over ADB sync".to_owned())?
        .map_err(|error| format!("ADB sync rejected the APK transfer: {error}"))?;
    // Read the staged bytes back through the same sync protocol and compare
    // their SHA-256, not merely their file size, before any future installer
    // is allowed to consume the path.
    let remote_bytes = timeout(Duration::from_secs(180), sync.recv(remote_path))
        .await
        .map_err(|_| "timed out reading the staged APK back from the Fire TV".to_owned())?
        .map_err(|error| format!("could not read the staged APK back: {error}"))?;
    let local_hash = crate::apk_release::sha256_hex(apk_bytes);
    let remote_hash = crate::apk_release::sha256_hex(&remote_bytes);
    if remote_bytes.len() != apk_bytes.len() || remote_hash != local_hash {
        return Err("staged APK read-back did not match the verified local bytes".into());
    }
    timeout(COMMAND_TIMEOUT, sync.quit()).await
        .map_err(|_| "timed out closing the ADB sync channel".to_owned())?
        .map_err(|error| format!("could not close the ADB sync channel: {error}"))?;
    Ok(())
}

fn valid_apk_staging_path(path: &str) -> bool {
    let Some(name) = path.strip_prefix("/data/local/tmp/dumbtv-") else { return false; };
    name.ends_with(".apk")
        && name.len() <= 96
        && name.bytes().all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.'))
        && !name.contains("..")
}

/// Read-only verification of every cleanup mutation marked verified in a
/// matching journal. This can be run again after reboot or a later firmware change.
pub async fn verify_cleanup_journal(
    ip: IpAddr,
    expected: &DeviceProfile,
    journal: &OperationJournal,
) -> Result<usize, String> {
    if journal.workflow != WorkflowKind::Cleanup
        || journal.manufacturer != expected.manufacturer
        || journal.model != expected.model
        || journal.build_display_id != expected.build_display_id
        || journal.android_sdk != expected.sdk
    {
        return Err("cleanup journal identity does not match the connected device".into());
    }
    let operations: Vec<crate::operations::PlannedOperation> = journal.operations.iter()
        .filter(|item| item.status == crate::operation_journal::OperationStatus::Verified)
        .filter_map(|item| item.command.clone().map(|command| crate::operations::PlannedOperation {
            description: item.description.clone(), command,
        }))
        .collect();
    if operations.is_empty() {
        return Err("matching cleanup journal contains no verified typed operations to check".into());
    }
    let mut connection = connect(ip).await?;
    let actual = read_profile(&mut connection).await?;
    if !same_device(&actual, expected) {
        return Err("the device at the selected IP no longer matches the inspected Fire TV".into());
    }
    verify_cleanup_operations(&mut connection, &operations, Some(journal)).await?;
    Ok(operations.len())
}

pub async fn read_current_device_state(
    ip: IpAddr,
    expected: &DeviceProfile,
    original: &DeviceSnapshot,
) -> Result<crate::snapshot::CurrentDeviceState, String> {
    let mut connection = connect(ip).await?;
    let actual = read_profile(&mut connection).await?;
    if !same_device(&actual, expected) {
        return Err("the device at the selected IP no longer matches the inspected Fire TV".into());
    }
    read_current_state_on_connection(&mut connection, original).await
}

pub async fn execute_restore_plan(
    ip: IpAddr,
    expected: &DeviceProfile,
    original: &DeviceSnapshot,
    reviewed_current: &crate::snapshot::CurrentDeviceState,
    operations: &[crate::operations::PlannedOperation],
    never_disable: &BTreeSet<String>,
    journal_path: &Path,
) -> Result<ExecutionReport, String> {
    let mut journal = OperationJournal::load(journal_path)?;
    if journal.workflow != WorkflowKind::Restore || journal.operations.iter().map(|item| &item.description).ne(operations.iter().map(|item| &item.description)) {
        return Err("restore journal does not match the reviewed restore plan".into());
    }
    let mut connection = connect(ip).await?;
    let actual = read_profile(&mut connection).await?;
    if !same_device(&actual, expected) {
        return Err("the device at the selected IP no longer matches the inspected Fire TV; restore aborted".into());
    }
    let current = read_current_state_on_connection(&mut connection, original).await?;
    if &current != reviewed_current {
        return Err("device state changed after the restore plan was reviewed; no restore command was sent".into());
    }
    let mut report = ExecutionReport { completed: Vec::new(), failure: None };
    for (index, operation) in operations.iter().enumerate() {
        journal.mark_running(journal_path, index)?;
        match execute_one(&mut connection, &operation.command, never_disable).await {
            Ok(detail) => {
                journal.mark_verified_with_detail(journal_path, index, Some(&detail))?;
                report.completed.push(operation.description.clone());
            }
            Err(error) => {
                journal.mark_failed(journal_path, index, &error)?;
                journal.finish_failed(journal_path)?;
                report.failure = Some(format!("{}: {error}", operation.description));
                break;
            }
        }
    }
    if report.failure.is_none() { journal.finish(journal_path)?; }
    Ok(report)
}

async fn read_current_state_on_connection(
    connection: &mut Connection<TokioTcp>,
    original: &DeviceSnapshot,
) -> Result<crate::snapshot::CurrentDeviceState, String> {
    let packages = read_package_inventory(connection).await?;
    let package_states = packages.installed.iter().map(|package| {
        let state = if packages.disabled.contains(package) { PackageState::Disabled } else { PackageState::Enabled };
        (package.clone(), state)
    }).collect();
    let secure_values = parse_settings_list(&run_command(connection, "settings list secure").await?)?;
    let global_values = parse_settings_list(&run_command(connection, "settings list global").await?)?;
    let secure_settings = original.secure_settings.keys().map(|key| (key.clone(), secure_values.get(key).cloned())).collect();
    let global_settings = original.global_settings.keys().map(|key| (key.clone(), global_values.get(key).cloned())).collect();
    let mut app_ops = Vec::new();
    for item in &original.app_ops {
        let command = format!("appops get {} {}", item.package, item.operation);
        let output = run_command(connection, &command).await?;
        app_ops.push(AppOpState {
            package: item.package.clone(),
            operation: item.operation.clone(),
            mode: parse_appop_mode(&output, &item.operation)?,
        });
    }
    let restrict_background = Some(parse_restrict_background(
        &run_command(connection, "cmd netpolicy get restrict-background").await?,
    )?);
    let metered_blacklist_uids = Some(parse_uid_blacklist(
        &run_command(connection, "cmd netpolicy list restrict-background-blacklist").await?,
    )?);
    Ok(crate::snapshot::CurrentDeviceState {
        packages: package_states,
        user_packages: packages.user,
        secure_settings,
        global_settings,
        app_ops,
        restrict_background,
        metered_blacklist_uids,
    })
}

pub async fn execute_and_verify(
    ip: IpAddr,
    expected: &DeviceProfile,
    command: &crate::device_command::DeviceCommand,
    never_disable: &BTreeSet<String>,
) -> Result<String, String> {
    let mut connection = connect(ip).await?;
    let actual = read_profile(&mut connection).await?;
    if !same_device(&actual, expected) {
        return Err("the device at the selected IP no longer matches the inspected Fire TV; operation aborted".into());
    }
    execute_one(&mut connection, command, never_disable).await
}

async fn validate_original_snapshot_state(
    connection: &mut Connection<TokioTcp>,
    original: &DeviceSnapshot,
) -> Result<(), String> {
    original.validate()?;
    let packages = read_package_inventory(connection).await?;
    let current_packages: BTreeMap<String, PackageState> = packages.installed.iter().map(|package| {
        let state = if packages.disabled.contains(package) { PackageState::Disabled } else { PackageState::Enabled };
        (package.clone(), state)
    }).collect();
    if current_packages != original.packages || packages.user != original.user_packages {
        return Err("installed, user/system classification or enabled/disabled package state changed after the original snapshot; no cleanup operation was sent".into());
    }

    let secure = parse_settings_list(&run_command(connection, "settings list secure").await?)?;
    let global = parse_settings_list(&run_command(connection, "settings list global").await?)?;
    for (key, value) in &original.secure_settings {
        if secure.get(key).cloned().as_ref() != value.as_ref() {
            return Err(format!("secure setting {key} changed after the original snapshot; no cleanup operation was sent"));
        }
    }
    for (key, value) in &original.global_settings {
        if global.get(key).cloned().as_ref() != value.as_ref() {
            return Err(format!("global setting {key} changed after the original snapshot; no cleanup operation was sent"));
        }
    }
    for item in &original.app_ops {
        let command = format!("appops get {} {}", item.package, item.operation);
        let output = run_command(connection, &command).await?;
        if parse_appop_mode(&output, &item.operation)? != item.mode {
            return Err(format!("AppOps state for {} changed after the original snapshot; no cleanup operation was sent", item.package));
        }
    }
    let restrict_background = parse_restrict_background(
        &run_command(connection, "cmd netpolicy get restrict-background").await?,
    )?;
    if original.restrict_background != Some(restrict_background) {
        return Err("Data Saver state changed after the original snapshot; no cleanup operation was sent".into());
    }
    let blacklist = parse_uid_blacklist(
        &run_command(connection, "cmd netpolicy list restrict-background-blacklist").await?,
    )?;
    if original.metered_blacklist_uids.as_ref() != Some(&blacklist) {
        return Err("metered-data UID blacklist changed after the original snapshot; no cleanup operation was sent".into());
    }
    Ok(())
}

fn same_device(actual: &DeviceProfile, expected: &DeviceProfile) -> bool {
    actual.manufacturer == expected.manufacturer
        && actual.model == expected.model
        && actual.sdk == expected.sdk
        && actual.build_display_id == expected.build_display_id
}

async fn execute_one(
    connection: &mut Connection<TokioTcp>,
    command: &crate::device_command::DeviceCommand,
    never_disable: &BTreeSet<String>,
) -> Result<String, String> {
    use crate::device_command::DeviceCommand;

    if let DeviceCommand::ForceStopAndRestrictIfRunning { package, uid, sdk } = command {
        let shell_command = command.to_adb_shell(never_disable)?;
        let before = read_running_names(connection).await?;
        if !before.contains(package) {
            return Ok(format!("{package} was not running after disable; no additional restrictions were needed"));
        }
        let mut appop_names = vec!["RUN_IN_BACKGROUND"];
        if *sdk >= 28 { appop_names.push("RUN_ANY_IN_BACKGROUND"); }
        for operation in &appop_names {
            run_command(connection, &format!("appops set {package} {operation} ignore")).await?;
            let actual = run_command(connection, &format!("appops get {package} {operation}")).await?;
            if parse_appop_mode(&actual, operation)? != "ignore" {
                return Err(format!("verification failed: {operation} was not ignored for {package}"));
            }
        }
        // Keep the legacy script's ordering: apply AppOps, force-stop and
        // verify process exit, then add the UID to the metered-data blacklist.
        // A failed force-stop must not leave a package with only a partial
        // network-policy change that the journal might mistake for completion.
        run_command(connection, &shell_command).await?;
        let after = read_running_names(connection).await?;
        if after.contains(package) {
            return Err(format!("verification failed: {package} still has a running process after force-stop"));
        }
        if let Some(uid) = uid {
            let current = parse_uid_blacklist(
                &run_command(connection, "cmd netpolicy list restrict-background-blacklist").await?,
            )?;
            if !current.contains(uid) {
                run_command(connection, &format!("cmd netpolicy add restrict-background-blacklist {uid}")).await?;
            }
            let verified = parse_uid_blacklist(
                &run_command(connection, "cmd netpolicy list restrict-background-blacklist").await?,
            )?;
            if !verified.contains(uid) {
                return Err(format!("verification failed: metered-data UID {uid} was not blacklisted"));
            }
        }
        return Ok(format!("Restricted background execution and verified force-stop for {package}"));
    }
    if let DeviceCommand::ForceStopPackage(package) = command {
        let shell_command = command.to_adb_shell(never_disable)?;
        let before = read_running_names(connection).await?;
        if !before.contains(package) {
            return Ok(format!("{package} was not running; no force-stop was needed"));
        }
        let _command_output = run_command(connection, &shell_command).await?;
        let after = read_running_names(connection).await?;
        if after.contains(package) {
            return Err(format!("verification failed: {package} still has a running process after force-stop"));
        }
        return Ok(format!("Force-stopped and verified {package}"));
    }
    let shell_command = command.to_adb_shell(never_disable)?;
    let _command_output = run_command(connection, &shell_command).await?;

    match command {
        DeviceCommand::DisablePackage(package) => {
            let disabled = crate::package_inventory::parse_pm_list_output(
                &run_command(connection, "pm list packages -d").await?,
            )?;
            if !disabled.contains(package) { return Err(format!("verification failed: {package} is not reported disabled")); }
            Ok(format!("Disabled and verified {package}"))
        }
        DeviceCommand::EnablePackage(package) => {
            let all = crate::package_inventory::parse_pm_list_output(
                &run_command(connection, "pm list packages").await?,
            )?;
            let disabled = crate::package_inventory::parse_pm_list_output(
                &run_command(connection, "pm list packages -d").await?,
            )?;
            if !all.contains(package) || disabled.contains(package) {
                return Err(format!("verification failed: {package} is not installed and enabled"));
            }
            Ok(format!("Enabled and verified {package}"))
        }
        DeviceCommand::UninstallPackageForUser { package, .. } => {
            let all = crate::package_inventory::parse_pm_list_output(
                &run_command(connection, "pm list packages").await?,
            )?;
            if all.contains(package) { return Err(format!("verification failed: {package} is still installed for user 0")); }
            Ok(format!("Uninstalled and verified {package}; Store reinstall may be required to restore it"))
        }
        DeviceCommand::PutSetting { namespace, key, value } => {
            let command = format!("settings get {} {key}", namespace.as_str());
            let actual = run_command(connection, &command).await?;
            if actual.trim() != value { return Err(format!("verification failed for setting {key}")); }
            Ok(format!("Set and verified {} setting {key}", namespace.as_str()))
        }
        DeviceCommand::DeleteSetting { namespace, key } => {
            let command = format!("settings get {} {key}", namespace.as_str());
            let actual = run_command(connection, &command).await?;
            if !matches!(actual.trim(), "" | "null") { return Err(format!("verification failed: setting {key} still exists")); }
            Ok(format!("Deleted and verified {} setting {key}", namespace.as_str()))
        }
        DeviceCommand::SetAppOp { package, operation, mode } => {
            let command = format!("appops get {package} {operation}");
            let actual = run_command(connection, &command).await?;
            if parse_appop_mode(&actual, operation)? != *mode { return Err(format!("verification failed for AppOps {operation} on {package}")); }
            Ok(format!("Set and verified {operation}={mode} for {package}"))
        }
        DeviceCommand::SetRestrictBackground(enabled) => {
            let actual = parse_restrict_background(
                &run_command(connection, "cmd netpolicy get restrict-background").await?,
            )?;
            if actual != *enabled { return Err("verification failed for Data Saver state".into()); }
            Ok(format!("Data Saver verified {}", if *enabled { "on" } else { "off" }))
        }
        DeviceCommand::AddMeteredBlacklistUid(uid) | DeviceCommand::RemoveMeteredBlacklistUid(uid) => {
            let actual = parse_uid_blacklist(
                &run_command(connection, "cmd netpolicy list restrict-background-blacklist").await?,
            )?;
            let should_exist = matches!(command, DeviceCommand::AddMeteredBlacklistUid(_));
            if actual.contains(uid) != should_exist { return Err(format!("verification failed for metered-data UID policy {uid}")); }
            Ok(format!("Metered-data blacklist UID {uid} verified {}", if should_exist { "present" } else { "absent" }))
        }
        DeviceCommand::ForceStopPackage(_) | DeviceCommand::ForceStopAndRestrictIfRunning { .. } => unreachable!(),
    }
}

async fn read_running_names(connection: &mut Connection<TokioTcp>) -> Result<BTreeSet<String>, String> {
    let output = run_command(connection, "ps -A -o NAME").await?;
    if output.to_ascii_lowercase().contains("bad pid") {
        let fallback = run_command(connection, "ps").await?;
        parse_running_names(&fallback, true)
    } else {
        parse_running_names(&output, false)
    }
}

fn parse_running_names(output: &str, full_ps_columns: bool) -> Result<BTreeSet<String>, String> {
    if output.to_ascii_lowercase().contains("bad pid") {
        return Err("Fire OS did not return a usable process list; force-stop was not attempted".into());
    }
    let mut lines = output.lines();
    let header = lines.next().ok_or("Fire OS returned an empty process list; force-stop was not attempted")?;
    let valid_header = if full_ps_columns {
        header.split_whitespace().any(|column| column == "USER")
            && header.split_whitespace().any(|column| column == "PID")
            && header.split_whitespace().count() >= 2
    } else {
        header.trim() == "NAME"
    };
    if !valid_header {
        return Err("Fire OS returned an unrecognised process-list format; force-stop was not attempted".into());
    }
    let mut names = BTreeSet::new();
    for line in lines {
        let name = if full_ps_columns { line.split_whitespace().last() } else { line.split_whitespace().next() };
        if let Some(name) = name.filter(|name| !name.is_empty()) { names.insert(name.to_owned()); }
    }
    Ok(names)
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

fn parse_package_uid(output: &str) -> Option<u32> {
    for marker in ["userId=", "appId="] {
        let Some(index) = output.find(marker) else { continue; };
        let digits: String = output[index + marker.len()..]
            .chars()
            .take_while(|character| character.is_ascii_digit())
            .collect();
        if !digits.is_empty() {
            if let Ok(uid) = digits.parse::<u32>() { return Some(uid); }
        }
    }
    None
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
    if lower.contains("none") || lower.contains("empty") || lower.contains("no uids") || lower.contains("no uid entries") || trimmed.ends_with("[]") {
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
    fn staged_apk_path_is_confined_to_the_control_centre_temp_prefix() {
        assert!(valid_apk_staging_path("/data/local/tmp/dumbtv-launcher-1.13.apk"));
        assert!(valid_apk_staging_path("/data/local/tmp/dumbtv-smarttube-32.56s.apk"));
        assert!(!valid_apk_staging_path("/sdcard/Download/payload.apk"));
        assert!(!valid_apk_staging_path("/data/local/tmp/dumbtv-../payload.apk"));
        assert!(!valid_apk_staging_path("/data/local/tmp/dumbtv-run.sh"));
        assert!(!valid_apk_staging_path("/data/local/tmp/dumbtv-foo.apk; reboot"));
    }

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
    fn parses_running_process_names_with_api25_fallback_columns() {
        let modern = "NAME\ncom.amazon.foo\ncom.amazon.bar\n";
        assert_eq!(parse_running_names(modern, false).unwrap(), [
            "com.amazon.bar".to_owned(), "com.amazon.foo".to_owned()
        ].into_iter().collect());
        let legacy = "USER PID PPID VSIZE RSS WCHAN PC NAME\nsystem 1 0 0 0 0 0 init\nu0_a12 42 1 0 0 0 0 com.amazon.foo\n";
        assert!(parse_running_names(legacy, true).unwrap().contains("com.amazon.foo"));
        assert!(parse_running_names("bad pid", false).is_err());
        assert!(parse_running_names("usage: ps", false).is_err());
    }

    #[test]
    fn extracts_package_uid_from_legacy_and_modern_dumpsys_fields() {
        assert_eq!(parse_package_uid("  userId=10042\n"), Some(10042));
        assert_eq!(parse_package_uid("  appId=10043\n"), Some(10043));
        assert_eq!(parse_package_uid("no uid reported"), None);
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
