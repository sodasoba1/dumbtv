mod adb_auth;
#[cfg(windows)]
mod adb_transport;
mod apk_release;
mod catalogue;
#[cfg(windows)]
mod cng_auth;
mod device_command;
mod device_profile;
mod home_config;
mod operation_journal;
mod operations;
mod package_inventory;
mod package_plan;
mod settings_catalogue;
mod snapshot;

use atomicwrites::{AllowOverwrite, AtomicFile};
use eframe::egui;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::{
    fs,
    io::{Read, Write},
    path::{Component, Path, PathBuf},
    sync::{Arc, Mutex},
    thread,
    time::Duration,
};

const OWNER: &str = "sodasoba1";
const REPOSITORY: &str = "dumbtv";
const MAX_ARCHIVE_BYTES: u64 = 512 * 1024 * 1024;
const MAX_EXTRACTED_BYTES: u64 = 1024 * 1024 * 1024;
const MAX_ARCHIVE_FILES: usize = 100_000;

#[derive(Debug, Deserialize)]
struct GitHubCommit {
    sha: String,
}

#[derive(Debug, Serialize, Deserialize)]
struct ActiveSource {
    repository: String,
    commit: String,
}

#[derive(Default)]
struct SharedState {
    status: String,
    latest_commit: Option<String>,
    active_commit: Option<String>,
    busy: bool,
    host_key_md5: Option<String>,
    host_key_sha256: Option<String>,
    device_profile: Option<device_profile::DeviceProfile>,
    package_inventory: Option<package_inventory::PackageInventory>,
    protected_uids: std::collections::BTreeMap<String, u32>,
    connected_ip: Option<std::net::IpAddr>,
    snapshot_path: Option<PathBuf>,
    restore_plan: Option<snapshot::RestorePlan>,
    restore_current: Option<snapshot::CurrentDeviceState>,
}

struct ControlCentre {
    shared: Arc<Mutex<SharedState>>,
    recovery_journals: Vec<String>,
    device_ip: String,
    keep_alexa: bool,
    reboot_after_cleanup: bool,
    remove_packages: String,
    keep_packages: String,
    cleanup_plan: Option<operations::CleanupPlan>,
    plan_data_root: Option<PathBuf>,
    plan_snapshot_path: Option<PathBuf>,
    confirm_apply: bool,
    confirm_restore: bool,
    replace_youtube: bool,
    install_launcher_after_cleanup: bool,
}

impl Default for ControlCentre {
    fn default() -> Self {
        let active_commit = load_active_source().ok().flatten().map(|m| m.commit);
        let recovery_journals = load_unfinished_journals();
        let app = Self {
            recovery_journals,
            shared: Arc::new(Mutex::new(SharedState {
                status: "Checking the official repository for updates…".into(),
                latest_commit: None,
                active_commit,
                busy: true,
                host_key_md5: None,
                host_key_sha256: None,
                device_profile: None,
                package_inventory: None,
                protected_uids: std::collections::BTreeMap::new(),
                connected_ip: None,
                snapshot_path: None,
                restore_plan: None,
                restore_current: None,
            })),
            device_ip: String::new(),
            keep_alexa: true,
            reboot_after_cleanup: false,
            remove_packages: String::new(),
            keep_packages: String::new(),
            cleanup_plan: None,
            plan_data_root: None,
            plan_snapshot_path: None,
            confirm_apply: false,
            confirm_restore: false,
            replace_youtube: false,
            install_launcher_after_cleanup: true,
        };
        app.start_initial_update_check();
        app
    }
}

impl ControlCentre {
    fn start_task(&self, task: impl FnOnce() -> Result<String, String> + Send + 'static) {
        let shared = Arc::clone(&self.shared);
        {
            let mut state = shared.lock().expect("state lock poisoned");
            if state.busy {
                return;
            }
            state.busy = true;
            state.status = "Working…".into();
        }
        thread::spawn(move || {
            let result = task();
            let active = load_active_source()
                .ok()
                .flatten()
                .map(|manifest| manifest.commit);
            let latest = check_latest_commit().ok().flatten();
            let mut state = shared.lock().expect("state lock poisoned");
            state.status = match result {
                Ok(message) => message,
                Err(error) => format!("Error: {error}"),
            };
            state.busy = false;
            if active.is_some() {
                state.active_commit = active;
            }
            if latest.is_some() {
                state.latest_commit = latest;
            }
        });
    }
}

impl ControlCentre {
    fn start_initial_update_check(&self) {
        let shared = Arc::clone(&self.shared);
        thread::spawn(move || {
            let latest_result = check_latest_commit();
            let mut state = shared.lock().expect("state lock poisoned");
            match latest_result {
                Ok(Some(latest)) => {
                    state.latest_commit = Some(latest.clone());
                    state.status = match state.active_commit.as_deref() {
                        Some(active) if active == latest => {
                            format!("Official source is up to date ({})", short_sha(&latest))
                        }
                        Some(_) => format!(
                            "An official source update is available ({})",
                            short_sha(&latest)
                        ),
                        None => format!(
                            "Official source is available ({}) and has not yet been downloaded",
                            short_sha(&latest)
                        ),
                    };
                }
                Ok(None) => {
                    state.status = "GitHub returned no main-branch commit.".into();
                }
                Err(error) => {
                    state.status = format!(
                        "Automatic update check failed: {error}. You can retry from Project updates."
                    );
                }
            }
            state.busy = false;
        });
    }
}

impl ControlCentre {
    #[cfg(windows)]
    fn install_launcher_on_device(
        &self,
        ip: std::net::IpAddr,
        profile: device_profile::DeviceProfile,
    ) {
        self.start_task(move || {
            let (bytes, version_code) = verified_launcher_apk()?;
            let runtime = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .map_err(|error| format!("cannot start ADB runtime: {error}"))?;
            let backup = launcher_backup_path(&profile)?;
            runtime.block_on(adb_transport::install_launcher(
                ip,
                &profile,
                &bytes,
                version_code,
                &backup,
            ))
        });
    }

    #[cfg(windows)]
    fn undo_launcher_on_device(
        &self,
        ip: std::net::IpAddr,
        profile: device_profile::DeviceProfile,
    ) {
        self.start_task(move || {
            let runtime = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .map_err(|error| format!("cannot start ADB runtime: {error}"))?;
            let backup = launcher_backup_path(&profile)?;
            runtime.block_on(adb_transport::undo_launcher(ip, &profile, &backup))
        });
    }

    #[cfg(windows)]
    fn install_smarttube_on_device(
        &self,
        ip: std::net::IpAddr,
        profile: device_profile::DeviceProfile,
    ) {
        self.start_task(move || {
            let bytes = verified_smarttube_apk()?;
            let backup = smarttube_backup_path(&profile)?;
            let runtime = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .map_err(|error| format!("cannot start ADB runtime: {error}"))?;
            runtime.block_on(adb_transport::install_smarttube(
                ip, &profile, &bytes, &backup,
            ))
        });
    }

    #[cfg(windows)]
    fn undo_smarttube_on_device(
        &self,
        ip: std::net::IpAddr,
        profile: device_profile::DeviceProfile,
    ) {
        self.start_task(move || {
            let backup = smarttube_backup_path(&profile)?;
            let runtime = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .map_err(|error| format!("cannot start ADB runtime: {error}"))?;
            runtime.block_on(adb_transport::undo_smarttube(ip, &profile, &backup))
        });
    }

    #[cfg(windows)]
    fn prepare_adb_key(&self) {
        let shared = Arc::clone(&self.shared);
        {
            let mut state = shared.lock().expect("state lock poisoned");
            if state.busy {
                return;
            }
            state.busy = true;
            state.host_key_md5 = None;
            state.host_key_sha256 = None;
            state.status = "Preparing the Windows-protected ADB host key…".into();
        }
        thread::spawn(move || {
            let result = cng_auth::WindowsCngAdbKey::open_or_create().map(|key| {
                (
                    key.public_key_fingerprint_md5(),
                    key.public_key_fingerprint_sha256(),
                )
            });
            let mut state = shared.lock().expect("state lock poisoned");
            match result {
                Ok((md5, sha256)) => {
                    state.host_key_md5 = Some(md5);
                    state.host_key_sha256 = Some(sha256);
                    state.status = "ADB host key is ready. Compare its MD5 fingerprint with the TV prompt before approving.".into();
                }
                Err(error) => state.status = format!("ADB key preparation failed: {error}"),
            }
            state.busy = false;
        });
    }

