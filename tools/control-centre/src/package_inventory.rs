//! Read-only inventory of packages reported by Android's package manager.

use std::collections::BTreeSet;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PackageInventory {
    pub installed: BTreeSet<String>,
    pub system: BTreeSet<String>,
    pub user: BTreeSet<String>,
    pub disabled: BTreeSet<String>,
}

impl PackageInventory {
    /// Build a consistent inventory from the output of the three read-only
    /// package-manager queries: all packages, system packages and disabled packages.
    pub fn from_pm_outputs(
        all_output: &str,
        system_output: &str,
        disabled_output: &str,
    ) -> Result<Self, String> {
        let installed = parse_pm_list_output(all_output)?;
        if installed.is_empty() {
            return Err("package manager returned no installed packages".into());
        }

        let system = parse_pm_list_output(system_output)?;
        let disabled = parse_pm_list_output(disabled_output)?;

        if let Some(package) = system.difference(&installed).next() {
            return Err(format!(
                "system package inventory contains an uninstalled package: {package}"
            ));
        }
        if let Some(package) = disabled.difference(&installed).next() {
            return Err(format!(
                "disabled package inventory contains an uninstalled package: {package}"
            ));
        }

        let user = installed.difference(&system).cloned().collect();
        Ok(Self {
            installed,
            system,
            user,
            disabled,
        })
    }
}

/// Parse package manager output without accepting shell syntax or path data.
/// The special Android framework package android is valid even though it has
/// no dot-separated namespace.
pub fn parse_pm_list_output(output: &str) -> Result<BTreeSet<String>, String> {
    let mut packages = BTreeSet::new();
    for (line_number, line) in output.lines().enumerate() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        let package = line.strip_prefix("package:").ok_or_else(|| {
            format!(
                "unexpected package-manager output at line {}: {line}",
                line_number + 1
            )
        })?;
        let package = package.trim();
        if !valid_inventory_package_name(package) {
            return Err(format!(
                "invalid package name from package manager at line {}",
                line_number + 1
            ));
        }
        packages.insert(package.to_owned());
    }
    Ok(packages)
}

fn valid_inventory_package_name(package: &str) -> bool {
    let mut segments = package.split('.');
    let Some(first) = segments.next() else {
        return false;
    };
    let valid_segment = |segment: &str| {
        !segment.is_empty()
            && segment.as_bytes()[0].is_ascii_alphabetic()
            && segment
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
    };
    valid_segment(first) && segments.all(valid_segment)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifies_framework_system_user_and_disabled_packages() {
        let inventory = PackageInventory::from_pm_outputs(
            "package:android\npackage:com.amazon.settings\npackage:com.amazon.ads\npackage:com.example.player\n",
            "package:android\npackage:com.amazon.settings\npackage:com.amazon.ads\n",
            "package:com.amazon.ads\n",
        )
        .unwrap();

        assert_eq!(inventory.installed.len(), 4);
        assert!(inventory.system.contains("android"));
        assert!(inventory.system.contains("com.amazon.settings"));
        assert!(inventory.user.contains("com.example.player"));
        assert!(!inventory.user.contains("com.amazon.settings"));
        assert!(inventory.disabled.contains("com.amazon.ads"));
    }

    #[test]
    fn rejects_inconsistent_or_malformed_package_manager_output() {
        assert!(PackageInventory::from_pm_outputs("", "", "").is_err());
        assert!(parse_pm_list_output("com.amazon.settings\n").is_err());
        assert!(parse_pm_list_output("package:../bad\n").is_err());
        assert!(PackageInventory::from_pm_outputs(
            "package:com.amazon.settings\n",
            "package:com.amazon.missing\n",
            "",
        )
        .is_err());
        assert!(PackageInventory::from_pm_outputs(
            "package:com.amazon.settings\n",
            "",
            "package:com.amazon.missing\n",
        )
        .is_err());
    }

    #[test]
    fn duplicate_package_lines_are_deduplicated() {
        let packages =
            parse_pm_list_output("package:com.amazon.settings\npackage:com.amazon.settings\n")
                .unwrap();
        assert_eq!(packages.len(), 1);
    }
}
