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
    let packages_to_disable: BTreeSet<String> = package_plan
        .actions
        .iter()
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
                description: format!(
                    "Uninstall owner-named user app {} (requires explicit approval)",
                    action.package
                ),
                command: DeviceCommand::UninstallPackageForUser {
                    package: action.package,
                    owner_confirmed: false,
                },
            }),
            _ => skipped.push(format!("{} — {}", action.package, action.reason)),
        }
    }

    // Match debloat.sh's process cleanup for catalogue packages. Protected
    // packages are handled with their AppOps/network policy below.
    for package in &catalogue.packages {
        if inventory.installed.contains(package)
            && !choices.keep.contains(package)
            && !catalogue.never_disable.contains(package)
            && package != "com.amazon.vizzini"
            && !catalogue.protected.contains(package)
            && !(choices.keep_alexa && catalogue.alexa.contains(package))
        {
            if let Some(uid) = protected_uids.get(package).copied() {
                operations.push(PlannedOperation {
                    description: format!("If {package} survives disable, restrict background execution, block metered background data where its UID is app-specific, and force-stop it"),
                    command: DeviceCommand::ForceStopAndRestrictIfRunning {
                        package: package.clone(),
                        uid: (uid >= 10_000).then_some(uid),
                        sdk,
                    },
                });
            } else {
                skipped.push(format!(
                    "{package} — UID unavailable; conditional background/data restriction skipped"
                ));
                operations.push(PlannedOperation {
                    description: format!("Force-stop {package} only if it is still running (UID unavailable; no additional restrictions)"),
                    command: DeviceCommand::ForceStopPackage(package.clone()),
                });
            }
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
            crate::settings_catalogue::SettingsNamespace::Secure => {
                original.secure_settings.get(&key)
            }
            crate::settings_catalogue::SettingsNamespace::Global => {
                original.global_settings.get(&key)
            }
        };
        if original_value.and_then(|value| value.as_deref()) == Some(value.as_str()) {
            skipped.push(format!("{key} — already has the requested value"));
            continue;
        }
        operations.push(PlannedOperation {
            description: format!("Set {} setting {} to {}", namespace.as_str(), key, value),
            command: DeviceCommand::PutSetting {
                namespace,
                key,
                value,
            },
        });
    }

    if let Some(Some(components)) = original.secure_settings.get("screensaver_components") {
        let current_dream = components
            .split(',')
            .next()
            .unwrap_or("")
            .split('/')
            .next()
            .unwrap_or("");
        if packages_to_disable.contains(current_dream) || inventory.disabled.contains(current_dream)
        {
            if inventory.installed.contains("com.android.dreams.basic")
                && !inventory.disabled.contains("com.android.dreams.basic")
            {
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
            description: "Enable Android Data Saver (does not block normal Wi-Fi/Ethernet traffic)"
                .into(),
            command: DeviceCommand::SetRestrictBackground(true),
        });
    }

    for package in &catalogue.protected {
        if !inventory.installed.contains(package) {
            skipped.push(format!("{package} — protected package is not installed"));
            continue;
        }
        if choices.keep.contains(package)
            || (choices.keep_alexa && catalogue.alexa.contains(package))
        {
            skipped.push(format!(
                "{package} — explicitly kept; background restrictions skipped"
            ));
            continue;
        }
        let Some(uid) = protected_uids.get(package).copied() else {
            skipped.push(format!(
                "{package} — UID could not be read; background restrictions skipped"
            ));
            continue;
        };
        if uid < 10_000 {
            skipped.push(format!(
                "{package} — shared system UID {uid}; background restrictions skipped"
            ));
            continue;
        }
        let mut ops = vec!["RUN_IN_BACKGROUND"];
        if sdk >= 28 {
            ops.push("RUN_ANY_IN_BACKGROUND");
        }
        for operation in ops {
            let already_ignored = original.app_ops.iter().any(|state| {
                state.package.as_str() == package.as_str()
                    && state.operation == operation
                    && state.mode == "ignore"
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
        if !catalogue.never_disable.contains(package) && package != "com.amazon.vizzini" {
            operations.push(PlannedOperation {
                description: format!(
                    "Force-stop protected package {package} only if it is still running"
                ),
                command: DeviceCommand::ForceStopPackage(package.clone()),
            });
        }
        match Some(uid) {
            Some(uid)
                if uid >= 10_000
                    && original
                        .metered_blacklist_uids
                        .as_ref()
                        .is_some_and(|uids| uids.contains(&uid)) =>
            {
                skipped.push(format!(
                    "{package} — UID {uid} is already on the metered-data blacklist"
                ));
            }
            Some(uid) if uid >= 10_000 && !planned_uids.insert(uid) => {
                skipped.push(format!(
                    "{package} — UID {uid} is already covered by another protected package"
                ));
            }
            Some(uid) if uid >= 10_000 => operations.push(PlannedOperation {
                description: format!("Block metered background data for {package} (UID {uid})"),
                command: DeviceCommand::AddMeteredBlacklistUid(uid),
            }),
            Some(uid) => skipped.push(format!(
                "{package} — shared/system UID {uid}; not added to metered blacklist"
            )),
            None => skipped.push(format!(
                "{package} — UID unavailable; metered blacklist entry skipped"
            )),
        }
    }

    Ok(CleanupPlan {
        operations,
        skipped,
    })
}

/// Limit restore to mutations that this Control Centre actually verified in
/// its durable cleanup journal. Differences caused by later manual edits are
/// left untouched and reported for review.
pub fn scope_restore_to_verified_cleanup(
    plan: &RestorePlan,
    journal: &crate::operation_journal::OperationJournal,
    current: &crate::snapshot::CurrentDeviceState,
) -> (RestorePlan, usize) {
    use crate::device_command::DeviceCommand;
    use crate::operation_journal::{OperationStatus, WorkflowKind};

    if journal.workflow != WorkflowKind::Cleanup {
        return (
            RestorePlan {
                actions: Vec::new(),
            },
            plan.actions.len(),
        );
    }
    let verified: Vec<&DeviceCommand> = journal
        .operations
        .iter()
        .filter(|item| item.status == OperationStatus::Verified)
        .filter_map(|item| item.command.as_ref())
        .collect();

    let mut retained = Vec::new();
    let mut excluded = 0;
    for action in &plan.actions {
        let belongs_to_cleanup = match action {
            RestoreAction::SetPackageState { package, .. } =>
                current.packages.get(package) == Some(&PackageState::Disabled)
                    && verified.iter().any(|command| matches!(command,
                        DeviceCommand::DisablePackage(name) if name == package)),
            RestoreAction::PutSetting { namespace, key, .. }
            | RestoreAction::DeleteSetting { namespace, key } => {
                let current_value = match namespace {
                    crate::snapshot::SettingNamespace::Secure => current.secure_settings.get(key),
                    crate::snapshot::SettingNamespace::Global => current.global_settings.get(key),
                };
                verified.iter().any(|command| matches!(command,
                    DeviceCommand::PutSetting { namespace: target, key: target_key, value }
                    if target_key == key
                        && current_value.and_then(|value| value.as_deref()) == Some(value.as_str())
                        && match namespace {
                            crate::snapshot::SettingNamespace::Secure => *target == crate::settings_catalogue::SettingsNamespace::Secure,
                            crate::snapshot::SettingNamespace::Global => *target == crate::settings_catalogue::SettingsNamespace::Global,
                        }))
            },
            RestoreAction::SetAppOp { package, operation, .. } => {
                let current_mode = current.app_ops.iter()
                    .find(|item| item.package == *package && item.operation == *operation)
                    .map(|item| item.mode.as_str());
                verified.iter().any(|command| match command {
                    DeviceCommand::SetAppOp { package: target, operation: target_op, mode } =>
                        target == package && target_op == operation && current_mode == Some(mode.as_str()),
                    DeviceCommand::ForceStopAndRestrictIfRunning { package: target, .. } =>
                        target == package
                            && matches!(operation.as_str(), "RUN_IN_BACKGROUND" | "RUN_ANY_IN_BACKGROUND")
                            && current_mode == Some("ignore"),
                    _ => false,
                })
            },
            RestoreAction::SetRestrictBackground(_) =>
                current.restrict_background == Some(true)
                    && verified.iter().any(|command| matches!(command, DeviceCommand::SetRestrictBackground(true))),
            RestoreAction::AddMeteredBlacklistUid(uid)
            | RestoreAction::RemoveMeteredBlacklistUid(uid) =>
                current.metered_blacklist_uids.as_ref().is_some_and(|uids| uids.contains(uid))
                    && verified.iter().any(|command| match command {
                        DeviceCommand::AddMeteredBlacklistUid(target) => target == uid,
                        DeviceCommand::ForceStopAndRestrictIfRunning { uid: Some(target), .. } => target == uid,
                        _ => false,
                    }),
            RestoreAction::ManualUserAppReinstall(package) =>
                !current.packages.contains_key(package)
                    && verified.iter().any(|command| matches!(command,
                        DeviceCommand::UninstallPackageForUser { package: target, .. } if target == package)),
            RestoreAction::ManualStateUnavailable(_) => true,
        };
        if belongs_to_cleanup {
            retained.push(action.clone());
        } else {
            excluded += 1;
        }
    }
    if excluded > 0 {
        retained.push(RestoreAction::ManualStateUnavailable(
            "Some changed captured fields were not verified as DumbTV cleanup operations and were left untouched; review those changes manually",
        ));
    }
    (RestorePlan { actions: retained }, excluded)
}

/// Convert a delta restore plan into typed commands and human-readable
/// manual actions. Store apps are never silently reinstalled or downloaded.
pub fn build_restore_operations(plan: &RestorePlan) -> (Vec<PlannedOperation>, Vec<String>) {
    let mut operations = Vec::new();
    let mut manual = Vec::new();
    for action in &plan.actions {
        let (description, command) = match action {
            RestoreAction::SetPackageState {
                package,
                state: PackageState::Enabled,
            } => (
                format!("Re-enable {package}"),
                DeviceCommand::EnablePackage(package.clone()),
            ),
            RestoreAction::SetPackageState {
                package,
                state: PackageState::Disabled,
            } => (
                format!("Restore disabled state for {package}"),
                DeviceCommand::DisablePackage(package.clone()),
            ),
            RestoreAction::SetPackageState { package, .. } => {
                manual.push(format!(
                    "{package} — original package state cannot be recreated safely"
                ));
                continue;
            }
            RestoreAction::PutSetting {
                namespace,
                key,
                value,
            } => (
                format!("Restore {} setting {key}", namespace_name(*namespace)),
                DeviceCommand::PutSetting {
                    namespace: match namespace {
                        crate::snapshot::SettingNamespace::Secure => {
                            crate::settings_catalogue::SettingsNamespace::Secure
                        }
                        crate::snapshot::SettingNamespace::Global => {
                            crate::settings_catalogue::SettingsNamespace::Global
                        }
                    },
                    key: key.clone(),
                    value: value.clone(),
                },
            ),
            RestoreAction::DeleteSetting { namespace, key } => (
                format!(
                    "Delete originally absent {} setting {key}",
                    namespace_name(*namespace)
                ),
                DeviceCommand::DeleteSetting {
                    namespace: match namespace {
                        crate::snapshot::SettingNamespace::Secure => {
                            crate::settings_catalogue::SettingsNamespace::Secure
                        }
                        crate::snapshot::SettingNamespace::Global => {
                            crate::settings_catalogue::SettingsNamespace::Global
                        }
                    },
                    key: key.clone(),
                },
            ),
            RestoreAction::SetAppOp {
                package,
                operation,
                mode,
            } => (
                format!("Restore {operation}={mode} for {package}"),
                DeviceCommand::SetAppOp {
                    package: package.clone(),
                    operation: operation.clone(),
                    mode: mode.clone(),
                },
            ),
            RestoreAction::SetRestrictBackground(enabled) => (
                format!(
                    "Restore Data Saver to {}",
                    if *enabled { "on" } else { "off" }
                ),
                DeviceCommand::SetRestrictBackground(*enabled),
            ),
            RestoreAction::AddMeteredBlacklistUid(uid) if *uid < 10_000 => {
                manual.push(format!(
                    "UID {uid} — shared/system network policy requires manual recovery"
                ));
                continue;
            }
            RestoreAction::RemoveMeteredBlacklistUid(uid) if *uid < 10_000 => {
                manual.push(format!(
                    "UID {uid} — shared/system network policy requires manual recovery"
                ));
                continue;
            }
            RestoreAction::AddMeteredBlacklistUid(uid) => (
                format!("Restore metered-data blacklist UID {uid}"),
                DeviceCommand::AddMeteredBlacklistUid(*uid),
            ),
            RestoreAction::RemoveMeteredBlacklistUid(uid) => (
                format!("Remove metered-data blacklist UID {uid}"),
                DeviceCommand::RemoveMeteredBlacklistUid(*uid),
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
        operations.push(PlannedOperation {
            description,
            command,
        });
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
            "package:com.amazon.ads\npackage:com.amazon.hedwig\npackage:com.amazon.client.metrics\npackage:com.example.player\n",
            "package:com.amazon.ads\npackage:com.amazon.hedwig\npackage:com.amazon.client.metrics\n",
            "",
        ).unwrap()
    }

    #[test]
    fn restore_conversion_keeps_user_app_reinstalls_manual_and_never_changes_system_uids() {
        let plan = RestorePlan {
            actions: vec![
                RestoreAction::PutSetting {
                    namespace: crate::snapshot::SettingNamespace::Secure,
                    key: "test_key".into(),
                    value: "original".into(),
                },
                RestoreAction::ManualUserAppReinstall("com.example.player".into()),
                RestoreAction::AddMeteredBlacklistUid(1000),
            ],
        };
        let (operations, manual) = build_restore_operations(&plan);
        assert_eq!(operations.len(), 1);
        assert!(matches!(
            &operations[0].command,
            DeviceCommand::PutSetting { .. }
        ));
        assert!(manual
            .iter()
            .any(|item| item.contains("Amazon Appstore or sideloaded APK")));
        assert!(manual.iter().any(|item| item.contains("shared/system")));
    }

    #[test]
    fn plan_keeps_alexa_by_default_and_only_uninstalls_named_user_apps() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let remove = ["com.example.player".to_owned()].into_iter().collect();
        let choices = PackageChoices {
            remove,
            ..PackageChoices::default()
        };
        let uids = [
            ("com.amazon.client.metrics".to_owned(), 10025),
            ("com.amazon.hedwig".to_owned(), 10026),
        ]
        .into_iter()
        .collect();
        let original = snapshot();
        let plan = build_cleanup_plan(&root, 25, &inventory(), &choices, &uids, &original).unwrap();
        assert!(plan.operations.iter().any(|item| matches!(
            &item.command, DeviceCommand::UninstallPackageForUser { package, owner_confirmed: false }
                if package == "com.example.player"
        )));
        assert!(!plan.operations.iter().any(|item| matches!(
            &item.command, DeviceCommand::UninstallPackageForUser { package, .. }
                if package == "com.amazon.hedwig"
        )));
        assert!(plan
            .operations
            .iter()
            .any(|item| matches!(&item.command, DeviceCommand::AddMeteredBlacklistUid(10025))));
        assert!(plan.operations.iter().any(|item| matches!(
            &item.command, DeviceCommand::ForceStopPackage(package)
                if package == "com.amazon.client.metrics"
        )));
        assert!(plan.operations.iter().any(|item| matches!(
            &item.command, DeviceCommand::ForceStopAndRestrictIfRunning { package, uid: Some(10026), sdk: 25 }
                if package == "com.amazon.hedwig"
        )));
        assert!(plan.operations.iter().any(|item| matches!(
            &item.command,
            DeviceCommand::PutSetting {
                namespace: SettingsNamespace::Secure,
                ..
            }
        )));
    }

    #[test]
    fn restore_scope_only_reverts_verified_cleanup_commands() {
        use crate::operation_journal::{OperationJournal, OperationStatus, WorkflowKind};
        let dir = tempfile::tempdir().unwrap();
        let commands = vec![
            (
                "Disable com.amazon.ads".into(),
                DeviceCommand::DisablePackage("com.amazon.ads".into()),
            ),
            (
                "Disable second package".into(),
                DeviceCommand::DisablePackage("com.amazon.other".into()),
            ),
            (
                "Set privacy key".into(),
                DeviceCommand::PutSetting {
                    namespace: SettingsNamespace::Secure,
                    key: "advertising_id".into(),
                    value: "0".into(),
                },
            ),
            (
                "Set second privacy key".into(),
                DeviceCommand::PutSetting {
                    namespace: SettingsNamespace::Secure,
                    key: "manual_key".into(),
                    value: "0".into(),
                },
            ),
            (
                "Restrict AppOp".into(),
                DeviceCommand::SetAppOp {
                    package: "com.amazon.hedwig".into(),
                    operation: "RUN_IN_BACKGROUND".into(),
                    mode: "ignore".into(),
                },
            ),
            (
                "Restrict second AppOp".into(),
                DeviceCommand::SetAppOp {
                    package: "com.amazon.other".into(),
                    operation: "RUN_IN_BACKGROUND".into(),
                    mode: "ignore".into(),
                },
            ),
            (
                "Enable Data Saver".into(),
                DeviceCommand::SetRestrictBackground(true),
            ),
            (
                "Blacklist UID".into(),
                DeviceCommand::AddMeteredBlacklistUid(10025),
            ),
        ];
        let path = OperationJournal::create_with_commands(
            dir.path(),
            WorkflowKind::Cleanup,
            "Amazon",
            "AFTMM",
            "FireOS-6.7.1.1",
            25,
            "0123456789abcdef0123456789abcdef01234567",
            &commands,
        )
        .unwrap();
        let mut journal = OperationJournal::load(&path).unwrap();
        for index in 0..commands.len() {
            journal.mark_running(&path, index).unwrap();
            journal.mark_verified(&path, index).unwrap();
        }
        assert!(journal
            .operations
            .iter()
            .all(|item| item.status == OperationStatus::Verified));

        let plan = RestorePlan {
            actions: vec![
                RestoreAction::SetPackageState {
                    package: "com.amazon.ads".into(),
                    state: PackageState::Enabled,
                },
                RestoreAction::SetPackageState {
                    package: "com.amazon.other".into(),
                    state: PackageState::Enabled,
                },
                RestoreAction::PutSetting {
                    namespace: crate::snapshot::SettingNamespace::Secure,
                    key: "advertising_id".into(),
                    value: "1".into(),
                },
                RestoreAction::PutSetting {
                    namespace: crate::snapshot::SettingNamespace::Secure,
                    key: "manual_key".into(),
                    value: "owner".into(),
                },
                RestoreAction::SetAppOp {
                    package: "com.amazon.hedwig".into(),
                    operation: "RUN_IN_BACKGROUND".into(),
                    mode: "allow".into(),
                },
                RestoreAction::SetAppOp {
                    package: "com.amazon.other".into(),
                    operation: "RUN_IN_BACKGROUND".into(),
                    mode: "allow".into(),
                },
                RestoreAction::SetRestrictBackground(false),
                RestoreAction::RemoveMeteredBlacklistUid(10025),
                RestoreAction::RemoveMeteredBlacklistUid(10026),
            ],
        };
        let current = crate::snapshot::CurrentDeviceState {
            packages: [
                ("com.amazon.ads".into(), PackageState::Disabled),
                ("com.amazon.other".into(), PackageState::Enabled),
            ]
            .into_iter()
            .collect(),
            user_packages: BTreeSet::new(),
            secure_settings: [
                ("advertising_id".into(), Some("0".into())),
                ("manual_key".into(), Some("owner".into())),
            ]
            .into_iter()
            .collect(),
            global_settings: BTreeMap::new(),
            app_ops: vec![
                crate::snapshot::AppOpState {
                    package: "com.amazon.hedwig".into(),
                    operation: "RUN_IN_BACKGROUND".into(),
                    mode: "ignore".into(),
                },
                // A later manual change no longer matches the cleanup target.
                crate::snapshot::AppOpState {
                    package: "com.amazon.other".into(),
                    operation: "RUN_IN_BACKGROUND".into(),
                    mode: "deny".into(),
                },
            ],
            restrict_background: Some(true),
            metered_blacklist_uids: Some([10025, 10026].into_iter().collect()),
        };
        let (scoped, excluded) = scope_restore_to_verified_cleanup(&plan, &journal, &current);
        assert_eq!(excluded, 4);
        assert_eq!(scoped.actions.len(), 6); // five verified changes plus the manual-review notice
        assert!(scoped.actions.iter().any(|item| matches!(item, RestoreAction::SetPackageState { package, .. } if package == "com.amazon.ads")));
        assert!(!scoped.actions.iter().any(|item| matches!(item, RestoreAction::SetPackageState { package, .. } if package == "com.amazon.other")));
        assert!(!scoped.actions.iter().any(
            |item| matches!(item, RestoreAction::PutSetting { key, .. } if key == "manual_key")
        ));
    }

    #[test]
    fn plan_skips_shared_system_uids_and_keeps_alexa_unmodified_by_default() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let choices = PackageChoices::default();
        let uids = [("com.amazon.client.metrics".to_owned(), 1000)]
            .into_iter()
            .collect();
        let mut original = snapshot();
        original.restrict_background = Some(true);
        let plan = build_cleanup_plan(&root, 25, &inventory(), &choices, &uids, &original).unwrap();
        assert!(!plan
            .operations
            .iter()
            .any(|item| matches!(&item.command, DeviceCommand::SetRestrictBackground(_))));
        assert!(!plan
            .operations
            .iter()
            .any(|item| matches!(&item.command, DeviceCommand::AddMeteredBlacklistUid(_))));
        assert!(!plan.operations.iter().any(|item| matches!(
            &item.command, DeviceCommand::ForceStopPackage(package)
                if package == "com.amazon.client.metrics"
        )));
        assert!(plan
            .skipped
            .iter()
            .any(|item| item.contains("shared system UID")));
        assert!(!plan.operations.iter().any(|item| matches!(
            &item.command, DeviceCommand::DisablePackage(package) if package == "com.amazon.alexa"
        )));
    }
}