    #[cfg(windows)]
    fn inspect_fire_tv(&self, ip: std::net::IpAddr) {
        let shared = Arc::clone(&self.shared);
        {
            let mut state = shared.lock().expect("state lock poisoned");
            if state.busy {
                return;
            }
            state.busy = true;
            state.device_profile = None;
            state.package_inventory = None;
            state.protected_uids.clear();
            state.connected_ip = None;
            state.snapshot_path = None;
            state.restore_plan = None;
            state.restore_current = None;
            state.status = "Connecting to the specified Fire TV. Check the TV before approving its ADB key prompt…".into();
        }
        thread::spawn(move || {
            let result = (|| -> Result<adb_transport::DeviceInventory, String> {
                let root = app_root()?;
                let saved_snapshot_path = root.join("snapshots").join("original-state.json");
                let source_commit = if saved_snapshot_path.is_file() {
                    snapshot::DeviceSnapshot::load(&saved_snapshot_path)?.source_commit
                } else {
                    load_active_source()?
                        .ok_or("download the official source before connecting to a Fire TV")?
                        .commit
                };
                let source_root = root.join("source-versions").join(&source_commit);
                validate_source_tree(&source_root)?;
                let data_root = source_root.join("data");
                let runtime = tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
                    .map_err(|error| format!("cannot start the ADB runtime: {error}"))?;
                runtime.block_on(adb_transport::inspect_device(ip, &data_root))
            })();
            let mut state = shared.lock().expect("state lock poisoned");
            match result {
                Ok(inventory) => {
                    state.status = if inventory.packages.is_some() {
                        "Connected. Device properties and package inventory read successfully; no package or settings changes were made.".into()
                    } else {
                        "Connected. Device properties read; package inventory was skipped because this device is outside the validated cleanup support range.".into()
                    };
                    state.connected_ip = Some(ip);
                    state.device_profile = Some(inventory.profile);
                    state.package_inventory = inventory.packages;
                    state.protected_uids = inventory.protected_uids;
                    let saved_snapshot = app_root()
                        .ok()
                        .map(|root| root.join("snapshots").join("original-state.json"));
                    if let Some(path) = saved_snapshot.filter(|path| path.is_file()) {
                        state.snapshot_path = Some(path);
                    }
                }
                Err(error) => {
                    state.protected_uids.clear();
                    state.status = format!("ADB inspection failed: {error}");
                }
            }
            state.busy = false;
        });
    }

    #[cfg(windows)]
    fn capture_original_state(&self, ip: std::net::IpAddr) {
        let shared = Arc::clone(&self.shared);
        let expected_profile = {
            let mut state = shared.lock().expect("state lock poisoned");
            if state.busy || state.connected_ip != Some(ip) || state.package_inventory.is_none() {
                return;
            }
            let Some(profile) = state.device_profile.clone() else {
                return;
            };
            state.busy = true;
            state.status = "Capturing original packages, settings, AppOps and network policy. No device changes will be made…".into();
            profile
        };
        thread::spawn(move || {
            let result = (|| -> Result<(PathBuf, Option<adb_transport::DeviceInventory>, Option<String>), String> {
                let manifest = load_active_source()?.ok_or("download the official source before capturing a snapshot")?;
                let data_root = app_root()?.join("source-versions").join(&manifest.commit).join("data");
                let snapshots = app_root()?.join("snapshots");
                fs::create_dir_all(&snapshots).map_err(|error| format!("cannot create snapshot folder: {error}"))?;
                let path = snapshots.join("original-state.json");
                let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build()
                    .map_err(|error| format!("cannot start the ADB runtime: {error}"))?;
                runtime.block_on(adb_transport::capture_original_snapshot(ip, &expected_profile, &data_root, &manifest.commit, &path))?;
                let inventory_result = runtime.block_on(adb_transport::inspect_device(ip, &data_root));
                match inventory_result {
                    Ok(inventory)
                        if inventory.profile.manufacturer == expected_profile.manufacturer
                            && inventory.profile.serial_fingerprint == expected_profile.serial_fingerprint
                            && inventory.profile.model == expected_profile.model
                            && inventory.profile.sdk == expected_profile.sdk
                            && inventory.profile.build_display_id == expected_profile.build_display_id =>
                    {
                        Ok((path, Some(inventory), None))
                    }
                    Ok(_) => Ok((path, None, Some("device changed during snapshot capture; reconnect before continuing".to_owned()))),
                    Err(error) => Ok((path, None, Some(format!("snapshot saved, but inventory refresh failed: {error}")))),
                }
            })();
            let mut state = shared.lock().expect("state lock poisoned");
            match result {
                Ok((path, Some(inventory), None)) => {
                    state.status = format!("Original state captured and saved locally as {}. No TV settings or packages were changed.", path.file_name().and_then(|name| name.to_str()).unwrap_or("snapshot.json"));
                    state.snapshot_path = Some(path);
                    state.package_inventory = inventory.packages;
                    state.protected_uids = inventory.protected_uids;
                }
                Ok((path, None, Some(reason))) => {
                    state.snapshot_path = Some(path);
                    state.connected_ip = None;
                    state.device_profile = None;
                    state.package_inventory = None;
                    state.protected_uids.clear();
                    state.status = format!("Original snapshot was saved, but the device preflight must be repeated: {reason}");
                }
                Ok((path, Some(_inventory), Some(reason))) => {
                    state.snapshot_path = Some(path);
                    state.connected_ip = None;
                    state.device_profile = None;
                    state.package_inventory = None;
                    state.protected_uids.clear();
                    state.status = format!("Original snapshot was saved, but the device preflight must be repeated: {reason}");
                }
                Ok((path, None, None)) => {
                    state.snapshot_path = Some(path);
                    state.status =
                        "Original snapshot was saved; reconnect before planning cleanup.".into();
                }
                Err(error) => {
                    state.status = format!(
                        "Original-state capture failed; no device changes were made: {error}"
                    )
                }
            }
            state.busy = false;
        });
    }

    fn prepare_cleanup_plan(
        &mut self,
        profile: &device_profile::DeviceProfile,
        inventory: &package_inventory::PackageInventory,
        protected_uids: &std::collections::BTreeMap<String, u32>,
        snapshot_path: &Path,
    ) -> Result<(), String> {
        let original = snapshot::DeviceSnapshot::load(snapshot_path)?;
        let source_root = app_root()?
            .join("source-versions")
            .join(&original.source_commit);
        validate_source_tree(&source_root)?;
        let data_root = source_root.join("data");
        if original.manufacturer != profile.manufacturer
            || original.device_fingerprint != profile.serial_fingerprint
            || original.model != profile.model
            || original.build_display_id != profile.build_display_id
            || original.android_sdk != profile.sdk
        {
            return Err(
                "the saved original snapshot belongs to a different device; refusing to use it"
                    .into(),
            );
        }
        if original.restrict_background.is_none() || original.metered_blacklist_uids.is_none() {
            return Err("the saved snapshot is missing Data Saver or metered-data policy state; refusing to apply cleanup".into());
        }
        let original_packages: std::collections::BTreeSet<String> =
            original.packages.keys().cloned().collect();
        if original_packages != inventory.installed || original.user_packages != inventory.user {
            return Err("the installed package inventory changed after the original snapshot; reconnect and capture a fresh baseline before planning".into());
        }
        for (package, original_state) in &original.packages {
            let current_state = if inventory.disabled.contains(package) {
                snapshot::PackageState::Disabled
            } else {
                snapshot::PackageState::Enabled
            };
            if current_state != *original_state {
                return Err(format!(
                    "package state changed after the original snapshot: {package}"
                ));
            }
        }
        let choices = package_plan::PackageChoices {
            keep_alexa: self.keep_alexa,
            keep: self
                .keep_packages
                .split_whitespace()
                .map(str::to_owned)
                .collect(),
            remove: self
                .remove_packages
                .split_whitespace()
                .map(str::to_owned)
                .collect(),
        };
        let plan = operations::build_cleanup_plan(
            &data_root,
            profile.sdk,
            inventory,
            &choices,
            protected_uids,
            &original,
        )?;
        self.plan_data_root = Some(data_root);
        self.plan_snapshot_path = Some(snapshot_path.to_path_buf());
        self.cleanup_plan = Some(plan);
        Ok(())
    }

