//! Offline cleanup planning. This module calculates intended package changes;
//! it never talks to ADB and never executes a device operation.

use std::collections::BTreeSet;

use crate::catalogue::{valid_package_name, FireOsCatalogue};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PackageChoices {
    /// true keeps Alexa; false adds the Alexa package list to cleanup.
    pub keep_alexa: bool,
    pub keep: BTreeSet<String>,
    /// Packages explicitly named by the owner for removal.
    pub remove: BTreeSet<String>,
}

impl Default for PackageChoices {
    fn default() -> Self {
        Self {
            keep_alexa: true,
            keep: BTreeSet::new(),
            remove: BTreeSet::new(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PackageActionKind {
    Disable,
    UninstallForUser,
    SkipKept,
    SkipNeverDisable,
    SkipNotInstalled,
    SkipAlreadyDisabled,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PackageAction {
    pub package: String,
    pub kind: PackageActionKind,
    pub reason: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PackagePlan {
    pub actions: Vec<PackageAction>,
}

pub fn build_package_plan(
    catalogue: &FireOsCatalogue,
    choices: &PackageChoices,
    installed: &BTreeSet<String>,
    system: &BTreeSet<String>,
    disabled: &BTreeSet<String>,
) -> Result<PackagePlan, String> {
    for package in choices.keep.iter().chain(choices.remove.iter()) {
        if !valid_package_name(package) {
            return Err(format!("invalid Android package identifier: {package}"));
        }
    }

    let mut never_disable: BTreeSet<&str> =
        catalogue.never_disable.iter().map(String::as_str).collect();
    // Hard guard independent of downloaded catalogue contents.
    never_disable.insert("com.amazon.vizzini");
    let known: BTreeSet<&str> = catalogue
        .packages
        .iter()
        .chain(catalogue.alexa.iter())
        .chain(catalogue.user_apps.iter())
        .map(String::as_str)
        .chain(choices.remove.iter().map(String::as_str))
        .collect();

    for package in &choices.keep {
        if !known.contains(package.as_str()) {
            return Err(format!(
                "keep choice {package} does not match a maintained removal list or an explicitly named removal"
            ));
        }
    }

    for package in &choices.remove {
        if never_disable.contains(package.as_str()) {
            return Err(format!(
                "refusing to plan removal of protected package {package}"
            ));
        }
        if choices.keep.contains(package) {
            return Err(format!(
                "package {package} is both marked keep and explicitly marked for removal"
            ));
        }
    }

    let mut candidates = Vec::new();
    candidates.extend(catalogue.packages.iter().cloned());
    if !choices.keep_alexa {
        candidates.extend(catalogue.alexa.iter().cloned());
    }

    let mut actions = Vec::new();
    let mut handled = BTreeSet::new();

    for package in candidates {
        if !handled.insert(package.clone()) {
            continue;
        }
        if never_disable.contains(package.as_str()) {
            actions.push(PackageAction {
                package,
                kind: PackageActionKind::SkipNeverDisable,
                reason: "listed in never-disable.txt".into(),
            });
        } else if choices.keep.contains(&package) {
            actions.push(PackageAction {
                package,
                kind: PackageActionKind::SkipKept,
                reason: "explicitly selected to keep".into(),
            });
        } else if !installed.contains(&package) {
            actions.push(PackageAction {
                package,
                kind: PackageActionKind::SkipNotInstalled,
                reason: "not installed on this device".into(),
            });
        } else if disabled.contains(&package) {
            actions.push(PackageAction {
                package,
                kind: PackageActionKind::SkipAlreadyDisabled,
                reason: "already disabled before this run".into(),
            });
        } else {
            actions.push(PackageAction {
                package,
                kind: PackageActionKind::Disable,
                reason: "Amazon cleanup catalogue".into(),
            });
        }
    }

    for package in &choices.remove {
        if !handled.insert(package.clone()) {
            continue;
        }
        if !installed.contains(package) {
            actions.push(PackageAction {
                package: package.clone(),
                kind: PackageActionKind::SkipNotInstalled,
                reason: "owner-named package is not installed".into(),
            });
        } else if system.contains(package) && disabled.contains(package) {
            actions.push(PackageAction {
                package: package.clone(),
                kind: PackageActionKind::SkipAlreadyDisabled,
                reason: "owner-named system app is already disabled".into(),
            });
        } else if system.contains(package) {
            actions.push(PackageAction {
                package: package.clone(),
                kind: PackageActionKind::Disable,
                reason: "owner-named system app; keep installed for restore".into(),
            });
        } else {
            actions.push(PackageAction {
                package: package.clone(),
                kind: PackageActionKind::UninstallForUser,
                reason: "owner explicitly named this user-installed app".into(),
            });
        }
    }

    Ok(PackagePlan { actions })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn catalogue() -> FireOsCatalogue {
        FireOsCatalogue {
            packages: vec!["com.amazon.ads".into(), "com.amazon.vizzini".into()],
            alexa: vec!["com.amazon.alexa".into()],
            user_apps: vec!["com.example.streaming".into()],
            protected: vec!["com.amazon.protected".into()],
            never_disable: vec!["com.amazon.vizzini".into()],
        }
    }

    #[test]
    fn defaults_keep_alexa_and_never_disable_vizzini() {
        let installed = [
            "com.amazon.ads".into(),
            "com.amazon.vizzini".into(),
            "com.amazon.alexa".into(),
        ]
        .into_iter()
        .collect();
        let plan = build_package_plan(
            &catalogue(),
            &PackageChoices::default(),
            &installed,
            &BTreeSet::new(),
            &BTreeSet::new(),
        )
        .unwrap();

        assert_eq!(
            plan.actions,
            vec![
                PackageAction {
                    package: "com.amazon.ads".into(),
                    kind: PackageActionKind::Disable,
                    reason: "Amazon cleanup catalogue".into(),
                },
                PackageAction {
                    package: "com.amazon.vizzini".into(),
                    kind: PackageActionKind::SkipNeverDisable,
                    reason: "listed in never-disable.txt".into(),
                },
            ]
        );
    }

    #[test]
    fn vizzini_is_never_disabled_even_if_catalogue_omits_it() {
        let mut catalogue = catalogue();
        catalogue.never_disable.clear();
        let installed = ["com.amazon.vizzini".into()].into_iter().collect();
        let plan = build_package_plan(
            &catalogue,
            &PackageChoices::default(),
            &installed,
            &BTreeSet::new(),
            &BTreeSet::new(),
        )
        .unwrap();
        assert!(plan.actions.iter().any(|action| {
            action.package == "com.amazon.vizzini"
                && action.kind == PackageActionKind::SkipNeverDisable
        }));
    }

    #[test]
    fn alexa_is_added_only_when_owner_opts_out() {
        let installed = ["com.amazon.alexa".into()].into_iter().collect();
        let plan = build_package_plan(
            &catalogue(),
            &PackageChoices { keep_alexa: false, ..Default::default() },
            &installed,
            &BTreeSet::new(),
            &BTreeSet::new(),
        )
        .unwrap();
        assert!(plan.actions.iter().any(|action| {
            action.package == "com.amazon.alexa" && action.kind == PackageActionKind::Disable
        }));
    }

    #[test]
    fn only_owner_named_user_apps_are_uninstalled() {
        let installed = ["com.example.streaming".into()].into_iter().collect();
        let remove = ["com.example.streaming".into()].into_iter().collect();
        let plan = build_package_plan(
            &catalogue(),
            &PackageChoices { keep_alexa: true, remove, ..Default::default() },
            &installed,
            &BTreeSet::new(),
            &BTreeSet::new(),
        )
        .unwrap();
        assert!(plan.actions.iter().any(|action| {
            action.package == "com.example.streaming"
                && action.kind == PackageActionKind::UninstallForUser
        }));
    }

    #[test]
    fn owner_named_system_apps_are_disabled_not_uninstalled() {
        let installed = ["com.example.streaming".into()].into_iter().collect();
        let system = installed.clone();
        let remove = ["com.example.streaming".into()].into_iter().collect();
        let plan = build_package_plan(
            &catalogue(),
            &PackageChoices { keep_alexa: true, remove, ..Default::default() },
            &installed,
            &system,
            &BTreeSet::new(),
        )
        .unwrap();
        assert!(plan.actions.iter().any(|action| {
            action.package == "com.example.streaming" && action.kind == PackageActionKind::Disable
        }));
    }

    #[test]
    fn refuses_never_disable_and_conflicting_owner_choices() {
        let installed = BTreeSet::new();
        let protected_remove = ["com.amazon.vizzini".into()].into_iter().collect();
        assert!(build_package_plan(
            &catalogue(),
            &PackageChoices { keep_alexa: true, remove: protected_remove, ..Default::default() },
            &installed,
            &BTreeSet::new(),
        )
        .is_err());

        let same = ["com.amazon.ads".into()].into_iter().collect();
        assert!(build_package_plan(
            &catalogue(),
            &PackageChoices { keep_alexa: true, keep: same.clone(), remove: same, },
            &installed,
            &BTreeSet::new(),
        )
        .is_err());
    }
}
