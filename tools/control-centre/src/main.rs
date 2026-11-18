mod adb_auth;
#[cfg(windows)]
mod cng_auth;
#[cfg(windows)]
mod adb_transport;
mod catalogue;
mod device_profile;
mod package_plan;
mod package_inventory;

use eframe::egui;
use atomicwrites::{AllowOverwrite, AtomicFile};
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
    package_counts: Option<(usize, usize, usize, usize)>,
}

struct ControlCentre {
    shared: Arc<Mutex<SharedState>>,
    device_ip: String,
}

impl Default for ControlCentre {
    fn default() -> Self {
        let active_commit = load_active_source().ok().flatten().map(|m| m.commit);
        Self {
            shared: Arc::new(Mutex::new(SharedState {
                status: "Ready. Device-changing operations are not available in this test build.".into(),
                latest_commit: None,
                active_commit,
                busy: false,
                host_key_md5: None,
                host_key_sha256: None,
                device_profile: None,
                package_counts: None,
            })),
            device_ip: String::new(),
        }
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
            let active = load_active_source().ok().flatten().map(|manifest| manifest.commit);
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
    #[cfg(windows)]
    fn prepare_adb_key(&self) {
        let shared = Arc::clone(&self.shared);
        {
            let mut state = shared.lock().expect("state lock poisoned");
            if state.busy {
                return;
            }
            state.busy = true;
            state.status = "Preparing the Windows-protected ADB host key…".into();
        }
        thread::spawn(move || {
            let result = cng_auth::WindowsCngAdbKey::open_or_create().map(|key| {
                (key.public_key_fingerprint_md5(), key.public_key_fingerprint_sha256())
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
            state.package_counts = None;
            state.status = "Connecting to the specified Fire TV. Check the TV before approving its ADB key prompt…".into();
        }
        thread::spawn(move || {
            let result = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .map_err(|error| format!("cannot start the ADB runtime: {error}"))
                .and_then(|runtime| {
                    runtime.block_on(adb_transport::inspect_device(ip))
                });
            let mut state = shared.lock().expect("state lock poisoned");
            match result {
                Ok(inventory) => {
                    state.status = "Connected. Device properties and package inventory read successfully; no package or settings changes were made.".into();
                    state.device_profile = Some(inventory.profile);
                    state.package_counts = Some((
                        inventory.packages.installed.len(),
                        inventory.packages.system.len(),
                        inventory.packages.user.len(),
                        inventory.packages.disabled.len(),
                    ));
                }
                Err(error) => state.status = format!("ADB inspection failed: {error}"),
            }
            state.busy = false;
        });
    }
}

impl eframe::App for ControlCentre {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        ctx.request_repaint_after(Duration::from_millis(250));
        egui::CentralPanel::default().show(ctx, |ui| {
            ui.heading("DumbTV Control Centre");
            ui.label("Windows test build · AFTMM / Fire OS 6 project");
            ui.separator();

            let (busy, status, latest, active, host_key_md5, host_key_sha256, device_profile, package_counts) = {
                let state = self.shared.lock().expect("state lock poisoned");
                (
                    state.busy,
                    state.status.clone(),
                    state.latest_commit.clone(),
                    state.active_commit.clone(),
                    state.host_key_md5.clone(),
                    state.host_key_sha256.clone(),
                    state.device_profile.clone(),
                    state.package_counts,
                )
            };

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
                        Ok(ip) => self.inspect_fire_tv(ip),
                        Err(_) => {
                            let mut state = self.shared.lock().expect("state lock poisoned");
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
            ui.small("This connection reads getprop and package-manager inventories only. It does not change packages, settings, HOME, Keeper or installed apps. The target is restricted to a private/local IP on TCP 5555.");
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
            ui.small("Test build: update download only. No debloat, package, settings, launcher or Keeper operations are exposed.");
        });
    }
}

fn short_sha(sha: &str) -> &str {
    sha.get(..7).unwrap_or(sha)
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
    if !source.join("README.md").is_file() || !source.join("LICENSE").is_file() {
        return Err("active source folder is missing expected repository files".into());
    }
    Ok(Some(manifest))
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
    fs::create_dir_all(&versions).map_err(|e| format!("cannot create managed source folder: {e}"))?;
    let destination = versions.join(&latest);

    if destination.exists() {
        if !destination.join("README.md").is_file() || !destination.join("LICENSE").is_file() {
            return Err("a source folder already exists for this commit but failed validation; it was left untouched".into());
        }
        write_active_manifest(&latest)?;
        return Ok(format!("Source {} is already downloaded and selected.", short_sha(&latest)));
    }

    let url = format!("https://github.com/{OWNER}/{REPOSITORY}/archive/{latest}.zip");
    let mut response = http_client()?
        .get(url)
        .send()
        .map_err(|e| format!("source download failed: {e}"))?
        .error_for_status()
        .map_err(|e| format!("GitHub rejected the source download: {e}"))?;

    if response.url().scheme() != "https"
        || !matches!(response.url().host_str(), Some("github.com" | "codeload.github.com"))
    {
        return Err("source download redirected outside the official GitHub archive hosts".into());
    }
    if response.content_length().is_some_and(|n| n > MAX_ARCHIVE_BYTES) {
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
    if !staged_root.join("README.md").is_file() || !staged_root.join("LICENSE").is_file() {
        return Err("downloaded archive is missing expected repository files".into());
    }

    fs::rename(&staged_root, &destination)
        .map_err(|e| format!("cannot activate downloaded source version: {e}"))?;
    write_active_manifest(&latest)?;
    Ok(format!(
        "Downloaded and selected official source commit {}. Nothing from the archive was executed.",
        short_sha(&latest)
    ))
}

fn extract_verified_archive(bytes: &[u8], destination: &Path, expected_sha: &str) -> Result<(), String> {
    let cursor = std::io::Cursor::new(bytes);
    let mut archive = zip::ZipArchive::new(cursor).map_err(|e| format!("invalid ZIP archive: {e}"))?;
    if archive.len() > MAX_ARCHIVE_FILES {
        return Err("archive contains too many entries".into());
    }

    let expected_root = format!("{REPOSITORY}-{expected_sha}");
    let mut total_written = 0_u64;
    let mut seen_paths = HashSet::new();

    for index in 0..archive.len() {
        let mut entry = archive.by_index(index).map_err(|e| format!("cannot read ZIP entry: {e}"))?;
        let name = entry.name().to_owned();
        let path = Path::new(&name);

        if entry.unix_mode().is_some_and(|mode| mode & 0o170000 == 0o120000) {
            return Err(format!("archive contains a symbolic link: {name}"));
        }
        if path.components().any(|part| !matches!(part, Component::Normal(_)))
            || !safe_windows_archive_path(&name)
        {
            return Err(format!("archive contains an unsafe path: {name}"));
        }
        let collision_key = name.to_lowercase();
        if !seen_paths.insert(collision_key) {
            return Err(format!("archive contains a duplicate or case-colliding path: {name}"));
        }
        let relative = path
            .strip_prefix(&expected_root)
            .map_err(|_| "archive contains an unexpected top-level directory")?
            .to_path_buf();
        if relative.as_os_str().is_empty() {
            continue;
        }

        let output = destination.join(&expected_root).join(&relative);
        if entry.is_dir() {
            fs::create_dir_all(&output).map_err(|e| format!("cannot create archive directory: {e}"))?;
            continue;
        }

        total_written = total_written
            .checked_add(entry.size())
            .ok_or("archive size overflow")?;
        if total_written > MAX_EXTRACTED_BYTES {
            return Err("extracted source exceeds the 1 GiB safety limit".into());
        }
        if let Some(parent) = output.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("cannot create archive directory: {e}"))?;
        }
        let mut file = fs::File::create(&output).map_err(|e| format!("cannot create extracted file: {e}"))?;
        std::io::copy(&mut entry, &mut file).map_err(|e| format!("cannot extract archive entry: {e}"))?;
        file.flush().map_err(|e| format!("cannot flush extracted file: {e}"))?;
    }
    Ok(())
}

fn safe_windows_archive_path(name: &str) -> bool {
    name.split('/').all(|part| {
        if part.is_empty() {
            return true;
        }
        if part.chars().any(|c| matches!(c, ':' | '\\' | '<' | '>' | '"' | '|' | '?' | '*'))
            || part.chars().any(|c| c.is_control())
            || part.ends_with('.')
            || part.ends_with(' ')
        {
            return false;
        }
        let device_name = part.split('.').next().unwrap_or("").to_ascii_uppercase();
        !matches!(
            device_name.as_str(),
            "CON" | "PRN" | "AUX" | "NUL"
                | "COM1" | "COM2" | "COM3" | "COM4" | "COM5"
                | "COM6" | "COM7" | "COM8" | "COM9"
                | "LPT1" | "LPT2" | "LPT3" | "LPT4" | "LPT5"
                | "LPT6" | "LPT7" | "LPT8" | "LPT9"
        )
    })
}

fn write_active_manifest(commit: &str) -> Result<(), String> {
    if !valid_sha(commit) {
        return Err("refusing to write an invalid commit identifier".into());
    }
    let path = active_manifest_path()?;
    let parent = path.parent().ok_or("invalid application data path")?;
    fs::create_dir_all(parent).map_err(|e| format!("cannot create application data folder: {e}"))?;
    let manifest = ActiveSource {
        repository: format!("{OWNER}/{REPOSITORY}"),
        commit: commit.to_owned(),
    };
    let bytes = serde_json::to_vec_pretty(&manifest).map_err(|e| format!("cannot encode manifest: {e}"))?;
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
        "DumbTV Control Centre",
        options,
        Box::new(|_creation_context| Ok(Box::new(ControlCentre::default()))),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

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
        assert!(safe_windows_archive_path("dumbtv/tools/control-centre/README.md"));
    }

    #[test]
    fn rejects_archive_path_traversal() {
        let mut bytes = Vec::new();
        {
            let mut writer = zip::ZipWriter::new(std::io::Cursor::new(&mut bytes));
            writer
                .start_file("dumbtv-0123456789abcdef0123456789abcdef01234567/../../outside.txt", zip::write::SimpleFileOptions::default())
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
