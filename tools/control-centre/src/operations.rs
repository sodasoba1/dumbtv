//! Deterministic cleanup planning from the maintained catalogues and live inventory.
//!
//! This module is pure planning: it never opens ADB or performs device writes.

use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;

use crate::{
    catalogue::FireOsCatalogue,
    device_command::DeviceCommand,
    package_inventory::PackageInventory,
    package_plan::{build_package_plan, PackageActionKind, PackageChoices},
    settings_catalogue::SettingsCatalogue,
    snapshot::{DeviceSnapshot, PackageState, RestoreAction, RestorePlan},
};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlannedOperation {
    pub description: String,
    pub command: DeviceCommand,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CleanupPlan {
    pub operations: Vec<PlannedOperation>,
    pub skipped: Vec<String>,
}

/// Build the exact operations to review before the owner approves execution.
/// User-installed apps enter the uninstall list only through choices.remove.
pub fn build_cleanup_plan(
    data_root: &Path,
    sdk: u32,
    inventory: &PackageInventory,
    choices: &PackageChoices,
    protected_uids: &BTreeMap<String, u32>,
    original: &DeviceSnapshot,
) -> Result<CleanupPlan, String> {
    let catalogue = FireOsCatalogue::load(data_root, sdk)?;
    let settings = SettingsCatalogue::load(data_root)?;
    let package_plan = build_package_plan(
        &catalogue,
        choices,
        &inventory.installed,
        &inventory.system,
        &inventory.disabled,
    )?;

    let mut operations = Vec::new();
    let mut skipped = Vec::new();
    let mut planned_uids = BTreeSet::new();
    let packages_to_disable: BTreeSet<String> = package_plan.actions.iter()
        .filter(|action| action.kind == PackageActionKind::Disable)
        .map(|action| action.package.clone())
        .collect();

    for action in package_plan.actions {
        match action.kind {
            PackageActionKind::Disable => operations.push(PlannedOperation {
                description: format!("Disable {} ({})", action.package, action.reason),
                command: DeviceCommand::DisablePackage(action.package),
            }),
            PackageActionKind::UninstallForUser => operations.push(PlannedOperation {
                description: format!("Uninstall owner-named user app {} (requires explicit approval)", action.package),
                command: DeviceCommand::UninstallPackageForUser {
                    package: action.package,
                    owner_confirmed: false,
                },
            }),
            _ => skipped.push(format!("{} — {}", action.package, action.reason)),
        }
    }

    let mut setting_changes = settings.privacy;
    if !choices.keep_alexa {
        setting_changes.extend(settings.alexa_off);
    }
    for setting in setting_changes {
        let namespace = setting.namespace;
        let key = setting.key;
        let value = setting.value;
        let original_value = match namespace {
            crate::settings_catalogue::SettingsNamespace::Secure => original.secure_settings.get(&key),
            crate::settings_catalogue::SettingsNamespace::Global => original.global_settings.get(&key),
        };
        if original_value.and_then(|value| value.as_deref()) == Some(value.as_str()) {
            skipped.push(format!("{key} — already has the requested value"));
            continue;
        }
        operations.push(PlannedOperation {
            description: format!("Set {} setting {} to {}", namespace.as_str(), key, value),
            command: DeviceCommand::PutSetting { namespace, key, value },
        });
    }

    if let Some(Some(components)) = original.secure_settings.get("screensaver_components") {
        let current_dream = components.split(',').next().unwrap_or("").split('/').next().unwrap_or("");
        if packages_to_disable.contains(current_dream) || inventory.disabled.contains(current_dream) {
            if inventory.installed.contains("com.android.dreams.basic") {
                operations.push(PlannedOperation {
                    description: format!("Switch the screensaver to Android's built-in dream because {current_dream} is being disabled"),
                    command: DeviceCommand::PutSetting {
                        namespace: crate::settings_catalogue::SettingsNamespace::Secure,
                        key: "screensaver_components".into(),
                        value: "com.android.dreams.basic/.Colors".into(),
                    },
                });
            } else {
                skipped.push(format!("{current_dream} — screensaver package will be disabled and no Android fallback is installed"));
            }
        }
    }

    if original.restrict_background != Some(true) {
        operations.push(PlannedOperation {
            description: "Enable Android Data Saver (does not block normal Wi-Fi/Ethernet traffic)".into(),
            command: DeviceCommand::SetRestrictBackground(true),
        });
    }

    for package in &catalogue.protected {
        if !inventory.installed.contains(package) {
            skipped.push(format!("{package} — protected package is not installed"));
            continue;
        }
        if choices.keep.contains(package) {
            skipped.push(format!("{package} — explicitly kept; background restrictions skipped"));
            continue;
        }
        let mut ops = vec!["RUN_IN_BACKGROUND"];
        if sdk >= 28 { ops.push("RUN_ANY_IN_BACKGROUND"); }
        for operation in ops {
            let already_ignored = original.app_ops.iter().any(|state| {
                state.package.as_str() == package.as_str() && state.operation == operation && state.mode == "ignore"
            });
            if already_ignored {
                skipped.push(format!("{package} — {operation} was already ignored"));
            } else {
                operations.push(PlannedOperation {
                    description: format!("Restrict {operation} for protected package {package}"),
                    command: DeviceCommand::SetAppOp {
                        package: package.clone(),
                        operation: operation.to_owned(),
                        mode: "ignore".into(),
                    },
                });
            }
        }
        match protected_uids.get(package).copied() {
            Some(uid) if uid >= 10_000 && original.metered_blacklist_uids.as_ref().is_some_and(|uids| uids.contains(&uid)) => {
                skipped.push(format!("{package} — UID {uid} is already on the metered-data blacklist"));
            }
            Some(uid) if uid >= 10_000 && !planned_uids.insert(uid) => {
                skipped.push(format!("{package} — UID {uid} is already covered by another protected package"));
            }
            Some(uid) if uid >= 10_000 => operations.push(PlannedOperation {
                description: format!("Block metered background data for {package} (UID {uid})"),
                command: DeviceCommand::AddMeteredBlacklistUid(uid),
            }),
            Some(uid) => skipped.push(format!("{package} — shared/system UID {uid}; not added to metered blacklist")),
            None => skipped.push(format!("{package} — UID unavailable; metered blacklist entry skipped")),
        }
    }

    Ok(CleanupPlan { operations, skipped })
}

/// Convert a delta restore plan into typed commands and human-readable
/// manual actions. Store apps are never silently reinstalled or downloaded.
pub fn build_restore_operations(plan: &RestorePlan) -> (Vec<PlannedOperation>, Vec<String>) {
    let mut operations = Vec::new();
    let mut manual = Vec::new();
    for action in &plan.actions {
        let (description, command) = match action {
            RestoreAction::SetPackageState { package, state: PackageState::Enabled } => (
                format!("Re-enable {package}"), DeviceCommand::EnablePackage(package.clone())
            ),
            RestoreAction::SetPackageState { package, state: PackageState::Disabled } => (
                format!("Restore disabled state for {package}"), DeviceCommand::DisablePackage(package.clone())
            ),
            RestoreAction::SetPackageState { package, .. } => {
                manual.push(format!("{package} — original package state cannot be recreated safely"));
                continue;
            }
            RestoreAction::PutSetting { namespace, key, value } => (
                format!("Restore {} setting {key}", namespace_name(*namespace)),
                DeviceCommand::PutSetting { namespace: match namespace {
                    crate::snapshot::SettingNamespace::Secure => crate::settings_catalogue::SettingsNamespace::Secure,
                    crate::snapshot::SettingNamespace::Global => crate::settings_catalogue::SettingsNamespace::Global,
                }, key: key.clone(), value: value.clone() }
            ),
            RestoreAction::DeleteSetting { namespace, key } => (
                format!("Delete originally absent {} setting {key}", namespace_name(*namespace)),
                DeviceCommand::DeleteSetting { namespace: match namespace {
                    crate::snapshot::SettingNamespace::Secure => crate::settings_catalogue::SettingsNamespace::Secure,
                    crate::snapshot::SettingNamespace::Global => crate::settings_catalogue::SettingsNamespace::Global,
                }, key: key.clone() }
            ),
            RestoreAction::SetAppOp { package, operation, mode } => (
                format!("Restore {operation}={mode} for {package}"),
                DeviceCommand::SetAppOp { package: package.clone(), operation: operation.clone(), mode: mode.clone() }
            ),
            RestoreAction::SetRestrictBackground(enabled) => (
                format!("Restore Data Saver to {}", if *enabled { "on" } else { "off" }),
                DeviceCommand::SetRestrictBackground(*enabled)
            ),
            RestoreAction::AddMeteredBlacklistUid(uid) if *uid < 10_000 => {
                manual.push(format!("UID {uid} — shared/system network policy requires manual recovery"));
                continue;
            }
            RestoreAction::RemoveMeteredBlacklistUid(uid) if *uid < 10_000 => {
                manual.push(format!("UID {uid} — shared/system network policy requires manual recovery"));
                continue;
            }
            RestoreAction::AddMeteredBlacklistUid(uid) => (
                format!("Restore metered-data blacklist UID {uid}"),
                DeviceCommand::AddMeteredBlacklistUid(*uid)
            ),
            RestoreAction::RemoveMeteredBlacklistUid(uid) => (
                format!("Remove metered-data blacklist UID {uid}"),
                DeviceCommand::RemoveMeteredBlacklistUid(*uid)
            ),
            RestoreAction::ManualUserAppReinstall(package) => {
                manual.push(format!("{package} — reinstall manually from its original source (Amazon Appstore or sideloaded APK)"));
                continue;
            }
            RestoreAction::ManualStateUnavailable(reason) => {
                manual.push((*reason).to_owned());
                continue;
            }
        };
        operations.push(PlannedOperation { description, command });
    }
    (operations, manual)
}

fn namespace_name(namespace: crate::snapshot::SettingNamespace) -> &'static str {
    match namespace {
        crate::snapshot::SettingNamespace::Secure => "secure",
        crate::snapshot::SettingNamespace::Global => "global",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::settings_catalogue::SettingsNamespace;

    fn snapshot() -> DeviceSnapshot {
        DeviceSnapshot {
            device_label: "Fire TV".into(),
            manufacturer: "Amazon".into(),
            model: "AFTMM".into(),
            build_display_id: "FireOS-6.7.1.1".into(),
            android_sdk: 25,
            source_commit: "0123456789abcdef0123456789abcdef01234567".into(),
            packages: BTreeMap::new(),
            user_packages: BTreeSet::new(),
            secure_settings: BTreeMap::new(),
            global_settings: BTreeMap::new(),
            app_ops: Vec::new(),
            restrict_background: Some(false),
            metered_blacklist_uids: Some(BTreeSet::new()),
            user_apps_uninstalled_by_tool: BTreeSet::new(),
        }
    }

    fn inventory() -> PackageInventory {
        PackageInventory::from_pm_outputs(
            "package:com.amazon.ads\npackage:com.amazon.client.metrics\npackage:com.example.player\n",
            "package:com.amazon.ads\npackage:com.amazon.client.metrics\n",
            "",
        ).unwrap()
    }

    #[test]
    fn restore_conversion_keeps_user_app_reinstalls_manual_and_never_changes_system_uids() {
        let plan = RestorePlan { actions: vec![
            RestoreAction::PutSetting {
                namespace: crate::snapshot::SettingNamespace::Secure,
                key: "test_key".into(), value: "original".into(),
            },
            RestoreAction::ManualUserAppReinstall("com.example.player".into()),
            RestoreAction::AddMeteredBlacklistUid(1000),
        ]};
        let (operations, manual) = build_restore_operations(&plan);
        assert_eq!(operations.len(), 1);
        assert!(matches!(&operations[0].command, DeviceCommand::PutSetting { .. }));
        assert!(manual.iter().any(|item| item.contains("Amazon Appstore or sideloaded APK")));
        assert!(manual.iter().any(|item| item.contains("shared/system")));
    }

    #[test]
    fn plan_keeps_alexa_by_default_and_only_uninstalls_named_user_apps() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let remove = ["com.example.player".to_owned()].into_iter().collect();
        let choices = PackageChoices { remove, ..PackageChoices::default() };
        let uids = [("com.amazon.client.metrics".to_owned(), 10025)].into_iter().collect();
        let original = snapshot();
        let plan = build_cleanup_plan(&root, 25, &inventory(), &choices, &uids, &original).unwrap();
        assert!(plan.operations.iter().any(|item| matches!(
            &item.command, DeviceCommand::UninstallPackageForUser { package, owner_confirmed: false }
                if package == "com.example.player"
        )));
        assert!(!plan.operations.iter().any(|item| matches!(
            &item.command, DeviceCommand::UninstallPackageForUser { package, .. }
                if package == "com.amazon.ads"
        )));
        assert!(plan.operations.iter().any(|item| matches!(
            &item.command, DeviceCommand::AddMeteredBlacklistUid(10025)
        )));
        assert!(plan.operations.iter().any(|item| matches!(
            &item.command, DeviceCommand::PutSetting { namespace: SettingsNamespace::Secure, .. }
        )));
    }

    #[test]
    fn plan_skips_shared_system_uids_and_keeps_alexa_unmodified_by_default() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let choices = PackageChoices::default();
        let uids = [("com.amazon.client.metrics".to_owned(), 1000)].into_iter().collect();
        let mut original = snapshot();
        original.restrict_background = Some(true);
        let plan = build_cleanup_plan(&root, 25, &inventory(), &choices, &uids, &original).unwrap();
        assert!(!plan.operations.iter().any(|item| matches!(
            &item.command, DeviceCommand::SetRestrictBackground(_)
        )));
        assert!(!plan.operations.iter().any(|item| matches!(
            &item.command, DeviceCommand::AddMeteredBlacklistUid(_)
        )));
        assert!(plan.skipped.iter().any(|item| item.contains("shared/system UID")));
        assert!(!plan.operations.iter().any(|item| matches!(
            &item.command, DeviceCommand::DisablePackage(package) if package == "com.amazon.alexa"
        )));
    }
}