    #[cfg(windows)]
    fn apply_cleanup_plan(
        &mut self,
        ip: std::net::IpAddr,
        profile: device_profile::DeviceProfile,
        reboot_after_cleanup: bool,
        install_launcher_after: bool,
        install_smarttube_after: bool,
    ) {
        let Some(mut plan) = self.cleanup_plan.clone() else {
            return;
        };
        let Some(data_root) = self.plan_data_root.clone() else {
            return;
        };
        let Some(snapshot_path) = self.plan_snapshot_path.clone() else {
            return;
        };
        let original = match snapshot::DeviceSnapshot::load(&snapshot_path) {
            Ok(snapshot) => snapshot,
            Err(error) => {
                let mut state = self.shared.lock().expect("state lock poisoned");
                state.status = format!("Cannot apply cleanup: {error}");
                return;
            }
        };
        if original.manufacturer != profile.manufacturer
            || original.device_fingerprint != profile.serial_fingerprint
            || original.model != profile.model
            || original.build_display_id != profile.build_display_id
            || original.android_sdk != profile.sdk
        {
            let mut state = self.shared.lock().expect("state lock poisoned");
            state.status =
                "Cannot apply cleanup: the saved original snapshot belongs to a different device."
                    .into();
            return;
        }
        let catalogue = match catalogue::FireOsCatalogue::load(&data_root, profile.sdk) {
            Ok(catalogue) => catalogue,
            Err(error) => {
                let mut state = self.shared.lock().expect("state lock poisoned");
                state.status = format!("Cannot load the protected-package policy: {error}");
                return;
            }
        };
        let never_disable: std::collections::BTreeSet<String> =
            catalogue.never_disable.into_iter().collect();
        // This flag is set only after the owner presses the final Apply button.
        for operation in &mut plan.operations {
            if let device_command::DeviceCommand::UninstallPackageForUser {
                owner_confirmed, ..
            } = &mut operation.command
            {
                *owner_confirmed = true;
            }
        }
        self.cleanup_plan = None;
        self.plan_data_root = None;
        self.plan_snapshot_path = None;
        self.confirm_apply = false;
        let journal_root = match app_root() {
            Ok(root) => root.join("journals"),
            Err(error) => {
                self.shared.lock().expect("state lock poisoned").status = error;
                return;
            }
        };
        let journal_operations: Vec<(String, device_command::DeviceCommand)> = plan
            .operations
            .iter()
            .map(|item| (item.description.clone(), item.command.clone()))
            .collect();
        let journal_path = match operation_journal::OperationJournal::create_with_commands(
            &journal_root,
            operation_journal::WorkflowKind::Cleanup,
            &profile.manufacturer,
            &profile.model,
            &profile.build_display_id,
            profile.sdk,
            &original.source_commit,
            &journal_operations,
        ) {
            Ok(path) => path,
            Err(error) => {
                self.shared.lock().expect("state lock poisoned").status =
                    format!("Cannot start cleanup safely: {error}");
                return;
            }
        };
        let journal_name = journal_path
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("cleanup journal")
            .to_owned();
        let shared = Arc::clone(&self.shared);
        {
            let mut state = shared.lock().expect("state lock poisoned");
            if state.busy {
                return;
            }
            state.busy = true;
            state.status = format!("Applying {} reviewed operations. The sequence stops on the first failed verification…", plan.operations.len());
        }
        thread::spawn(move || {
            let result = (|| -> Result<(adb_transport::ExecutionReport, bool, Vec<String>, Option<String>), String> {
                let runtime = tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
                    .map_err(|error| format!("cannot start the ADB runtime: {error}"))?;
                let report = runtime.block_on(adb_transport::execute_plan(
                    ip,
                    &profile,
                    &original,
                    &plan.operations,
                    &never_disable,
                    &journal_path,
                ))?;
                let rebooted = if report.failure.is_none() && reboot_after_cleanup {
                    let journal = operation_journal::OperationJournal::load(&journal_path)?;
                    runtime.block_on(adb_transport::reboot_and_reconnect(
                        ip,
                        &profile,
                        &plan.operations,
                        &journal,
                    ))?;
                    true
                } else {
                    false
                };
                let mut installed = Vec::new();
                let mut install_error = None;
                if report.failure.is_none() && install_launcher_after {
                    match (|| -> Result<String, String> {
                        let (bytes, version_code) = verified_launcher_apk()?;
                        let backup = launcher_backup_path(&profile)?;
                        runtime.block_on(adb_transport::install_launcher(ip, &profile, &bytes, version_code, &backup))
                    })() {
                        Ok(message) => installed.push(message),
                        Err(error) => install_error = Some(format!("DumbTV HOME installation failed after cleanup: {error}")),
                    }
                }
                if report.failure.is_none() && install_error.is_none() && install_smarttube_after {
                    match (|| -> Result<String, String> {
                        let bytes = verified_smarttube_apk()?;
                        let backup = smarttube_backup_path(&profile)?;
                        runtime.block_on(adb_transport::install_smarttube(ip, &profile, &bytes, &backup))
                    })() {
                        Ok(message) => installed.push(message),
                        Err(error) => install_error = Some(format!("SmartTube installation failed after cleanup: {error}")),
                    }
                }
                Ok((report, rebooted, installed, install_error))
            })();
            let mut state = shared.lock().expect("state lock poisoned");
            state.status = match result {
                Ok((report, rebooted, installed, install_error)) => match report.failure {
                    Some(failure) => format!("Stopped after {} verified cleanup operations. {failure}. Journal: {journal_name}. The original snapshot remains available for restore.", report.completed.len()),
                    None => {
                        let cleanup = if rebooted {
                            format!("All {} reviewed cleanup operations passed verification; the Fire TV rebooted and reconnected as the same device. Journal: {journal_name}.", report.completed.len())
                        } else {
                            format!("All {} reviewed cleanup operations passed verification. Journal: {journal_name}.", report.completed.len())
                        };
                        let follow_up = if installed.is_empty() { String::new() } else { format!(" {}", installed.join(" ")) };
                        match install_error {
                            Some(error) => format!("{cleanup} {error}. Use the available undo action if a partial installer change needs reversing."),
                            None => format!("{cleanup}{follow_up}"),
                        }
                    }
                },
                Err(error) => format!("Cleanup or its requested post-change verification/reconnect reported an error: {error}. Journal: {journal_name}. Inspect the TV before attempting another operation."),
            };
            state.busy = false;
            state.connected_ip = None;
            state.device_profile = None;
            state.package_inventory = None;
            state.protected_uids.clear();
            state.restore_plan = None;
            state.restore_current = None;
        });
    }

    #[cfg(windows)]
    fn verify_last_cleanup(
        &self,
        ip: std::net::IpAddr,
        profile: device_profile::DeviceProfile,
        snapshot_path: PathBuf,
    ) {
        let shared = Arc::clone(&self.shared);
        {
            let mut state = shared.lock().expect("state lock poisoned");
            if state.busy {
                return;
            }
            state.busy = true;
            state.status = "Re-reading all verified cleanup results from the Fire TV. No changes will be made…".into();
        }
        thread::spawn(move || {
            let result = (|| -> Result<usize, String> {
                let original = snapshot::DeviceSnapshot::load(&snapshot_path)?;
                if original.manufacturer != profile.manufacturer
                    || original.device_fingerprint != profile.serial_fingerprint
                    || original.model != profile.model
                    || original.build_display_id != profile.build_display_id
                    || original.android_sdk != profile.sdk
                {
                    return Err("the original snapshot belongs to a different device".into());
                }
                let journal = latest_cleanup_journal(&profile, &original.source_commit)?
                    .ok_or("no matching cleanup journal is available")?;
                let runtime = tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
                    .map_err(|error| format!("cannot start the ADB runtime: {error}"))?;
                runtime.block_on(adb_transport::verify_cleanup_journal(
                    ip, &profile, &journal,
                ))
            })();
            let mut state = shared.lock().expect("state lock poisoned");
            state.status = match result {
                Ok(count) => format!("Read-only verification passed for all {count} verified cleanup operations on the matching Fire TV."),
                Err(error) => format!("Read-only cleanup verification failed: {error}. Inspect the TV and review restore; no changes were sent."),
            };
            state.busy = false;
        });
    }

    #[cfg(windows)]
    fn review_restore_plan(
        &self,
        ip: std::net::IpAddr,
        profile: device_profile::DeviceProfile,
        snapshot_path: PathBuf,
    ) {
        let shared = Arc::clone(&self.shared);
        {
            let mut state = shared.lock().expect("state lock poisoned");
            if state.busy {
                return;
            }
            state.busy = true;
            state.restore_plan = None;
            state.restore_current = None;
            state.status = "Reading current device state and calculating the restore delta…".into();
        }
        thread::spawn(move || {
            let result = (|| -> Result<(snapshot::RestorePlan, snapshot::CurrentDeviceState, usize), String> {
                let original = snapshot::DeviceSnapshot::load(&snapshot_path)?;
                if original.manufacturer != profile.manufacturer || original.device_fingerprint != profile.serial_fingerprint || original.model != profile.model || original.build_display_id != profile.build_display_id || original.android_sdk != profile.sdk {
                    return Err("the saved original snapshot belongs to a different device".into());
                }
                let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build()
                    .map_err(|error| format!("cannot start the ADB runtime: {error}"))?;
                let current = runtime.block_on(adb_transport::read_current_device_state(ip, &profile, &original))?;
                let full_plan = snapshot::build_restore_plan(&original, &current)?;
                let journal = latest_cleanup_journal(&profile, &original.source_commit)?.ok_or(
                    "no cleanup journal is available; refusing to restore arbitrary differences from the original snapshot"
                )?;
                if journal.manufacturer != profile.manufacturer
                    || journal.model != profile.model
                    || journal.build_display_id != profile.build_display_id
                    || journal.android_sdk != profile.sdk
                    || journal.source_commit != original.source_commit
                {
                    return Err("the latest cleanup journal does not match this device and original snapshot".into());
                }
                let (plan, excluded) = operations::scope_restore_to_verified_cleanup(&full_plan, &journal, &current);
                Ok((plan, current, excluded))
            })();
            let mut state = shared.lock().expect("state lock poisoned");
            match result {
                Ok((plan, current, excluded)) => {
                    let (commands, manual) = operations::build_restore_operations(&plan);
                    state.status = format!("Selective restore plan ready: {} automatic operations and {} manual items. {excluded} baseline differences were not attributed to verified DumbTV changes and will be left untouched. Nothing has been changed.", commands.len(), manual.len());
                    state.restore_plan = Some(plan);
                    state.restore_current = Some(current);
                }
                Err(error) => state.status = format!("Cannot prepare restore plan: {error}"),
            }
            state.busy = false;
        });
    }

