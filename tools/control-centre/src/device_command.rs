//! Closed set of reviewed Android device commands.
//!
//! No arbitrary shell strings are accepted. Every dynamic token is validated,
//! package operations consult the maintained never-disable policy, and removal
//! of a user app requires an explicit owner-confirmation bit.

use std::collections::BTreeSet;

use crate::settings_catalogue::SettingsNamespace;

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(tag = "kind", content = "arguments", rename_all = "snake_case")]
pub enum DeviceCommand {
    DisablePackage(String),
    EnablePackage(String),
    UninstallPackageForUser { package: String, owner_confirmed: bool },
    ForceStopPackage(String),
    /// Match debloat.sh: only when a cleanup package survives disable, restrict
    /// background execution, block metered background data, then force-stop it.
    ForceStopAndRestrictIfRunning { package: String, uid: Option<u32>, sdk: u32 },
    PutSetting { namespace: SettingsNamespace, key: String, value: String },
    DeleteSetting { namespace: SettingsNamespace, key: String },
    SetAppOp { package: String, operation: String, mode: String },
    SetRestrictBackground(bool),
    AddMeteredBlacklistUid(u32),
    RemoveMeteredBlacklistUid(u32),
}

impl DeviceCommand {
    /// Build one reviewed Android shell-service command. The caller must still
    /// enforce a single selected device, timeout, read-back verification and
    /// original-state snapshot before sending any mutating command.
    pub fn to_adb_shell(
        &self,
        never_disable: &BTreeSet<String>,
    ) -> Result<String, String> {
        match self {
            Self::DisablePackage(package) => {
                validate_protected_package(package, never_disable)?;
                Ok(format!("pm disable-user --user 0 {package}"))
            }
            Self::EnablePackage(package) => {
                validate_protected_package(package, never_disable)?;
                Ok(format!("pm enable --user 0 {package}"))
            }
            Self::UninstallPackageForUser { package, owner_confirmed } => {
                validate_protected_package(package, never_disable)?;
                if !owner_confirmed {
                    return Err("uninstall requires explicit owner confirmation".into());
                }
                Ok(format!("pm uninstall --user 0 {package}"))
            }
            Self::ForceStopPackage(package) => {
                validate_protected_package(package, never_disable)?;
                Ok(format!("am force-stop {package}"))
            }
            Self::ForceStopAndRestrictIfRunning { package, uid, sdk } => {
                validate_protected_package(package, never_disable)?;
                if uid.is_some_and(|value| value < 10_000) || !(25..=30).contains(sdk) {
                    return Err("invalid UID or SDK for conditional background restriction".into());
                }
                Ok(format!("am force-stop {package}"))
            }
            Self::PutSetting { namespace, key, value } => {
                validate_setting_key(key)?;
                validate_setting_value(value)?;
                Ok(format!("settings put {} {key} {}", namespace.as_str(), shell_quote(value)))
            }
            Self::DeleteSetting { namespace, key } => {
                validate_setting_key(key)?;
                Ok(format!("settings delete {} {key}", namespace.as_str()))
            }
            Self::SetAppOp { package, operation, mode } => {
                validate_package(package)?;
                if !valid_operation(operation) || !valid_mode(mode) {
                    return Err("invalid AppOps operation or mode".into());
                }
                Ok(format!("appops set {package} {operation} {mode}"))
            }
            Self::SetRestrictBackground(enabled) => Ok(format!(
                "cmd netpolicy set restrict-background {}",
                if *enabled { "true" } else { "false" }
            )),
            Self::AddMeteredBlacklistUid(uid) | Self::RemoveMeteredBlacklistUid(uid) => {
                if *uid < 10_000 {
                    return Err("refusing to alter a shared/system UID network policy".into());
                }
                let action = if matches!(self, Self::AddMeteredBlacklistUid(_)) {
                    "add"
                } else {
                    "remove"
                };
                Ok(format!("cmd netpolicy {action} restrict-background-blacklist {uid}"))
            }
        }
    }
}

fn validate_protected_package(
    package: &str,
    never_disable: &BTreeSet<String>,
) -> Result<(), String> {
    validate_package(package)?;
    if package == "com.amazon.vizzini" || never_disable.contains(package) {
        return Err(format!("refusing to alter protected package {package}"));
    }
    Ok(())
}

fn validate_package(value: &str) -> Result<(), String> {
    if value == "android" {
        return Err("the Android framework package is not an operation target".into());
    }
    let parts: Vec<&str> = value.split('.').collect();
    if parts.len() < 2
        || parts.iter().any(|part| {
            part.is_empty()
                || !part.as_bytes()[0].is_ascii_alphabetic()
                || !part.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
        })
    {
        return Err("invalid Android package identifier".into());
    }
    Ok(())
}
fn validate_setting_key(value: &str) -> Result<(), String> {
    if value.is_empty()
        || !value.bytes().all(|b| b.is_ascii_alphanumeric() || b"_.:-".contains(&b))
    {
        return Err("invalid Android settings key".into());
    }
    Ok(())
}
fn validate_setting_value(value: &str) -> Result<(), String> {
    if value.len() > 4096 || value.chars().any(char::is_control) {
        return Err("Android settings value is too long or contains control characters".into());
    }
    Ok(())
}

