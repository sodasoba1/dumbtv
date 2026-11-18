//! Original-state snapshots and exact, delta-only restore planning.
//!
//! This module is deliberately device-agnostic. It never issues ADB commands.
//! A snapshot is immutable once written; restore plans only actions needed to
//! return captured state to its recorded values. Store apps removed for user 0
//! cannot be reconstructed by this module and are reported as manual work.

use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    io::Write,
    path::Path,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PackageState {
    Enabled,
    Disabled,
    DisabledBySystem,
    NotInstalled,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AppOpState {
    pub package: String,
    pub operation: String,
    pub mode: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceSnapshot {
    /// Stable local label chosen by the desktop app; do not put a raw serial in reports.
    pub device_label: String,
    pub manufacturer: String,
    pub model: String,
    pub android_sdk: u32,
    /// Exact source revision whose catalogues defined this baseline and plan.
    pub source_commit: String,
    pub packages: BTreeMap<String, PackageState>,
    /// User-installed packages are distinguished so restore never claims a
    /// preinstalled system app needs an Appstore reinstall.
    pub user_packages: BTreeSet<String>,
    /// None means the setting did not exist before the first change.
    pub secure_settings: BTreeMap<String, Option<String>>,
    pub global_settings: BTreeMap<String, Option<String>>,
    pub app_ops: Vec<AppOpState>,
    /// None means the original value could not be read; it must never be guessed.
    pub restrict_background: Option<bool>,
    /// None means the original blacklist could not be read.
    pub metered_blacklist_uids: Option<BTreeSet<u32>>,
    /// Explicitly uninstalled user apps cannot be restored from an APK by this tool.
    pub user_apps_uninstalled_by_tool: BTreeSet<String>,
}

impl DeviceSnapshot {
    pub fn validate(&self) -> Result<(), String> {
        if self.device_label.trim().is_empty() {
            return Err("snapshot is missing its local device label".into());
        }
        if self.manufacturer.trim().is_empty() || self.android_sdk == 0 {
            return Err("snapshot is missing device identity or SDK".into());
        }
        if self.source_commit.len() != 40 || !self.source_commit.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return Err("snapshot has an invalid pinned source revision".into());
        }
        for package in self.packages.keys() {
            if !valid_package(package) {
                return Err(format!("snapshot contains an invalid package identifier: {package}"));
            }
        }
        for package in &self.user_packages {
            if !valid_package(package) || !self.packages.contains_key(package) {
                return Err(format!("snapshot contains an invalid or uninstalled user package: {package}"));
            }
        }
        for (key, value) in self.secure_settings.iter().chain(self.global_settings.iter()) {
            if !valid_setting_key(key) || value.as_deref().is_some_and(|v| !valid_setting_value(v)) {
                return Err(format!("snapshot contains an invalid settings key/value: {key}"));
            }
        }
        let mut app_ops_seen = BTreeSet::new();
        for item in &self.app_ops {
            if !valid_package(&item.package)
                || !valid_operation(&item.operation)
                || !valid_mode(&item.mode)
                || !app_ops_seen.insert((item.package.clone(), item.operation.clone()))
            {
                return Err("snapshot contains an invalid or duplicate AppOps entry".into());
            }
        }
        Ok(())
    }

    /// Load and validate a previously captured original-state snapshot.
    pub fn load(path: &Path) -> Result<Self, String> {
        let bytes = std::fs::read(path)
            .map_err(|error| format!("cannot read original snapshot: {error}"))?;
        let snapshot: Self = serde_json::from_slice(&bytes)
            .map_err(|error| format!("original snapshot is not valid JSON: {error}"))?;
        snapshot.validate()?;
        Ok(snapshot)
    }

    /// Persist once. A later run must never replace the pre-cleanup original state.
    pub fn save_once(&self, path: &Path) -> Result<(), String> {
        self.validate()?;
        if path.exists() {
            return Err("an original snapshot already exists; refusing to overwrite it".into());
        }
        let parent = path.parent().ok_or("snapshot path has no parent folder")?;
        std::fs::create_dir_all(parent)
            .map_err(|error| format!("cannot create snapshot folder: {error}"))?;
        let bytes = serde_json::to_vec_pretty(self)
            .map_err(|error| format!("cannot encode original snapshot: {error}"))?;
        let mut staged = tempfile::NamedTempFile::new_in(parent)
            .map_err(|error| format!("cannot stage original snapshot: {error}"))?;
        staged.write_all(&bytes)
            .map_err(|error| format!("cannot write staged original snapshot: {error}"))?;
        staged.as_file().sync_all()
            .map_err(|error| format!("cannot flush staged original snapshot: {error}"))?;
        staged.persist_noclobber(path)
            .map_err(|error| format!("cannot atomically save original snapshot without overwrite: {}", error.error))?;
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CurrentDeviceState {
    pub packages: BTreeMap<String, PackageState>,
    pub user_packages: BTreeSet<String>,
    pub secure_settings: BTreeMap<String, Option<String>>,
    pub global_settings: BTreeMap<String, Option<String>>,
    pub app_ops: Vec<AppOpState>,
    pub restrict_background: Option<bool>,
    pub metered_blacklist_uids: Option<BTreeSet<u32>>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RestoreAction {
    SetPackageState { package: String, state: PackageState },
    PutSetting { namespace: SettingNamespace, key: String, value: String },
    DeleteSetting { namespace: SettingNamespace, key: String },
    SetAppOp { package: String, operation: String, mode: String },
    SetRestrictBackground(bool),
    AddMeteredBlacklistUid(u32),
    RemoveMeteredBlacklistUid(u32),
    ManualUserAppReinstall(String),
    ManualStateUnavailable(&'static str),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SettingNamespace {
    Secure,
    Global,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RestorePlan {
    pub actions: Vec<RestoreAction>,
}

pub fn build_restore_plan(
    original: &DeviceSnapshot,
    current: &CurrentDeviceState,
) -> Result<RestorePlan, String> {
    original.validate()?;
    let mut actions = Vec::new();

    for (package, original_state) in &original.packages {
        match current.packages.get(package) {
            Some(current_state) if current_state != original_state => {
                if *original_state != PackageState::NotInstalled {
                    actions.push(RestoreAction::SetPackageState {
                        package: package.clone(),
                        state: *original_state,
                    });
                }
            }
            None if *original_state != PackageState::NotInstalled && original.user_packages.contains(package) => {
                // A user app removed for user 0 cannot be reconstructed from this tool.
                actions.push(RestoreAction::ManualUserAppReinstall(package.clone()));
            }
            None if *original_state != PackageState::NotInstalled => {
                actions.push(RestoreAction::ManualStateUnavailable(
                    "a preinstalled system package is missing; manual recovery is required",
                ));
            }
            _ => {}
        }
    }
    for package in &original.user_apps_uninstalled_by_tool {
        actions.push(RestoreAction::ManualUserAppReinstall(package.clone()));
    }

    add_setting_restore_actions(
        &mut actions,
        SettingNamespace::Secure,
        &original.secure_settings,
        &current.secure_settings,
    );
    add_setting_restore_actions(
        &mut actions,
        SettingNamespace::Global,
        &original.global_settings,
        &current.global_settings,
    );

    let original_ops: BTreeMap<_, _> = original.app_ops.iter()
        .map(|item| ((item.package.as_str(), item.operation.as_str()), item.mode.as_str()))
        .collect();
    let current_ops: BTreeMap<_, _> = current.app_ops.iter()
        .map(|item| ((item.package.as_str(), item.operation.as_str()), item.mode.as_str()))
        .collect();
    for ((package, operation), mode) in original_ops {
        if current_ops.get(&(package, operation)).copied() != Some(mode) {
            actions.push(RestoreAction::SetAppOp {
                package: package.to_owned(),
                operation: operation.to_owned(),
                mode: mode.to_owned(),
            });
        }
    }

    match (original.restrict_background, current.restrict_background) {
        (Some(before), Some(now)) if before != now => {
            actions.push(RestoreAction::SetRestrictBackground(before));
        }
        (None, _) | (_, None) => actions.push(RestoreAction::ManualStateUnavailable(
            "Data Saver state could not be captured or read back",
        )),
        _ => {}
    }

    match (&original.metered_blacklist_uids, &current.metered_blacklist_uids) {
        (Some(before), Some(now)) => {
            for uid in before.difference(now) {
                actions.push(RestoreAction::AddMeteredBlacklistUid(*uid));
            }
            for uid in now.difference(before) {
                actions.push(RestoreAction::RemoveMeteredBlacklistUid(*uid));
            }
        }
        _ => actions.push(RestoreAction::ManualStateUnavailable(
            "metered-data UID blacklist could not be captured or read back",
        )),
    }

    Ok(RestorePlan { actions })
}

fn add_setting_restore_actions(
    actions: &mut Vec<RestoreAction>,
    namespace: SettingNamespace,
    original: &BTreeMap<String, Option<String>>,
    current: &BTreeMap<String, Option<String>>,
) {
    for (key, before) in original {
        if current.get(key) == Some(before) {
            continue;
        }
        match before {
            Some(value) => actions.push(RestoreAction::PutSetting {
                namespace, key: key.clone(), value: value.clone(),
            }),
            None => actions.push(RestoreAction::DeleteSetting {
                namespace, key: key.clone(),
            }),
        }
    }
}

fn valid_package(value: &str) -> bool {
    if value == "android" {
        return true;
    }
    let parts: Vec<&str> = value.split('.').collect();
    parts.len() >= 2 && parts.iter().all(|part| valid_segment(part))
}
fn valid_segment(value: &str) -> bool {
    !value.is_empty()
        && value.as_bytes()[0].is_ascii_alphabetic()
        && value.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
}
fn valid_setting_key(value: &str) -> bool {
    !value.is_empty() && value.bytes().all(|b| b.is_ascii_alphanumeric() || b"_.:-".contains(&b))
}
fn valid_setting_value(value: &str) -> bool {
    value.len() <= 4096 && !value.chars().any(char::is_control)
}
fn valid_operation(value: &str) -> bool {
    !value.is_empty() && value.bytes().all(|b| b.is_ascii_uppercase() || b == b'_')
}
fn valid_mode(value: &str) -> bool {
    matches!(value, "allow" | "ignore" | "deny" | "default" | "foreground" | "errored")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn snapshot() -> DeviceSnapshot {
        DeviceSnapshot {
            device_label: "Fire TV (local)".into(),
            manufacturer: "Amazon".into(),
            model: "AFTMM".into(),
            android_sdk: 25,
            source_commit: "0123456789abcdef0123456789abcdef01234567".into(),
            packages: [("com.amazon.ads".into(), PackageState::Enabled)]
                .into_iter().collect(),
            user_packages: ["com.amazon.ads".into()].into_iter().collect(),
            secure_settings: [
                ("advertising_id".into(), Some("1".into())),
                ("previously_absent".into(), None),
            ].into_iter().collect(),
            global_settings: BTreeMap::new(),
            app_ops: vec![AppOpState {
                package: "com.amazon.ads".into(),
                operation: "RUN_IN_BACKGROUND".into(),
                mode: "allow".into(),
            }],
            restrict_background: Some(false),
            metered_blacklist_uids: Some([10001].into_iter().collect()),
            user_apps_uninstalled_by_tool: ["com.example.player".into()].into_iter().collect(),
        }
    }

    fn current() -> CurrentDeviceState {
        CurrentDeviceState {
            packages: [("com.amazon.ads".into(), PackageState::Disabled)]
                .into_iter().collect(),
            user_packages: ["com.amazon.ads".into()].into_iter().collect(),
            secure_settings: [
                ("advertising_id".into(), Some("0".into())),
                ("previously_absent".into(), Some("x".into())),
            ].into_iter().collect(),
            global_settings: BTreeMap::new(),
            app_ops: vec![AppOpState {
                package: "com.amazon.ads".into(),
                operation: "RUN_IN_BACKGROUND".into(),
                mode: "ignore".into(),
            }],
            restrict_background: Some(true),
            metered_blacklist_uids: Some([10002].into_iter().collect()),
        }
    }

    #[test]
    fn restore_plan_returns_only_captured_values_and_deltas() {
        let plan = build_restore_plan(&snapshot(), &current()).unwrap();
        assert!(plan.actions.contains(&RestoreAction::SetPackageState {
            package: "com.amazon.ads".into(), state: PackageState::Enabled,
        }));
        assert!(plan.actions.contains(&RestoreAction::PutSetting {
            namespace: SettingNamespace::Secure, key: "advertising_id".into(), value: "1".into(),
        }));
        assert!(plan.actions.contains(&RestoreAction::DeleteSetting {
            namespace: SettingNamespace::Secure, key: "previously_absent".into(),
        }));
        assert!(plan.actions.contains(&RestoreAction::SetAppOp {
            package: "com.amazon.ads".into(), operation: "RUN_IN_BACKGROUND".into(), mode: "allow".into(),
        }));
        assert!(plan.actions.contains(&RestoreAction::SetRestrictBackground(false)));
        assert!(plan.actions.contains(&RestoreAction::AddMeteredBlacklistUid(10001)));
        assert!(plan.actions.contains(&RestoreAction::RemoveMeteredBlacklistUid(10002)));
        assert!(plan.actions.contains(&RestoreAction::ManualUserAppReinstall("com.example.player".into())));
    }

    #[test]
    fn restore_reports_originally_installed_but_now_missing_user_apps() {
        let mut current = current();
        current.packages.clear();
        let plan = build_restore_plan(&snapshot(), &current).unwrap();
        assert!(plan.actions.contains(&RestoreAction::ManualUserAppReinstall("com.amazon.ads".into())));
    }

    #[test]
    fn unavailable_original_policy_state_is_never_guessed() {
        let mut original = snapshot();
        original.restrict_background = None;
        original.metered_blacklist_uids = None;
        let plan = build_restore_plan(&original, &current()).unwrap();
        assert!(plan.actions.iter().any(|action| matches!(
            action, RestoreAction::ManualStateUnavailable(_)
        )));
    }

    #[test]
    fn snapshot_validation_rejects_bad_packages_and_appops() {
        let mut original = snapshot();
        original.packages.insert("../bad".into(), PackageState::Enabled);
        assert!(original.validate().is_err());
        let mut original = snapshot();
        original.app_ops[0].mode = "run shell".into();
        assert!(original.validate().is_err());
        let mut original = snapshot();
        original.secure_settings.insert("safe_key".into(), Some("value with spaces".into()));
        assert!(original.validate().is_ok());
        original.secure_settings.insert("unsafe_key".into(), Some("bad\nvalue".into()));
        assert!(original.validate().is_err());
        let mut original = snapshot();
        original.packages.insert("notapackage".into(), PackageState::Enabled);
        assert!(original.validate().is_err());
    }

    #[test]
    fn snapshot_is_created_once_and_cannot_be_overwritten() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("original.json");
        let original = snapshot();
        original.save_once(&path).unwrap();
        assert!(original.save_once(&path).is_err());
        let saved: DeviceSnapshot = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
        assert_eq!(saved, original);
    }
}
