//! Rust readers for the maintained Fire OS package catalogues.
//!
//! The source archive is data only: these functions parse the existing text
//! lists without executing or sourcing shell files.

use std::{
    collections::HashSet,
    fs,
    path::{Path, PathBuf},
};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FireOsCatalogue {
    pub packages: Vec<String>,
    pub alexa: Vec<String>,
    pub user_apps: Vec<String>,
    pub protected: Vec<String>,
    pub never_disable: Vec<String>,
}

impl FireOsCatalogue {
    pub fn load(data_root: &Path, sdk: u32) -> Result<Self, String> {
        let packages_path = generation_file(data_root, sdk, "packages.txt");
        let protected_path = generation_file(data_root, sdk, "protected.txt");
        let never_disable_path = generation_file(data_root, sdk, "never-disable.txt");

        Ok(Self {
            packages: read_package_list(&packages_path)?,
            // Alexa and user-apps are intentionally shared by Fire OS 6/7/8.
            alexa: read_package_list(&data_root.join("fireos").join("alexa.txt"))?,
            user_apps: read_package_list(&data_root.join("fireos").join("user-apps.txt"))?,
            protected: read_package_list(&protected_path)?,
            never_disable: read_package_list(&never_disable_path)?,
        })
    }
}

/// Mirrors scripts/lib.sh: SDK 25–27 prefers fireos6/<name> when present;
/// otherwise the shared fireos/<name> file is used.
fn generation_file(data_root: &Path, sdk: u32, name: &str) -> PathBuf {
    let fireos6 = data_root.join("fireos6").join(name);
    if (25..28).contains(&sdk) && fireos6.is_file() {
        fireos6
    } else {
        data_root.join("fireos").join(name)
    }
}

/// Parse the existing list format: strip comments, trim whitespace, use the
/// first field, preserve order and remove duplicate package identifiers.
pub fn read_package_list(path: &Path) -> Result<Vec<String>, String> {
    let contents = fs::read_to_string(path)
        .map_err(|error| format!("cannot read package list {}: {error}", path.display()))?;
    let mut packages = Vec::new();
    let mut seen = HashSet::new();

    for (line_number, line) in contents.lines().enumerate() {
        let entry = line.split('#').next().unwrap_or("").trim();
        let Some(package) = entry.split_whitespace().next() else {
            continue;
        };
        if !valid_package_name(package) {
            return Err(format!(
                "invalid package identifier in {} at line {}: {package}",
                path.display(),
                line_number + 1
            ));
        }
        if seen.insert(package.to_owned()) {
            packages.push(package.to_owned());
        }
    }

    Ok(packages)
}

pub fn valid_package_name(package: &str) -> bool {
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
    valid_segment(first) && segments.all(valid_segment) && package.contains('.')
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn parses_comments_whitespace_and_deduplicates_without_reordering() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("packages.txt");
        let mut file = fs::File::create(&path).unwrap();
        writeln!(file, "# header").unwrap();
        writeln!(file, "  com.amazon.first   # comment").unwrap();
        writeln!(file, "com.amazon.second").unwrap();
        writeln!(file, "com.amazon.first # duplicate").unwrap();
        assert_eq!(
            read_package_list(&path).unwrap(),
            vec!["com.amazon.first", "com.amazon.second"]
        );
    }

    #[test]
    fn rejects_malformed_package_entries_instead_of_silently_dropping_them() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("packages.txt");
        fs::write(&path, "com.amazon.good\nnot-a-package\n").unwrap();
        assert!(read_package_list(&path).is_err());
        assert!(valid_package_name("com.amazon.good"));
        assert!(!valid_package_name("com"));
        assert!(!valid_package_name("com..amazon"));
        assert!(!valid_package_name("1com.amazon"));
        assert!(!valid_package_name("com.amazon;pm"));
    }

    #[test]
    fn fire_os_6_uses_its_own_lists_and_falls_back_when_a_file_is_missing() {
        let dir = tempfile::tempdir().unwrap();
        let fireos = dir.path().join("fireos");
        let fireos6 = dir.path().join("fireos6");
        fs::create_dir_all(&fireos).unwrap();
        fs::create_dir_all(&fireos6).unwrap();
        fs::write(fireos.join("packages.txt"), "com.amazon.shared\n").unwrap();
        fs::write(fireos6.join("packages.txt"), "com.amazon.fireos6\n").unwrap();
        fs::write(fireos.join("protected.txt"), "com.amazon.protected\n").unwrap();
        fs::write(fireos.join("never-disable.txt"), "com.amazon.vizzini\n").unwrap();
        fs::write(fireos.join("alexa.txt"), "com.amazon.alexa\n").unwrap();
        fs::write(fireos.join("user-apps.txt"), "com.example.userapp\n").unwrap();

        let profile = FireOsCatalogue::load(dir.path(), 25).unwrap();
        assert_eq!(profile.packages, vec!["com.amazon.fireos6"]);
        assert_eq!(profile.protected, vec!["com.amazon.protected"]);
        assert_eq!(profile.alexa, vec!["com.amazon.alexa"]);

        let fallback = FireOsCatalogue::load(dir.path(), 25).unwrap();
        assert_eq!(fallback.protected, vec!["com.amazon.protected"]);
    }

    #[test]
    fn maintained_repository_catalogues_are_valid_and_keep_vizzini_protected() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
        for sdk in [25, 28, 30] {
            let catalogue = FireOsCatalogue::load(&root, sdk).unwrap();
            assert!(!catalogue.packages.is_empty());
            assert!(catalogue
                .never_disable
                .iter()
                .any(|p| p == "com.amazon.vizzini"));
            assert!(!catalogue.packages.iter().any(|p| p == "com.amazon.vizzini"));
            assert!(catalogue.packages.iter().all(|p| valid_package_name(p)));
            assert!(catalogue
                .never_disable
                .iter()
                .all(|p| valid_package_name(p)));
        }
    }

    #[test]
    fn fire_os_7_and_8_use_shared_fireos_lists() {
        let dir = tempfile::tempdir().unwrap();
        let fireos = dir.path().join("fireos");
        fs::create_dir_all(&fireos).unwrap();
        for name in [
            "packages.txt",
            "protected.txt",
            "never-disable.txt",
            "alexa.txt",
            "user-apps.txt",
        ] {
            fs::write(fireos.join(name), "com.amazon.shared\n").unwrap();
        }
        for sdk in [28, 30] {
            let catalogue = FireOsCatalogue::load(dir.path(), sdk).unwrap();
            assert_eq!(catalogue.packages, vec!["com.amazon.shared"]);
        }
    }
}