/// POSIX-shell single-quote escaping for a single validated data argument.
fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
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

    fn policy() -> BTreeSet<String> {
        ["com.amazon.settings".to_owned()].into_iter().collect()
    }

    #[test]
    fn builds_only_expected_package_commands() {
        assert_eq!(
            DeviceCommand::DisablePackage("com.amazon.ads".into()).to_adb_shell(&policy()).unwrap(),
            "pm disable-user --user 0 com.amazon.ads"
        );
        assert_eq!(
            DeviceCommand::EnablePackage("com.amazon.ads".into()).to_adb_shell(&policy()).unwrap(),
            "pm enable --user 0 com.amazon.ads"
        );
        assert_eq!(
            DeviceCommand::ForceStopPackage("com.amazon.ads".into()).to_adb_shell(&policy()).unwrap(),
            "am force-stop com.amazon.ads"
        );
    }

    #[test]
    fn never_disable_and_vizzini_are_blocked_at_command_boundary() {
        assert!(DeviceCommand::DisablePackage("com.amazon.vizzini".into()).to_adb_shell(&policy()).is_err());
        assert!(DeviceCommand::EnablePackage("com.amazon.vizzini".into()).to_adb_shell(&policy()).is_err());
        assert!(DeviceCommand::UninstallPackageForUser {
            package: "com.amazon.settings".into(), owner_confirmed: true,
        }.to_adb_shell(&policy()).is_err());
    }

    #[test]
    fn user_app_uninstall_requires_explicit_confirmation() {
        let command = DeviceCommand::UninstallPackageForUser {
            package: "com.example.player".into(), owner_confirmed: false,
        };
        assert!(command.to_adb_shell(&policy()).is_err());
        let confirmed = DeviceCommand::UninstallPackageForUser {
            package: "com.example.player".into(), owner_confirmed: true,
        };
        assert_eq!(confirmed.to_adb_shell(&policy()).unwrap(), "pm uninstall --user 0 com.example.player");
    }

    #[test]
    fn rejects_shell_injection_and_shared_system_uids() {
        assert!(DeviceCommand::DisablePackage("com.example.app; reboot".into()).to_adb_shell(&policy()).is_err());
        assert_eq!(
            DeviceCommand::PutSetting {
                namespace: SettingsNamespace::Secure, key: "safe_key".into(), value: "$(id); reboot".into(),
            }.to_adb_shell(&policy()).unwrap(),
            "settings put secure safe_key '$(id); reboot'"
        );
        assert!(DeviceCommand::AddMeteredBlacklistUid(1000).to_adb_shell(&policy()).is_err());
    }

    #[test]
    fn conditional_background_restriction_rejects_shared_uids_and_unsupported_sdk() {
        let shared = DeviceCommand::ForceStopAndRestrictIfRunning {
            package: "com.amazon.ads".into(), uid: Some(1000), sdk: 25,
        };
        assert!(shared.to_adb_shell(&policy()).is_err());
        let unsupported = DeviceCommand::ForceStopAndRestrictIfRunning {
            package: "com.amazon.ads".into(), uid: Some(10001), sdk: 31,
        };
        assert!(unsupported.to_adb_shell(&policy()).is_err());
        let valid = DeviceCommand::ForceStopAndRestrictIfRunning {
            package: "com.amazon.ads".into(), uid: Some(10001), sdk: 25,
        };
        assert_eq!(valid.to_adb_shell(&policy()).unwrap(), "am force-stop com.amazon.ads");
    }

    #[test]
    fn creates_whitelisted_settings_appops_and_policy_commands() {
        assert_eq!(
            DeviceCommand::PutSetting {
                namespace: SettingsNamespace::Secure, key: "advertising_id".into(), value: "0".into(),
            }.to_adb_shell(&policy()).unwrap(),
            "settings put secure advertising_id '0'"
        );
        assert_eq!(
            DeviceCommand::SetAppOp {
                package: "com.amazon.ads".into(), operation: "RUN_IN_BACKGROUND".into(), mode: "ignore".into(),
            }.to_adb_shell(&policy()).unwrap(),
            "appops set com.amazon.ads RUN_IN_BACKGROUND ignore"
        );
        assert_eq!(
            DeviceCommand::SetRestrictBackground(true).to_adb_shell(&policy()).unwrap(),
            "cmd netpolicy set restrict-background true"
        );
        assert_eq!(
            DeviceCommand::AddMeteredBlacklistUid(10001).to_adb_shell(&policy()).unwrap(),
            "cmd netpolicy add restrict-background-blacklist 10001"
        );
    }
}