    #[cfg(windows)]
    fn apply_restore_plan(&self, ip: std::net::IpAddr, profile: device_profile::DeviceProfile) {
        let (Some(plan), Some(reviewed_current)) = ({
            let state = self.shared.lock().expect("state lock poisoned");
            (state.restore_plan.clone(), state.restore_current.clone())
        }) else {
            return;
        };
        let snapshot_path = match app_root() {
            Ok(root) => root.join("snapshots").join("original-state.json"),
            Err(error) => {
                let mut state = self.shared.lock().expect("state lock poisoned");
                state.status = error;
                return;
            }
        };
        let original = match snapshot::DeviceSnapshot::load(&snapshot_path) {
            Ok(snapshot) => snapshot,
            Err(error) => {
                let mut state = self.shared.lock().expect("state lock poisoned");
                state.status = format!("Cannot load original snapshot: {error}");
                return;
            }
        };
        if original.manufacturer != profile.manufacturer
            || original.model != profile.model
            || original.build_display_id != profile.build_display_id
            || original.android_sdk != profile.sdk
        {
            let mut state = self.shared.lock().expect("state lock poisoned");
            state.status =
                "Cannot restore: the original snapshot belongs to a different device.".into();
            return;
        }
        let source_root = match app_root() {
            Ok(root) => root.join("source-versions").join(&original.source_commit),
            Err(error) => {
                let mut state = self.shared.lock().expect("state lock poisoned");
                state.status = error;
                return;
            }
        };
        if let Err(error) = validate_source_tree(&source_root) {
            let mut state = self.shared.lock().expect("state lock poisoned");
            state.status = format!("Cannot load the pinned source policy: {error}");
            return;
        }
        let catalogue =
            match catalogue::FireOsCatalogue::load(&source_root.join("data"), profile.sdk) {
                Ok(catalogue) => catalogue,
                Err(error) => {
                    let mut state = self.shared.lock().expect("state lock poisoned");
                    state.status = format!("Cannot load protected-package policy: {error}");
                    return;
                }
            };
        let never_disable: std::collections::BTreeSet<String> =
            catalogue.never_disable.into_iter().collect();
        let (operations, manual) = operations::build_restore_operations(&plan);
        if operations.is_empty() {
            let mut state = self.shared.lock().expect("state lock poisoned");
            state.status = format!(
                "No automatic restore operations are available. Manual actions: {}",
                manual.join("; ")
            );
            return;
        }
        let journal_root = match app_root() {
            Ok(root) => root.join("journals"),
            Err(error) => {
                self.shared.lock().expect("state lock poisoned").status = error;
                return;
            }
        };
        let journal_operations: Vec<(String, device_command::DeviceCommand)> = operations
            .iter()
            .map(|item| (item.description.clone(), item.command.clone()))
            .collect();
        let journal_path = match operation_journal::OperationJournal::create_with_commands(
            &journal_root,
            operation_journal::WorkflowKind::Restore,
            &profile.manufacturer,
            &profile.model,
            &profile.build_display_id,
            profile.sdk,
            &original.source_commit,
            &journal_operations,
        ) {
            Ok(path) => path,
            Err(error) => {
                self.shared.lock().expect("state lock poisoned").status =
                    format!("Cannot start restore safely: {error}");
                return;
            }
        };
        let journal_name = journal_path
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("restore journal")
            .to_owned();
        let shared = Arc::clone(&self.shared);
        {
            let mut state = shared.lock().expect("state lock poisoned");
            if state.busy {
                return;
            }
            state.busy = true;
            state.status = format!(
                "Restoring {} reviewed items. The sequence stops on the first failed verification…",
                operations.len()
            );
        }
        thread::spawn(move || {
            let result = (|| -> Result<adb_transport::ExecutionReport, String> {
                let runtime = tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
                    .map_err(|error| format!("cannot start the ADB runtime: {error}"))?;
                runtime.block_on(adb_transport::execute_restore_plan(
                    ip,
                    &profile,
                    &original,
                    &reviewed_current,
                    &operations,
                    &never_disable,
                    &journal_path,
                ))
            })();
            let mut state = shared.lock().expect("state lock poisoned");
            state.status = match result {
                Ok(report) => {
                    let manual_text = if manual.is_empty() {
                        String::new()
                    } else {
                        format!(" Manual actions still required: {}.", manual.join("; "))
                    };
                    match report.failure {
                        Some(failure) => format!("Restore stopped after {} verified operations. {failure}.{manual_text} Journal: {journal_name}.", report.completed.len()),
                        None => format!("{} restore operations completed and passed read-back verification.{manual_text} Journal: {journal_name}. Reconnect and review the device state again.", report.completed.len()),
                    }
                }
                Err(error) => format!(
                    "Restore aborted before or during execution: {error}. Journal: {journal_name}."
                ),
            };
            state.busy = false;
            state.connected_ip = None;
            state.device_profile = None;
            state.package_inventory = None;
            state.protected_uids.clear();
            state.restore_plan = None;
            state.restore_current = None;
        });
    }
}

