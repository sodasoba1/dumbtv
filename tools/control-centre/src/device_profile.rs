//! Device identity and compatibility gates shared by ADB preflight and the UI.
//!
//! The checks intentionally mirror the maintained scripts: cleanup supports
//! Amazon Fire OS SDK 25 through 30; the launcher is restricted to API 25.
//! A newer SDK is reported as unvalidated rather than guessed to be compatible.

use std::collections::BTreeMap;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceProfile {
    pub manufacturer: String,
    pub model: String,
    pub build_display_id: String,
    pub android_release: String,
    pub sdk: u32,
    pub abi: String,
    pub kernel: String,
    pub verified_boot_state: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FireOsGeneration {
    FireOs6,
    FireOs7,
    FireOs8,
    NewerThanValidated,
    OlderThanSupported,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Compatibility {
    Supported,
    UnsupportedManufacturer,
    AndroidTooOld,
    NewerThanValidated,
    LauncherRequiresApi25,
}

impl DeviceProfile {
    /// Construct a profile from Android getprop key/value pairs.
    pub fn from_properties(properties: &BTreeMap<String, String>) -> Result<Self, String> {
        let required = |key: &str| -> Result<String, String> {
            properties
                .get(key)
                .map(|value| value.trim().to_owned())
                .filter(|value| !value.is_empty())
                .ok_or_else(|| format!("device did not report required property {key}"))
        };
        let optional = |key: &str| {
            properties
                .get(key)
                .map(|value| value.trim().to_owned())
                .unwrap_or_default()
        };

        let sdk_text = required("ro.build.version.sdk")?;
        let sdk = sdk_text
            .parse::<u32>()
            .map_err(|_| format!("device reported an invalid Android SDK level: {sdk_text}"))?;

        Ok(Self {
            manufacturer: required("ro.product.manufacturer")?,
            model: optional("ro.product.model"),
            build_display_id: optional("ro.build.display.id"),
            android_release: optional("ro.build.version.release"),
            sdk,
            abi: optional("ro.product.cpu.abi"),
            kernel: properties.get("kernel.release").cloned().unwrap_or_default(),
            verified_boot_state: properties
                .get("ro.boot.verifiedbootstate")
                .cloned()
                .unwrap_or_default(),
        })
    }

    /// Parse the normal getprop output format: [key]: [value].
    pub fn parse_getprop_output(output: &str) -> Result<Self, String> {
        let mut properties = BTreeMap::new();
        for line in output.lines() {
            let Some((key, value)) = line.trim().split_once("]: [") else {
                continue;
            };
            let Some(key) = key.strip_prefix('[') else {
                continue;
            };
            let Some(value) = value.strip_suffix(']') else {
                continue;
            };
            if !key.is_empty() {
                properties.insert(key.to_owned(), value.to_owned());
            }
        }
        Self::from_properties(&properties)
    }

    pub fn is_amazon_device(&self) -> bool {
        self.manufacturer.starts_with("Amazon")
    }

    pub fn fire_os_generation(&self) -> FireOsGeneration {
        match self.sdk {
            25..=27 => FireOsGeneration::FireOs6,
            28..=29 => FireOsGeneration::FireOs7,
            30 => FireOsGeneration::FireOs8,
            0..=24 => FireOsGeneration::OlderThanSupported,
            _ => FireOsGeneration::NewerThanValidated,
        }
    }

    /// Cleanup scripts support Amazon Fire OS 6, 7 and 8 (SDK 25–30).
    pub fn cleanup_compatibility(&self) -> Compatibility {
        if !self.is_amazon_device() {
            Compatibility::UnsupportedManufacturer
        } else {
            match self.fire_os_generation() {
                FireOsGeneration::OlderThanSupported => Compatibility::AndroidTooOld,
                FireOsGeneration::NewerThanValidated => Compatibility::NewerThanValidated,
                FireOsGeneration::FireOs6 | FireOsGeneration::FireOs7 | FireOsGeneration::FireOs8 => {
                    Compatibility::Supported
                }
            }
        }
    }

    /// DumbTV HOME/Keeper installation remains Fire OS 6 / Android API 25 only.
    pub fn launcher_compatibility(&self) -> Compatibility {
        if !self.is_amazon_device() {
            Compatibility::UnsupportedManufacturer
        } else if self.sdk == 25 {
            Compatibility::Supported
        } else {
            Compatibility::LauncherRequiresApi25
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn properties(manufacturer: &str, sdk: &str) -> BTreeMap<String, String> {
        [
            ("ro.product.manufacturer", manufacturer),
            ("ro.product.model", "AFTMM"),
            ("ro.build.display.id", "PS7613/"),
            ("ro.build.version.release", "7.1.2"),
            ("ro.build.version.sdk", sdk),
            ("ro.product.cpu.abi", "armeabi-v7a"),
        ]
        .into_iter()
        .map(|(key, value)| (key.to_owned(), value.to_owned()))
        .collect()
    }

    #[test]
    fn fire_os_6_cleanup_and_launcher_are_supported_on_api_25() {
        let profile = DeviceProfile::from_properties(&properties("Amazon", "25")).unwrap();
        assert_eq!(profile.fire_os_generation(), FireOsGeneration::FireOs6);
        assert_eq!(profile.cleanup_compatibility(), Compatibility::Supported);
        assert_eq!(profile.launcher_compatibility(), Compatibility::Supported);
    }

    #[test]
    fn fire_os_7_and_8_allow_cleanup_but_not_launcher_install() {
        for (sdk, generation) in [
            ("28", FireOsGeneration::FireOs7),
            ("30", FireOsGeneration::FireOs8),
        ] {
            let profile = DeviceProfile::from_properties(&properties("Amazon", sdk)).unwrap();
            assert_eq!(profile.fire_os_generation(), generation);
            assert_eq!(profile.cleanup_compatibility(), Compatibility::Supported);
            assert_eq!(profile.launcher_compatibility(), Compatibility::LauncherRequiresApi25);
        }
    }

    #[test]
    fn refuses_non_amazon_devices_and_unvalidated_sdk_levels() {
        let other = DeviceProfile::from_properties(&properties("Google", "30")).unwrap();
        assert_eq!(other.cleanup_compatibility(), Compatibility::UnsupportedManufacturer);

        let newer = DeviceProfile::from_properties(&properties("Amazon", "35")).unwrap();
        assert_eq!(newer.cleanup_compatibility(), Compatibility::NewerThanValidated);

        let older = DeviceProfile::from_properties(&properties("Amazon", "24")).unwrap();
        assert_eq!(older.cleanup_compatibility(), Compatibility::AndroidTooOld);
    }

    #[test]
    fn parses_getprop_output_and_keeps_optional_values_optional() {
        let output = "[ro.product.manufacturer]: [Amazon]\n[ro.product.model]: [AFTMM]\n[ro.build.display.id]: [PS7613/]\n[ro.build.version.release]: [7.1.2]\n[ro.build.version.sdk]: [25]\n[ro.product.cpu.abi]: [armeabi-v7a]\n";
        let profile = DeviceProfile::parse_getprop_output(output).unwrap();
        assert_eq!(profile.model, "AFTMM");
        assert_eq!(profile.sdk, 25);
        assert!(profile.kernel.is_empty());
        assert!(profile.verified_boot_state.is_empty());
    }

    #[test]
    fn rejects_missing_or_invalid_sdk_properties() {
        let mut missing = properties("Amazon", "25");
        missing.remove("ro.build.version.sdk");
        assert!(DeviceProfile::from_properties(&missing).is_err());

        let invalid = properties("Amazon", "unknown");
        assert!(DeviceProfile::from_properties(&invalid).is_err());
    }
}
