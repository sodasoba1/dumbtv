//! Typed readers for the maintained privacy-settings lists.
//!
//! These files are data, never shell input. Only secure/global namespaces and
//! simple scalar values are accepted.

use std::{collections::BTreeSet, fs, path::Path};

#[derive(
    Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, serde::Serialize, serde::Deserialize,
)]
#[serde(rename_all = "snake_case")]
pub enum SettingsNamespace {
    Secure,
    Global,
}

impl SettingsNamespace {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Secure => "secure",
            Self::Global => "global",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SettingChange {
    pub namespace: SettingsNamespace,
    pub key: String,
    pub value: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SettingsCatalogue {
    pub privacy: Vec<SettingChange>,
    pub alexa_off: Vec<SettingChange>,
    pub restore_defaults: Vec<SettingChange>,
}

impl SettingsCatalogue {
    pub fn load(data_root: &Path) -> Result<Self, String> {
        let fireos = data_root.join("fireos");
        Ok(Self {
            privacy: read_settings_file(&fireos.join("settings.txt"))?,
            alexa_off: read_settings_file(&fireos.join("settings.alexa.txt"))?,
            restore_defaults: read_settings_file(&fireos.join("settings.restore.txt"))?,
        })
    }
}

pub fn read_settings_file(path: &Path) -> Result<Vec<SettingChange>, String> {
    let contents = fs::read_to_string(path)
        .map_err(|error| format!("cannot read settings list {}: {error}", path.display()))?;
    let mut result = Vec::new();
    let mut seen = BTreeSet::new();

    for (line_number, line) in contents.lines().enumerate() {
        let entry = line.split('#').next().unwrap_or("").trim();
        if entry.is_empty() {
            continue;
        }
        let fields: Vec<&str> = entry.split_whitespace().collect();
        if fields.len() != 3 {
            return Err(format!(
                "invalid settings entry in {} at line {}",
                path.display(),
                line_number + 1
            ));
        }

        let namespace = match fields[0] {
            "secure" => SettingsNamespace::Secure,
            "global" => SettingsNamespace::Global,
            _ => {
                return Err(format!(
                    "unsupported settings namespace in {} at line {}",
                    path.display(),
                    line_number + 1
                ));
            }
        };
        let key = fields[1];
        let value = fields[2];
        if !valid_setting_key(key) || !valid_setting_value(value) {
            return Err(format!(
                "unsafe settings key/value in {} at line {}",
                path.display(),
                line_number + 1
            ));
        }
        if !seen.insert((namespace, key.to_owned())) {
            return Err(format!(
                "duplicate settings key in {} at line {}: {}",
                path.display(),
                line_number + 1,
                key
            ));
        }

        result.push(SettingChange {
            namespace,
            key: key.to_owned(),
            value: value.to_owned(),
        });
    }

    Ok(result)
}

fn valid_setting_key(key: &str) -> bool {
    !key.is_empty()
        && key
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b':' | b'.' | b'-'))
}

fn valid_setting_value(value: &str) -> bool {
    !value.is_empty()
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b':' | b'.' | b'-'))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_actual_privacy_alexa_and_restore_lists() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        let catalogue = SettingsCatalogue::load(&root).unwrap();

        assert!(catalogue.privacy.iter().any(|setting| {
            setting.key == "advertisingIdApp:interestBasedAds:value" && setting.value == "0"
        }));
        assert!(catalogue
            .alexa_off
            .iter()
            .any(|setting| { setting.key == "alexa_wake_word_disabled" && setting.value == "1" }));
        assert!(catalogue
            .restore_defaults
            .iter()
            .any(|setting| { setting.key == "animator_duration_scale" && setting.value == "1.0" }));
        assert!(!catalogue
            .privacy
            .iter()
            .any(|setting| { setting.key == "animator_duration_scale" }));
    }

    #[test]
    fn rejects_shell_syntax_unknown_namespaces_and_duplicate_keys() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("settings.txt");
        fs::write(&path, "secure good_key 1\nsecure bad_key $(id)\n").unwrap();
        assert!(read_settings_file(&path).is_err());

        fs::write(&path, "system bad_namespace 1\n").unwrap();
        assert!(read_settings_file(&path).is_err());

        fs::write(&path, "secure same_key 1\nsecure same_key 0\n").unwrap();
        assert!(read_settings_file(&path).is_err());
    }
}