impl eframe::App for ControlCentre {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        ctx.request_repaint_after(Duration::from_millis(250));
        egui::CentralPanel::default().show(ctx, |ui| {
            egui::ScrollArea::vertical().show(ui, |ui| {
            ui.heading("DumbTV Windows Installer");
            ui.label("Portable Windows frontend · AFTMM / Fire OS 6 project");
            if !self.recovery_journals.is_empty() {
                ui.separator();
                ui.heading("Operation journals requiring review");
                ui.label("Running/pending steps may have been interrupted; failed steps may have partially changed the TV. Reconnect to the recorded device and inspect live state. Only verified steps whose live values still match the recorded cleanup target can be restored automatically. No step is replayed.");
                if ui.button("Refresh recovery records").clicked() {
                    self.recovery_journals = load_unfinished_journals();
                }
                for summary in &self.recovery_journals { ui.small(summary); }
            }
            ui.separator();

            let (busy, status, latest, active, host_key_md5, host_key_sha256, device_profile, package_inventory, protected_uids, connected_ip, snapshot_path, restore_plan) = {
                let state = self.shared.lock().expect("state lock poisoned");
                (
                    state.busy,
                    state.status.clone(),
                    state.latest_commit.clone(),
                    state.active_commit.clone(),
                    state.host_key_md5.clone(),
                    state.host_key_sha256.clone(),
                    state.device_profile.clone(),
                    state.package_inventory.clone(),
                    state.protected_uids.clone(),
                    state.connected_ip,
                    state.snapshot_path.clone(),
                    state.restore_plan.clone(),
                )
            };

            let package_counts = package_inventory.as_ref().map(|packages| (
                packages.installed.len(), packages.system.len(), packages.user.len(), packages.disabled.len()
            ));

            ui.heading("Project updates");
            ui.label(format!("Official source: {OWNER}/{REPOSITORY} · main"));
            ui.horizontal(|ui| {
                if ui.add_enabled(!busy, egui::Button::new("Check for updates")).clicked() {
                    self.start_task(|| {
                        let latest = check_latest_commit()?.ok_or("GitHub returned no commit")?;
                        Ok(format!("Latest official main commit: {}", short_sha(&latest)))
                    });
                }
                if ui.add_enabled(!busy, egui::Button::new("Download latest source")).clicked() {
                    self.start_task(|| download_latest_source());
                }
            });
            let update_state = match (active.as_deref(), latest.as_deref()) {
                (Some(local), Some(remote)) if local == remote => "Up to date",
                (Some(_), Some(_)) => "Update available",
                (None, Some(_)) => "No managed source downloaded yet",
                (_, None) => "Check for updates to compare revisions",
            };
            ui.label(format!("Source status: {update_state}"));
            ui.label(format!("Downloaded source: {}", active.as_deref().map(short_sha).unwrap_or("Not downloaded")));
            ui.label(format!("Latest checked commit: {}", latest.as_deref().map(short_sha).unwrap_or("Not checked")));
            ui.label("Source updates are extracted into a versioned managed folder. Existing folders are never overwritten, and downloaded scripts are not run.");
            ui.separator();

            ui.heading("Verified APK assets");
            ui.label("Download and verify the existing published APK releases. Installation is a separate, explicit device action.");
            ui.horizontal(|ui| {
                if ui.add_enabled(!busy && active.is_some(), egui::Button::new("Prepare API 25 DumbTV HOME APK")).clicked() {
                    self.start_task(download_launcher_apk);
                }
                if ui.add_enabled(!busy, egui::Button::new("Prepare optional SmartTube APK")).clicked() {
                    self.start_task(download_smarttube_apk);
                }
            });
            ui.separator();

            ui.heading("Fire TV connection");
            ui.label("Read-only inspection over legacy network ADB. Enable ADB Debugging on the Stick and find its IP address in Network settings.");
            ui.horizontal(|ui| {
                ui.label("Fire TV IP address:");
                ui.text_edit_singleline(&mut self.device_ip);
            });
            ui.horizontal(|ui| {
                if ui.add_enabled(!busy, egui::Button::new("Prepare ADB key")).clicked() {
                    self.prepare_adb_key();
                }
                if ui.add_enabled(!busy && host_key_md5.is_some(), egui::Button::new("Connect and inspect")).clicked() {
                    match self.device_ip.trim().parse::<std::net::IpAddr>() {
                        Ok(ip) => {
                            self.cleanup_plan = None;
                            self.plan_data_root = None;
                            self.plan_snapshot_path = None;
                            self.confirm_apply = false;
                            self.confirm_restore = false;
                            self.inspect_fire_tv(ip);
                        },
                        Err(_) => {
                            let mut state = self.shared.lock().expect("state lock poisoned");
                            state.device_profile = None;
                            state.package_inventory = None;
                            state.status = "Enter a valid local IP address for the Fire TV.".into();
                        }
                    }
                }
            });
            if let Some(fingerprint) = host_key_md5.as_deref() {
                ui.label("ADB key MD5 fingerprint (this is what the Android 7-era TV prompt displays):");
                ui.monospace(fingerprint);
                if let Some(sha256) = host_key_sha256.as_deref() {
                    ui.small(format!("SHA-256 app fingerprint: {sha256}"));
                }
                ui.label("On first connection, compare the TV's fingerprint with the MD5 value above before approving. The TV accepts this host key only if you approve; selecting 'Always allow' saves it for later connections.");
            } else {
                ui.small("Prepare the host key first. Its private key remains in the Windows user key store.");
            }
            if let Some(profile) = device_profile.as_ref() {
                ui.separator();
                ui.label(format!("Device: {} · {}", profile.manufacturer, profile.model));
                ui.label(format!("Build: {} · Android {} · SDK {}", profile.build_display_id, profile.android_release, profile.sdk));
                ui.label(format!("CPU ABI: {}", profile.abi));
                ui.label(format!("Cleanup compatibility: {:?}", profile.cleanup_compatibility()));
                ui.label(format!("DumbTV HOME/Keeper compatibility: {:?}", profile.launcher_compatibility()));
            }
            if let Some((installed, system, user, disabled)) = package_counts {
                ui.label(format!("Package inventory: {installed} installed · {system} system · {user} user-installed · {disabled} disabled"));
            }
            if let Some(ip) = connected_ip {
                if snapshot_path.is_none() && package_counts.is_some() {
                    if ui.add_enabled(!busy, egui::Button::new("Capture original state (read-only)")).clicked() {
                        self.capture_original_state(ip);
                    }
                }
                if let Some(path) = snapshot_path.as_ref() {
                    ui.label(format!("Original-state snapshot: {}", path.file_name().and_then(|name| name.to_str()).unwrap_or("saved")));
                }
            }
            if let (Some(ip), Some(profile)) = (connected_ip, device_profile.as_ref()) {
                ui.separator();
                ui.heading("DumbTV HOME installation");
                if profile.launcher_compatibility() == device_profile::Compatibility::Supported {
                    ui.label("API 25 only. Installs the checksum-verified published APK; it never uninstalls an existing signed package to work around a signature mismatch.");
                    ui.horizontal(|ui| {
                        if ui.add_enabled(!busy, egui::Button::new("Install / update DumbTV HOME (downloads if needed)")).clicked() {
                            self.install_launcher_on_device(ip, profile.clone());
                        }
                        if ui.add_enabled(!busy, egui::Button::new("Undo HOME takeover")).clicked() {
                            self.undo_launcher_on_device(ip, profile.clone());
                        }
                    });
                } else {
                    ui.label("Launcher installation is unavailable: this device is not the supported Amazon API 25 target. The undo action remains available if an older installation exists.");
                    if ui.add_enabled(!busy, egui::Button::new("Undo HOME takeover")).clicked() {
                        self.undo_launcher_on_device(ip, profile.clone());
                    }
                }
                ui.separator();
                ui.heading("Optional SmartTube replacement");
                ui.checkbox(&mut self.replace_youtube, "I explicitly want to install SmartTube and disable stock YouTube (reversible)");
                ui.label("SmartTube is opt-in. Stock YouTube is disabled, never uninstalled, by this workflow. The original YouTube state and exact home.conf bytes are backed up for undo.");
                ui.small("After installation, use the TV remote once: SmartTube Settings > General > Hide content > Hide shorts everywhere; Player > Video presets > 1080p 60fps VP9 (not AV1).");
                if ui.add_enabled(!busy && self.replace_youtube, egui::Button::new("Install SmartTube (downloads if needed)")).clicked() {
                    self.install_smarttube_on_device(ip, profile.clone());
                }
                let backup_exists = smarttube_backup_path(profile).is_ok_and(|path| path.is_file());
                if ui.add_enabled(!busy && backup_exists, egui::Button::new("Undo SmartTube replacement")).clicked() {
                    self.undo_smarttube_on_device(ip, profile.clone());
                }
            }
            if connected_ip.is_some() && package_inventory.is_some() && device_profile.is_some() {
                ui.separator();
                ui.heading("Cleanup choices");
                if ui.checkbox(&mut self.keep_alexa, "Keep Alexa and the remote voice button (default)").changed() {
                    self.cleanup_plan = None;
                    self.plan_data_root = None;
                    self.plan_snapshot_path = None;
                }
                ui.checkbox(&mut self.reboot_after_cleanup, "Reboot Fire TV after successful cleanup and verify reconnect (optional)");
                if let Some(profile) = device_profile.as_ref() {
                    if profile.launcher_compatibility() == device_profile::Compatibility::Supported {
                        ui.checkbox(&mut self.install_launcher_after_cleanup, "After cleanup, install / update DumbTV HOME (recommended)");
                    } else {
                        ui.label("DumbTV HOME installation is not supported on this API level; cleanup can still run.");
                    }
                }
                ui.label("Apps to remove — enter exact Android package IDs, separated by spaces. No other user apps will be removed.");
                if ui.text_edit_singleline(&mut self.remove_packages).changed() { self.cleanup_plan = None; self.plan_data_root = None; self.plan_snapshot_path = None; }
                ui.label("Packages to keep — optional exact package IDs from the maintained removal lists.");
                if ui.text_edit_singleline(&mut self.keep_packages).changed() { self.cleanup_plan = None; self.plan_data_root = None; self.plan_snapshot_path = None; }
                if snapshot_path.is_none() {
                    ui.small("Capture the original state before reviewing or applying any changes.");
                } else {
                    let mut review_clicked = false;
                    if ui.add_enabled(!busy, egui::Button::new("Review cleanup plan")).clicked() {
                        review_clicked = true;
                    }
                    if review_clicked {
                        match (device_profile.as_ref(), package_inventory.as_ref(), connected_ip, snapshot_path.as_ref()) {
                            (Some(profile), Some(inventory), Some(_ip), Some(path)) => {
                                match self.prepare_cleanup_plan(profile, inventory, &protected_uids, path) {
                                    Ok(()) => {
                                        let mut state = self.shared.lock().expect("state lock poisoned");
                                        state.status = "Cleanup plan prepared. Review every operation below; nothing has been changed.".into();
                                    }
                                    Err(error) => {
                                        let mut state = self.shared.lock().expect("state lock poisoned");
                                        state.status = format!("Cannot prepare cleanup plan: {error}");
                                    }
                                }
                            }
                            _ => {}
                        }
                    }
                }
            }
            if let (Some(ip), Some(profile), Some(path)) = (connected_ip, device_profile.as_ref(), snapshot_path.as_ref()) {
                if ui.add_enabled(!busy, egui::Button::new("Verify last cleanup (read-only)")).clicked() {
                    self.verify_last_cleanup(ip, profile.clone(), path.clone());
                }
                if ui.add_enabled(!busy, egui::Button::new("Review restore plan")).clicked() {
                    self.confirm_restore = false;
                    self.review_restore_plan(ip, profile.clone(), path.clone());
                }
            }
            if let Some(plan) = restore_plan.as_ref() {
                ui.separator();
                ui.heading("Restore original state");
                let (restore_operations, manual_actions) = operations::build_restore_operations(plan);
                ui.label(format!("{} automatic operations; {} manual actions", restore_operations.len(), manual_actions.len()));
                for (index, operation) in restore_operations.iter().enumerate() {
                    ui.label(format!("{}. {}", index + 1, operation.description));
                }
                for action in &manual_actions {
                    ui.label(format!("Manual: {action}"));
                }
                if restore_operations.is_empty() {
                    ui.label("No automatic restore changes are available.");
                } else if ui.add_enabled(!busy, egui::Button::new("Confirm and restore captured state")).clicked() {
                    self.confirm_restore = true;
                }
            }

            if let Some(plan) = self.cleanup_plan.as_ref() {
                ui.separator();
                ui.heading("Reviewed cleanup plan");
                ui.label(format!("{} device operations; {} items skipped", plan.operations.len(), plan.skipped.len()));
                for (index, operation) in plan.operations.iter().enumerate() {
                    ui.label(format!("{}. {}", index + 1, operation.description));
                }
                if !plan.skipped.is_empty() {
                    ui.collapsing("Skipped items", |ui| {
                        for item in &plan.skipped { ui.small(item); }
                    });
                }
                if plan.operations.is_empty() {
                    ui.label("No changes are needed for the selected options.");
                } else if ui.add_enabled(!busy, egui::Button::new(if (self.install_launcher_after_cleanup && device_profile.as_ref().is_some_and(|profile| profile.launcher_compatibility() == device_profile::Compatibility::Supported)) || self.replace_youtube { "Confirm, apply and install selected apps" } else { "Confirm and apply reviewed changes" })).clicked() {
                    self.confirm_apply = true;
                }
            }
            if self.confirm_apply {
                let has_uninstalls = self.cleanup_plan.as_ref().is_some_and(|plan| plan.operations.iter().any(|operation| matches!(&operation.command, device_command::DeviceCommand::UninstallPackageForUser { .. })));
                let count = self.cleanup_plan.as_ref().map(|plan| plan.operations.len()).unwrap_or(0);
                let mut apply_clicked = false;
                let mut cancel_clicked = false;
                egui::Window::new("Confirm Fire TV changes").collapsible(false).resizable(false).show(ctx, |ui| {
                    ui.label(format!("Apply {count} reviewed operations to the connected Fire TV?"));
                    if self.reboot_after_cleanup { ui.label("After successful cleanup, the Fire TV will reboot. The app will wait for ADB to disconnect, reconnect and verify the same device and final cleanup state."); }
                    if has_uninstalls {
                        ui.label("This includes uninstalling the user apps you explicitly named. Reinstalling them may require the Amazon Appstore.");
                    }
                    ui.label("The app will stop at the first failed operation or failed read-back. The original snapshot will be retained.");
                    if self.install_launcher_after_cleanup && device_profile.as_ref().is_some_and(|profile| profile.launcher_compatibility() == device_profile::Compatibility::Supported) {
                        ui.label("After successful cleanup (and optional reboot verification), the app will download/verify and install the published DumbTV HOME APK.");
                    }
                    if self.replace_youtube {
                        ui.label("SmartTube is also selected: after DumbTV HOME succeeds, it will be installed and stock YouTube disabled reversibly.");
                    }
                    ui.horizontal(|ui| {
                        if ui.button("Apply these changes").clicked() { apply_clicked = true; }
                        if ui.button("Cancel").clicked() { cancel_clicked = true; }
                    });
                });
                if cancel_clicked { self.confirm_apply = false; }
                if apply_clicked {
                    self.confirm_apply = false;
                    if let (Some(ip), Some(profile)) = (connected_ip, device_profile.clone()) {
                        let reboot_after_cleanup = self.reboot_after_cleanup;
                        let install_launcher = self.install_launcher_after_cleanup && profile.launcher_compatibility() == device_profile::Compatibility::Supported;
                        let install_smarttube = self.replace_youtube;
                        self.apply_cleanup_plan(ip, profile, reboot_after_cleanup, install_launcher, install_smarttube);
                    }
                }
            }
            if self.confirm_restore {
                let mut restore_clicked = false;
                let mut cancel_restore = false;
                egui::Window::new("Confirm restore").collapsible(false).resizable(false).show(ctx, |ui| {
                    ui.label("Restore only the verified changes made by this Control Centre cleanup on this Fire TV?");
                    ui.label("User apps removed for user 0 are not backed up; reinstall them manually from their original source (Amazon Appstore or sideloaded APK).");
                    ui.label("Selective restore only reverses operations recorded as verified in the matching DumbTV cleanup journal. Other differences from the original snapshot are left untouched and listed for manual review.");
                    ui.horizontal(|ui| {
                        if ui.button("Restore captured state").clicked() { restore_clicked = true; }
                        if ui.button("Cancel").clicked() { cancel_restore = true; }
                    });
                });
                if cancel_restore { self.confirm_restore = false; }
                if restore_clicked {
                    self.confirm_restore = false;
                    if let (Some(ip), Some(profile)) = (connected_ip, device_profile.clone()) {
                        self.apply_restore_plan(ip, profile);
                    }
                }
            }
            ui.small("Connection uses one owner-selected private/local IP on TCP 5555. Inventory and original-state capture are read-only; device changes are only sent after reviewing the plan and pressing the final confirmation.");
            ui.separator();

            ui.heading("Status");
            if busy {
                ui.horizontal(|ui| {
                    ui.spinner();
                    ui.label(&status);
                });
            } else {
                ui.label(&status);
            }
            ui.separator();
            ui.small("Portable Windows installer: source updater, native Rust ADB, read-only preflight, reviewed cleanup/restore, published DumbTV HOME installation and undo, and optional SmartTube replacement and undo. No Android source or APK is rebuilt.");
            });
        });
    }
}

