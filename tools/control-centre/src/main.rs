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
}

struct ControlCentre {
    shared: Arc<Mutex<SharedState>>,
}

impl Default for ControlCentre {
    fn default() -> Self {
        let active_commit = load_active_source().ok().flatten().map(|m| m.commit);
        Self {
            shared: Arc::new(Mutex::new(SharedState {
                status: "Ready. No device changes are available in this test build.".into(),
                latest_commit: None,
                active_commit,
                busy: false,
            })),
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

impl eframe::App for ControlCentre {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        ctx.request_repaint_after(Duration::from_millis(250));
        egui::CentralPanel::default().show(ctx, |ui| {
            ui.heading("DumbTV Control Centre");
            ui.label("Windows test build · AFTMM / Fire OS 6 project");
            ui.separator();

            let (busy, status, latest, active) = {
                let state = self.shared.lock().expect("state lock poisoned");
                (
                    state.busy,
                    state.status.clone(),
                    state.latest_commit.clone(),
                    state.active_commit.clone(),
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
            ui.label(format!("Downloaded source: {}", active.as_deref().map(short_sha).unwrap_or("Not downloaded")));
            ui.label(format!("Latest checked commit: {}", latest.as_deref().map(short_sha).unwrap_or("Not checked")));
            ui.label("Source updates are extracted into a versioned managed folder. Existing folders are never overwritten, and downloaded scripts are not run.");
            ui.separator();

            ui.heading("Fire TV connection");
            ui.label("ADB support is intentionally not enabled in this first test build.");
            ui.label("The Rust ADB library is still undergoing dependency/security review. This build will not ask you to install Python, Bash, Git or platform-tools, and it will not run shell commands on your Stick.");
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
            || name.contains(':')
            || name.split('/').any(|part| part.ends_with('.') || part.ends_with(' '))
        {
            return Err(format!("archive contains an unsafe path: {name}"));
        }
        let collision_key = name.to_lowercase();
        if !seen_paths.insert(collision_key) {
            return Err(format!("archive contains a duplicate or case-colliding path: {name}"));
        }
        let mut components = path.components();
        match components.next() {
            Some(Component::Normal(root)) if root == expected_root.as_str() => {}
            _ => return Err("archive contains an unexpected top-level directory".into()),
        }
        let relative: PathBuf = components.collect();
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