fn short_sha(sha: &str) -> &str {
    sha.get(..7).unwrap_or(sha)
}

fn latest_cleanup_journal(
    profile: &device_profile::DeviceProfile,
    source_commit: &str,
) -> Result<Option<operation_journal::OperationJournal>, String> {
    let root = app_root()?.join("journals");
    let entries = match fs::read_dir(root) {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(format!("cannot read operation journals: {error}")),
    };
    let mut journals: Vec<operation_journal::OperationJournal> = entries
        .flatten()
        .map(|entry| entry.path())
        .filter(|path| path.extension().and_then(|value| value.to_str()) == Some("json"))
        .filter_map(|path| operation_journal::OperationJournal::load(&path).ok())
        .filter(|journal| {
            journal.workflow == operation_journal::WorkflowKind::Cleanup
                && journal.manufacturer == profile.manufacturer
                && journal.model == profile.model
                && journal.build_display_id == profile.build_display_id
                && journal.android_sdk == profile.sdk
                && journal.source_commit == source_commit
        })
        .collect();
    journals.sort_by(|left, right| right.created_unix_seconds.cmp(&left.created_unix_seconds));
    Ok(journals.into_iter().next())
}

fn load_unfinished_journals() -> Vec<String> {
    let root = match app_root() {
        Ok(root) => root.join("journals"),
        Err(_) => return Vec::new(),
    };
    let entries = match fs::read_dir(root) {
        Ok(entries) => entries,
        Err(_) => return Vec::new(),
    };
    let mut journals: Vec<(u64, PathBuf, operation_journal::OperationJournal)> = entries
        .flatten()
        .map(|entry| entry.path())
        .filter(|path| path.extension().and_then(|value| value.to_str()) == Some("json"))
        .filter_map(|path| {
            operation_journal::OperationJournal::load(&path)
                .ok()
                .map(|journal| (journal.created_unix_seconds, path, journal))
        })
        .collect();
    // Keep the newest recovery records, regardless of cleanup/restore filename prefix.
    journals.sort_by(|left, right| right.0.cmp(&left.0));
    journals.truncate(256);

    let mut summaries = Vec::new();
    for (_, path, journal) in journals {
        let verified = journal
            .operations
            .iter()
            .filter(|item| item.status == operation_journal::OperationStatus::Verified)
            .count();
        let running = journal
            .operations
            .iter()
            .filter(|item| item.status == operation_journal::OperationStatus::Running)
            .count();
        let failed = journal
            .operations
            .iter()
            .filter(|item| item.status == operation_journal::OperationStatus::Failed)
            .count();
        let pending = journal
            .operations
            .iter()
            .filter(|item| item.status == operation_journal::OperationStatus::Pending)
            .count();
        // Failed workflows are marked finished to prevent accidental replay,
        // but they still require owner review and must remain visible on restart.
        if journal.finished && failed == 0 && journal.workflow_error.is_none() {
            continue;
        }
        let attention: Vec<String> = journal
            .operations
            .iter()
            .filter_map(|item| {
                let detail = item.detail.as_deref().unwrap_or("no recorded error detail");
                let detail: String = detail.chars().take(240).collect();
                match item.status {
                    operation_journal::OperationStatus::Running => {
                        Some(format!("interrupted during: {}", item.description))
                    }
                    operation_journal::OperationStatus::Failed => {
                        Some(format!("failed: {} ({detail})", item.description))
                    }
                    _ => None,
                }
            })
            .take(2)
            .collect();
        let mut attention = attention;
        if let Some(error) = journal.workflow_error.as_deref() {
            attention.push(format!(
                "final verification failed: {}",
                error.chars().take(240).collect::<String>()
            ));
        }
        let attention = if attention.is_empty() {
            String::new()
        } else {
            format!(" Review: {}.", attention.join("; "))
        };
        let name = path
            .file_name()
            .and_then(|value| value.to_str())
            .unwrap_or("operation journal");
        summaries.push(format!(
            "{name}: {verified} verified, {running} running at last save, {failed} failed, {pending} pending. Device recorded: {} {} · {}.{attention}",
            journal.manufacturer, journal.model, journal.build_display_id
        ));
    }
    summaries
}

fn app_root() -> Result<PathBuf, String> {
    let base = dirs::data_local_dir().ok_or("Windows Local AppData folder is unavailable")?;
    Ok(base.join("DumbTV-ControlCentre"))
}

fn active_manifest_path() -> Result<PathBuf, String> {
    Ok(app_root()?.join("active-source.json"))
}

fn load_active_source() -> Result<Option<ActiveSource>, String> {
    let path = active_manifest_path()?;
    if !path.exists() {
        return Ok(None);
    }
    let bytes = fs::read(path).map_err(|e| format!("cannot read source manifest: {e}"))?;
    let manifest: ActiveSource =
        serde_json::from_slice(&bytes).map_err(|e| format!("invalid source manifest: {e}"))?;
    if manifest.repository != format!("{OWNER}/{REPOSITORY}") || !valid_sha(&manifest.commit) {
        return Err("source manifest does not identify the expected repository and commit".into());
    }
    let source = app_root()?.join("source-versions").join(&manifest.commit);
    validate_source_tree(&source)?;
    Ok(Some(manifest))
}

fn download_github_release_asset(url: &str, max_bytes: usize) -> Result<Vec<u8>, String> {
    let parsed = reqwest::Url::parse(url).map_err(|_| "invalid release asset URL")?;
    if parsed.scheme() != "https" || parsed.host_str() != Some("github.com") {
        return Err("release asset URL must originate from official GitHub HTTPS".into());
    }
    let response = http_client()?
        .get(parsed)
        .send()
        .map_err(|error| format!("GitHub release download failed: {error}"))?
        .error_for_status()
        .map_err(|error| format!("GitHub release asset was rejected: {error}"))?;
    if response.url().scheme() != "https"
        || !matches!(
            response.url().host_str(),
            Some(
                "github.com"
                    | "release-assets.githubusercontent.com"
                    | "objects.githubusercontent.com"
            )
        )
    {
        return Err("release asset redirected outside the approved GitHub download hosts".into());
    }
    if response
        .content_length()
        .is_some_and(|size| size > max_bytes as u64)
    {
        return Err("release asset exceeds the configured size limit".into());
    }
    let mut bytes = Vec::new();
    response
        .take(max_bytes as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| format!("could not read release asset: {error}"))?;
    if bytes.len() > max_bytes {
        return Err("release asset exceeds the configured size limit".into());
    }
    Ok(bytes)
}

fn store_verified_apk(name: &str, bytes: &[u8], expected_sha256: &str) -> Result<PathBuf, String> {
    apk_release::verify_sha256(bytes, expected_sha256)?;
    let root = app_root()?.join("verified-apks");
    fs::create_dir_all(&root)
        .map_err(|error| format!("cannot create verified APK folder: {error}"))?;
    let path = root.join(name);
    if path.exists() {
        let existing = fs::read(&path)
            .map_err(|error| format!("cannot read existing verified APK: {error}"))?;
        apk_release::verify_sha256(&existing, expected_sha256)
            .map_err(|_| "an existing APK at the expected path failed checksum validation; it was left untouched".to_owned())?;
        return Ok(path);
    }
    let parent = path.parent().ok_or("invalid verified APK path")?;
    let mut staged = tempfile::NamedTempFile::new_in(parent)
        .map_err(|error| format!("cannot stage verified APK: {error}"))?;
    staged
        .write_all(bytes)
        .map_err(|error| format!("cannot write verified APK: {error}"))?;
    staged
        .as_file()
        .sync_all()
        .map_err(|error| format!("cannot flush verified APK: {error}"))?;
    staged.persist_noclobber(&path).map_err(|error| {
        format!(
            "cannot save verified APK without overwriting an existing file: {}",
            error.error
        )
    })?;
    Ok(path)
}

#[cfg(windows)]
fn launcher_backup_path(profile: &device_profile::DeviceProfile) -> Result<PathBuf, String> {
    let identity = format!(
        "{}|{}|{}|{}",
        profile.manufacturer, profile.model, profile.build_display_id, profile.sdk
    );
    let key = apk_release::sha256_hex(identity.as_bytes());
    Ok(app_root()?
        .join("backups")
        .join(format!("launcher-{key}.json")))
}

#[cfg(windows)]
fn smarttube_backup_path(profile: &device_profile::DeviceProfile) -> Result<PathBuf, String> {
    let identity = format!(
        "{}|{}|{}|{}",
        profile.manufacturer, profile.model, profile.build_display_id, profile.sdk
    );
    let key = apk_release::sha256_hex(identity.as_bytes());
    Ok(app_root()?
        .join("backups")
        .join(format!("smarttube-{key}.json")))
}

#[cfg(windows)]
fn verified_launcher_apk() -> Result<(Vec<u8>, u64), String> {
    let manifest = load_active_source()?
        .ok_or("download the official source before installing DumbTV HOME")?;
    let source_root = app_root()?.join("source-versions").join(&manifest.commit);
    validate_source_tree(&source_root)?;
    let version_text = fs::read_to_string(source_root.join("launcher/version.env"))
        .map_err(|error| format!("cannot read launcher version manifest: {error}"))?;
    let release = apk_release::parse_launcher_version_env(&version_text)?;
    let filename = format!("dumbtv-home-{}.apk", release.version);
    let path = app_root()?.join("verified-apks").join(filename);
    if !path.is_file() {
        download_launcher_apk()?;
    }
    let base = format!(
        "https://github.com/{OWNER}/{REPOSITORY}/releases/download/launcher-v{}/dumbtv-home.apk",
        release.version
    );
    let sidecar = download_github_release_asset(&format!("{base}.sha256"), 4096)?;
    let expected =
        String::from_utf8(sidecar).map_err(|_| "launcher checksum sidecar is not UTF-8")?;
    let expected = expected.trim();
    let bytes =
        fs::read(&path).map_err(|error| format!("cannot read verified launcher APK: {error}"))?;
    apk_release::verify_sha256(&bytes, expected)?;
    Ok((bytes, release.version_code))
}

#[cfg(windows)]
fn verified_smarttube_apk() -> Result<Vec<u8>, String> {
    let path = app_root()?
        .join("verified-apks")
        .join("smarttube-32.56s.apk");
    if !path.is_file() {
        download_smarttube_apk()?;
    }
    let bytes =
        fs::read(path).map_err(|error| format!("cannot read verified SmartTube APK: {error}"))?;
    apk_release::verify_sha256(&bytes, apk_release::SMARTTUBE_SHA256)?;
    Ok(bytes)
}

fn download_launcher_apk() -> Result<String, String> {
    let manifest = load_active_source()?
        .ok_or("download the official source before preparing the launcher APK")?;
    let source_root = app_root()?.join("source-versions").join(&manifest.commit);
    validate_source_tree(&source_root)?;
    let version_text = fs::read_to_string(source_root.join("launcher/version.env"))
        .map_err(|error| format!("cannot read launcher version manifest: {error}"))?;
    let release = apk_release::parse_launcher_version_env(&version_text)?;
    let base = format!(
        "https://github.com/{OWNER}/{REPOSITORY}/releases/download/launcher-v{}/dumbtv-home.apk",
        release.version
    );
    let sidecar = download_github_release_asset(&format!("{base}.sha256"), 4096)?;
    let expected =
        String::from_utf8(sidecar).map_err(|_| "launcher checksum sidecar is not UTF-8")?;
    let expected = expected.trim();
    if expected.len() != 64 || !expected.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err("launcher release checksum sidecar is not a 64-character SHA-256 value".into());
    }
    let bytes = download_github_release_asset(&base, 128 * 1024 * 1024)?;
    let actual = apk_release::sha256_hex(&bytes);
    apk_release::verify_sha256(&bytes, expected)?;
    let path = store_verified_apk(
        &format!("dumbtv-home-{}.apk", release.version),
        &bytes,
        expected,
    )?;
    Ok(format!("{} {} (versionCode {}) downloaded and checksum-verified. SHA-256 {}. Stored as {}. It has not been installed.", apk_release::LAUNCHER_PACKAGE, release.version, release.version_code, actual, path.file_name().and_then(|value| value.to_str()).unwrap_or("launcher APK")) )
}

fn download_smarttube_apk() -> Result<String, String> {
    let bytes = download_github_release_asset(apk_release::SMARTTUBE_URL, 128 * 1024 * 1024)?;
    apk_release::verify_sha256(&bytes, apk_release::SMARTTUBE_SHA256)?;
    let path = store_verified_apk(
        "smarttube-32.56s.apk",
        &bytes,
        apk_release::SMARTTUBE_SHA256,
    )?;
    Ok(format!("{} versionCode {} downloaded and checksum-verified. {} has not been disabled or removed. Stored as {}.", apk_release::SMARTTUBE_PACKAGE, apk_release::SMARTTUBE_VERSION_CODE, apk_release::STOCK_YOUTUBE_PACKAGE, path.file_name().and_then(|value| value.to_str()).unwrap_or("SmartTube APK")))
}

fn valid_sha(sha: &str) -> bool {
    sha.len() == 40 && sha.bytes().all(|b| b.is_ascii_hexdigit())
}

fn http_client() -> Result<reqwest::blocking::Client, String> {
    reqwest::blocking::Client::builder()
        .user_agent("DumbTV-Control-Centre/0.1.0")
        .connect_timeout(Duration::from_secs(15))
        .timeout(Duration::from_secs(120))
        .build()
        .map_err(|e| format!("cannot initialise HTTPS client: {e}"))
}

fn check_latest_commit() -> Result<Option<String>, String> {
    let url = format!("https://api.github.com/repos/{OWNER}/{REPOSITORY}/commits/main");
    let response = http_client()?
        .get(url)
        .header(reqwest::header::ACCEPT, "application/vnd.github+json")
        .send()
        .map_err(|e| format!("GitHub request failed: {e}"))?
        .error_for_status()
        .map_err(|e| format!("GitHub rejected the update check: {e}"))?;
    if response.url().scheme() != "https" || response.url().host_str() != Some("api.github.com") {
        return Err("update check redirected outside the official GitHub API host".into());
    }
    let commit: GitHubCommit = response
        .json()
        .map_err(|e| format!("GitHub returned an unreadable commit response: {e}"))?;
    if !valid_sha(&commit.sha) {
        return Err("GitHub returned an invalid commit identifier".into());
    }
    Ok(Some(commit.sha))
}

fn download_latest_source() -> Result<String, String> {
    let latest = check_latest_commit()?.ok_or("GitHub returned no commit")?;
    let root = app_root()?;
    let versions = root.join("source-versions");
    fs::create_dir_all(&versions)
        .map_err(|e| format!("cannot create managed source folder: {e}"))?;
    let destination = versions.join(&latest);

    if destination.exists() {
        validate_source_tree(&destination).map_err(|error| {
            format!("existing source folder failed validation and was left untouched: {error}")
        })?;
        write_active_manifest(&latest)?;
        return Ok(format!(
            "Source {} is already downloaded and selected.",
            short_sha(&latest)
        ));
    }

    let url = format!("https://github.com/{OWNER}/{REPOSITORY}/archive/{latest}.zip");
    let response = http_client()?
        .get(url)
        .send()
        .map_err(|e| format!("source download failed: {e}"))?
        .error_for_status()
        .map_err(|e| format!("GitHub rejected the source download: {e}"))?;

    if response.url().scheme() != "https"
        || !matches!(
            response.url().host_str(),
            Some("github.com" | "codeload.github.com")
        )
    {
        return Err("source download redirected outside the official GitHub archive hosts".into());
    }
    if response
        .content_length()
        .is_some_and(|n| n > MAX_ARCHIVE_BYTES)
    {
        return Err("source archive exceeds the 512 MiB safety limit".into());
    }

    let mut archive_bytes = Vec::new();
    response
        .take(MAX_ARCHIVE_BYTES + 1)
        .read_to_end(&mut archive_bytes)
        .map_err(|e| format!("cannot read source archive: {e}"))?;
    if archive_bytes.len() as u64 > MAX_ARCHIVE_BYTES {
        return Err("source archive exceeds the 512 MiB safety limit".into());
    }

    let temp = tempfile::Builder::new()
        .prefix("dumbtv-source-")
        .tempdir_in(&versions)
        .map_err(|e| format!("cannot create staging folder: {e}"))?;
    extract_verified_archive(&archive_bytes, temp.path(), &latest)?;

    let staged_root = temp.path().join(format!("{REPOSITORY}-{latest}"));
    validate_source_tree(&staged_root)
        .map_err(|error| format!("downloaded source failed validation: {error}"))?;

    fs::rename(&staged_root, &destination)
        .map_err(|e| format!("cannot activate downloaded source version: {e}"))?;
    write_active_manifest(&latest)?;
    Ok(format!(
        "Downloaded and selected official source commit {}. Nothing from the archive was executed.",
        short_sha(&latest)
    ))
}

fn validate_source_tree(root: &Path) -> Result<(), String> {
    // The Windows controller consumes maintained catalogues and release
    // metadata only. Shell scripts and Android source are behavioural
    // references in the repository, not runtime inputs to this application.
    for required in ["launcher/version.env"] {
        if !root.join(required).is_file() {
            return Err(format!(
                "source archive is missing required file: {required}"
            ));
        }
    }

    let version_text = fs::read_to_string(root.join("launcher/version.env"))
        .map_err(|error| format!("cannot read launcher version manifest: {error}"))?;
    apk_release::parse_launcher_version_env(&version_text)?;

    let data = root.join("data");
    for sdk in [25, 28, 30] {
        let catalogue = catalogue::FireOsCatalogue::load(&data, sdk)?;
        if !catalogue
            .never_disable
            .iter()
            .any(|package| package == "com.amazon.vizzini")
            || catalogue
                .packages
                .iter()
                .any(|package| package == "com.amazon.vizzini")
        {
            return Err(format!(
                "Fire OS SDK {sdk} catalogue violates the vizzini protection invariant"
            ));
        }
    }

    let settings = settings_catalogue::SettingsCatalogue::load(&data)?;
    if settings.privacy.is_empty() || settings.restore_defaults.is_empty() {
        return Err("source archive has an empty privacy or restore settings catalogue".into());
    }
    Ok(())
}

fn extract_verified_archive(
    bytes: &[u8],
    destination: &Path,
    expected_sha: &str,
) -> Result<(), String> {
    let cursor = std::io::Cursor::new(bytes);
    let mut archive =
        zip::ZipArchive::new(cursor).map_err(|e| format!("invalid ZIP archive: {e}"))?;
    if archive.len() > MAX_ARCHIVE_FILES {
        return Err("archive contains too many entries".into());
    }

    let expected_root = format!("{REPOSITORY}-{expected_sha}");
    let mut total_written = 0_u64;
    let mut seen_paths = HashSet::new();

    for index in 0..archive.len() {
        let entry = archive
            .by_index(index)
            .map_err(|e| format!("cannot read ZIP entry: {e}"))?;
        let name = entry
            .name()
            .map_err(|error| format!("archive entry has invalid path encoding: {error}"))?
            .into_owned();
        let path = Path::new(&name);

        if entry
            .unix_mode()
            .is_some_and(|mode| mode & 0o170000 == 0o120000)
        {
            return Err(format!("archive contains a symbolic link: {name}"));
        }
        if path
            .components()
            .any(|part| !matches!(part, Component::Normal(_)))
            || !safe_windows_archive_path(&name)
        {
            return Err(format!("archive contains an unsafe path: {name}"));
        }
        let collision_key = name.to_lowercase();
        if !seen_paths.insert(collision_key) {
            return Err(format!(
                "archive contains a duplicate or case-colliding path: {name}"
            ));
        }
        let relative = path
            .strip_prefix(&expected_root)
            .map_err(|_| "archive contains an unexpected top-level directory")?
            .to_path_buf();
        if relative.as_os_str().is_empty() {
            continue;
        }

        // Retain only the small data/configuration surface needed by the
        // installer. In particular, do not extract Android source, Gradle
        // projects, APK build inputs, or shell scripts into the managed cache.
        let keep =
            relative.starts_with("data") || relative == Path::new("launcher").join("version.env");
        if !keep {
            continue;
        }

        let output = destination.join(&expected_root).join(&relative);
        if entry.is_dir() {
            fs::create_dir_all(&output)
                .map_err(|e| format!("cannot create archive directory: {e}"))?;
            continue;
        }

        let declared_size = entry.size();
        let next_total = total_written
            .checked_add(declared_size)
            .ok_or("archive size overflow")?;
        if next_total > MAX_EXTRACTED_BYTES {
            return Err("extracted source exceeds the 1 GiB safety limit".into());
        }
        if let Some(parent) = output.parent() {
            fs::create_dir_all(parent)
                .map_err(|e| format!("cannot create archive directory: {e}"))?;
        }
        let mut file =
            fs::File::create(&output).map_err(|e| format!("cannot create extracted file: {e}"))?;
        let copied = std::io::copy(&mut entry.take(declared_size.saturating_add(1)), &mut file)
            .map_err(|e| format!("cannot extract archive entry: {e}"))?;
        if copied != declared_size {
            return Err(format!(
                "archive entry size did not match its declared length: {name}"
            ));
        }
        total_written = next_total;
        file.flush()
            .map_err(|e| format!("cannot flush extracted file: {e}"))?;
    }
    Ok(())
}

fn safe_windows_archive_path(name: &str) -> bool {
    name.split('/').all(|part| {
        if part.is_empty() {
            return true;
        }
        if part
            .chars()
            .any(|c| matches!(c, ':' | '\\' | '<' | '>' | '"' | '|' | '?' | '*'))
            || part.chars().any(|c| c.is_control())
            || part.ends_with('.')
            || part.ends_with(' ')
        {
            return false;
        }
        let device_name = part.split('.').next().unwrap_or("").to_ascii_uppercase();
        !matches!(
            device_name.as_str(),
            "CON"
                | "PRN"
                | "AUX"
                | "NUL"
                | "COM1"
                | "COM2"
                | "COM3"
                | "COM4"
                | "COM5"
                | "COM6"
                | "COM7"
                | "COM8"
                | "COM9"
                | "LPT1"
                | "LPT2"
                | "LPT3"
                | "LPT4"
                | "LPT5"
                | "LPT6"
                | "LPT7"
                | "LPT8"
                | "LPT9"
        )
    })
}

fn write_active_manifest(commit: &str) -> Result<(), String> {
    if !valid_sha(commit) {
        return Err("refusing to write an invalid commit identifier".into());
    }
    let path = active_manifest_path()?;
    let parent = path.parent().ok_or("invalid application data path")?;
    fs::create_dir_all(parent)
        .map_err(|e| format!("cannot create application data folder: {e}"))?;
    let manifest = ActiveSource {
        repository: format!("{OWNER}/{REPOSITORY}"),
        commit: commit.to_owned(),
    };
    let bytes =
        serde_json::to_vec_pretty(&manifest).map_err(|e| format!("cannot encode manifest: {e}"))?;
    AtomicFile::new(&path, AllowOverwrite)
        .write(|file| file.write_all(&bytes))
        .map_err(|e| format!("cannot atomically activate source manifest: {e}"))?;
    Ok(())
}

fn main() -> eframe::Result<()> {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([760.0, 600.0])
            .with_min_inner_size([640.0, 480.0]),
        ..Default::default()
    };
    eframe::run_native(
        "DumbTV Windows Installer",
        options,
        Box::new(|_creation_context| Ok(Box::new(ControlCentre::default()))),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_the_complete_maintained_source_tree() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
        validate_source_tree(&root).unwrap();
    }

    #[test]
    fn accepts_only_full_hex_commit_ids() {
        assert!(valid_sha("0123456789abcdef0123456789abcdef01234567"));
        assert!(!valid_sha("0123456"));
        assert!(!valid_sha("../0123456789abcdef0123456789abcdef012345"));
        assert!(!valid_sha("0123456789abcdef0123456789abcdef0123456g"));
    }

    #[test]
    fn rejects_windows_device_names_and_special_paths() {
        assert!(!safe_windows_archive_path("dumbtv/CON.txt"));
        assert!(!safe_windows_archive_path("dumbtv/folder./file"));
        assert!(!safe_windows_archive_path("dumbtv/name:stream"));
        assert!(safe_windows_archive_path(
            "dumbtv/tools/control-centre/README.md"
        ));
    }

    #[test]
    fn extracts_only_installer_catalogues_and_release_metadata() {
        let mut bytes = Vec::new();
        {
            let mut writer = zip::ZipWriter::new(std::io::Cursor::new(&mut bytes));
            let root = "dumbtv-0123456789abcdef0123456789abcdef01234567/";
            for (path, contents) in [
                ("data/fireos/packages.txt", "com.amazon.example"),
                (
                    "launcher/version.env",
                    "VERSION=1.13\\nVERSION_CODE=14\\nVERSION_NAME=1.13",
                ),
                ("app/build.gradle", "android source must not be retained"),
                ("scripts/all.sh", "shell scripts are reference only"),
            ] {
                writer
                    .start_file(
                        format!("{root}{path}"),
                        zip::write::SimpleFileOptions::default(),
                    )
                    .unwrap();
                writer.write_all(contents.as_bytes()).unwrap();
            }
            writer.finish().unwrap();
        }
        let dir = tempfile::tempdir().unwrap();
        extract_verified_archive(
            &bytes,
            dir.path(),
            "0123456789abcdef0123456789abcdef01234567",
        )
        .unwrap();
        let extracted = dir
            .path()
            .join("dumbtv-0123456789abcdef0123456789abcdef01234567");
        assert_eq!(
            fs::read_to_string(extracted.join("data/fireos/packages.txt")).unwrap(),
            "com.amazon.example"
        );
        assert!(extracted.join("launcher/version.env").is_file());
        assert!(!extracted.join("app/build.gradle").exists());
        assert!(!extracted.join("scripts/all.sh").exists());
    }

    #[test]
    fn rejects_archive_path_traversal() {
        let mut bytes = Vec::new();
        {
            let mut writer = zip::ZipWriter::new(std::io::Cursor::new(&mut bytes));
            writer
                .start_file(
                    "dumbtv-0123456789abcdef0123456789abcdef01234567/../../outside.txt",
                    zip::write::SimpleFileOptions::default(),
                )
                .unwrap();
            writer.write_all(b"bad").unwrap();
            writer.finish().unwrap();
        }
        let dir = tempfile::tempdir().unwrap();
        let result = extract_verified_archive(
            &bytes,
            dir.path(),
            "0123456789abcdef0123456789abcdef01234567",
        );
        assert!(result.is_err());
        assert!(!dir.path().join("outside.txt").exists());
    }
}
