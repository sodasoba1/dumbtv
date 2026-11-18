//! Durable, privacy-minimised journal for device-changing workflows.
//!
//! The journal records operation descriptions and verification state only. It
//! deliberately excludes device IPs, ADB serials, host keys and raw command output.
//! A journal is evidence for recovery, not permission to resume blindly: after an
//! interruption the device must be re-inspected before any further write.

use atomicwrites::{AllowOverwrite, AtomicFile};
use serde::{Deserialize, Serialize};
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
    time::{SystemTime, UNIX_EPOCH},
};

const SCHEMA_VERSION: u32 = 1;
static JOURNAL_COUNTER: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum WorkflowKind {
    Cleanup,
    Restore,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum OperationStatus {
    Pending,
    Running,
    Verified,
    Failed,
    NotAttempted,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct JournalOperation {
    pub description: String,
    pub status: OperationStatus,
    pub detail: Option<String>,
    /// Exact typed intent, never an arbitrary shell command. Older journals omit it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub command: Option<crate::device_command::DeviceCommand>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OperationJournal {
    pub schema_version: u32,
    pub workflow: WorkflowKind,
    pub created_unix_seconds: u64,
    pub manufacturer: String,
    pub model: String,
    pub build_display_id: String,
    pub android_sdk: u32,
    pub source_commit: String,
    pub operations: Vec<JournalOperation>,
    pub finished: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workflow_error: Option<String>,
}

impl OperationJournal {
    pub fn create(
        root: &Path,
        workflow: WorkflowKind,
        manufacturer: &str,
        model: &str,
        build_display_id: &str,
        android_sdk: u32,
        source_commit: &str,
        descriptions: &[String],
    ) -> Result<PathBuf, String> {
        if descriptions.is_empty() {
            return Err("cannot create an operation journal for an empty workflow".into());
        }
        if descriptions.len() > 10_000
            || descriptions.iter().any(|item| item.is_empty() || item.len() > 1024 || item.chars().any(char::is_control))
        {
            return Err("operation journal contains an invalid or excessive operation description".into());
        }
        if source_commit.len() != 40 || !source_commit.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err("operation journal requires a full source commit identifier".into());
        }
        let created = SystemTime::now().duration_since(UNIX_EPOCH)
            .map_err(|_| "system clock is before the Unix epoch")?.as_secs();
        fs::create_dir_all(root).map_err(|e| format!("cannot create journal folder: {e}"))?;
        let nonce = JOURNAL_COUNTER.fetch_add(1, Ordering::Relaxed);
        let label = match workflow { WorkflowKind::Cleanup => "cleanup", WorkflowKind::Restore => "restore" };
        let path = root.join(format!("{label}-{created}-{}-{nonce}.json", std::process::id()));
        let journal = Self {
            schema_version: SCHEMA_VERSION,
            workflow,
            created_unix_seconds: created,
            manufacturer: manufacturer.to_owned(),
            model: model.to_owned(),
            build_display_id: build_display_id.to_owned(),
            android_sdk,
            source_commit: source_commit.to_owned(),
            operations: descriptions.iter().map(|description| JournalOperation {
                description: description.clone(),
                status: OperationStatus::Pending,
                detail: None,
                command: None,
            }).collect(),
            finished: false,
            workflow_error: None,
        };
        journal.save(&path)?;
        Ok(path)
    }

    /// Create a journal that also retains the reviewed typed command for each step.
    /// This supports selective recovery without storing raw ADB shell strings.
    pub fn create_with_commands(
        root: &Path,
        workflow: WorkflowKind,
        manufacturer: &str,
        model: &str,
        build_display_id: &str,
        android_sdk: u32,
        source_commit: &str,
        operations: &[(String, crate::device_command::DeviceCommand)],
    ) -> Result<PathBuf, String> {
        let descriptions: Vec<String> = operations.iter().map(|(description, _)| description.clone()).collect();
        let path = Self::create(
            root, workflow, manufacturer, model, build_display_id, android_sdk,
            source_commit, &descriptions,
        )?;
        let mut journal = Self::load(&path)?;
        for (item, (_, command)) in journal.operations.iter_mut().zip(operations) {
            item.command = Some(command.clone());
        }
        journal.save(&path)?;
        Ok(path)
    }

    pub fn load(path: &Path) -> Result<Self, String> {
        let bytes = fs::read(path).map_err(|e| format!("cannot read operation journal: {e}"))?;
        if bytes.len() > 4 * 1024 * 1024 {
            return Err("operation journal exceeds the 4 MiB safety limit".into());
        }
        let journal: Self = serde_json::from_slice(&bytes)
            .map_err(|e| format!("operation journal is invalid: {e}"))?;
        journal.validate()?;
        Ok(journal)
    }

    pub fn mark_running(&mut self, path: &Path, index: usize) -> Result<(), String> {
        let operation = self.operations.get_mut(index).ok_or("journal operation index is out of range")?;
        if operation.status != OperationStatus::Pending {
            return Err("journal operation is not pending; refusing to execute it again".into());
        }
        operation.status = OperationStatus::Running;
        operation.detail = None;
        self.save(path)
    }

    pub fn mark_verified(&mut self, path: &Path, index: usize) -> Result<(), String> {
        self.mark_verified_with_detail(path, index, None)
    }

    pub fn mark_verified_with_detail(
        &mut self,
        path: &Path,
        index: usize,
        detail: Option<&str>,
    ) -> Result<(), String> {
        let operation = self.operations.get_mut(index).ok_or("journal operation index is out of range")?;
        if operation.status != OperationStatus::Running {
            return Err("journal operation was not marked running".into());
        }
        operation.status = OperationStatus::Verified;
        operation.detail = detail.map(|value| value.chars().take(512).collect());
        self.save(path)
    }

    pub fn mark_failed(&mut self, path: &Path, index: usize, detail: &str) -> Result<(), String> {
        let operation = self.operations.get_mut(index).ok_or("journal operation index is out of range")?;
        if operation.status != OperationStatus::Running {
            return Err("journal operation was not marked running".into());
        }
        operation.status = OperationStatus::Failed;
        operation.detail = Some(detail.chars().take(512).collect());
        self.save(path)
    }

    pub fn finish_failed(&mut self, path: &Path) -> Result<(), String> {
        if !self.operations.iter().any(|item| item.status == OperationStatus::Failed)
            || self.operations.iter().any(|item| item.status == OperationStatus::Running)
        {
            return Err("cannot close a failed journal without a recorded failure and no running operation".into());
        }
        for operation in &mut self.operations {
            if operation.status == OperationStatus::Pending {
                operation.status = OperationStatus::NotAttempted;
            }
        }
        self.finished = true;
        self.save(path)
    }

    /// Close a fully executed journal while retaining a failed final audit.
    pub fn finish_with_error(&mut self, path: &Path, detail: &str) -> Result<(), String> {
        if self.operations.iter().any(|item| item.status == OperationStatus::Running || item.status == OperationStatus::Pending) {
            return Err("cannot close a journal with pending or running operations".into());
        }
        self.workflow_error = Some(detail.chars().take(512).collect());
        self.finished = true;
        self.save(path)
    }

    pub fn finish(&mut self, path: &Path) -> Result<(), String> {
        if self.operations.iter().any(|item| item.status == OperationStatus::Running || item.status == OperationStatus::Pending) {
            return Err("cannot finish a journal with pending or running operations".into());
        }
        self.finished = true;
        self.save(path)
    }

    fn validate(&self) -> Result<(), String> {
        if self.schema_version != SCHEMA_VERSION
            || self.operations.is_empty()
            || self.operations.len() > 10_000
            || self.source_commit.len() != 40
            || !self.source_commit.bytes().all(|b| b.is_ascii_hexdigit())
            || self.manufacturer.len() > 128
            || self.model.len() > 128
            || self.build_display_id.len() > 256
            || self.operations.iter().any(|item| item.description.is_empty() || item.description.len() > 1024)
            || self.workflow_error.as_deref().is_some_and(|value| value.len() > 512)
        {
            return Err("operation journal failed schema validation".into());
        }
        if self.finished && self.operations.iter().any(|item| matches!(item.status, OperationStatus::Pending | OperationStatus::Running)) {
            return Err("finished operation journal contains unfinished operations".into());
        }
        Ok(())
    }

    fn save(&self, path: &Path) -> Result<(), String> {
        self.validate()?;
        let bytes = serde_json::to_vec_pretty(self).map_err(|e| format!("cannot encode operation journal: {e}"))?;
        let parent = path.parent().ok_or("invalid operation journal path")?;
        AtomicFile::new(path, AllowOverwrite)
            .write(|file| file.write_all(&bytes))
            .map_err(|e| format!("cannot persist operation journal: {e}"))?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn profile_values() -> (&'static str, &'static str, &'static str, &'static str) {
        ("Amazon", "AFTMM", "FireOS-6.7.1.1", "0123456789abcdef0123456789abcdef01234567")
    }

    #[test]
    fn journal_persists_each_verified_step_and_rejects_reexecution() {
        let dir = tempfile::tempdir().unwrap();
        let (manufacturer, model, build, commit) = profile_values();
        let path = OperationJournal::create(
            dir.path(), WorkflowKind::Cleanup, manufacturer, model, build, 25, commit,
            &["Disable package".into(), "Set privacy setting".into()],
        ).unwrap();
        let mut journal = OperationJournal::load(&path).unwrap();
        journal.mark_running(&path, 0).unwrap();
        journal.mark_verified(&path, 0).unwrap();
        assert!(journal.mark_running(&path, 0).is_err());
        journal.mark_running(&path, 1).unwrap();
        journal.mark_failed(&path, 1, "read-back failed").unwrap();
        let loaded = OperationJournal::load(&path).unwrap();
        assert_eq!(loaded.operations[0].status, OperationStatus::Verified);
        assert_eq!(loaded.operations[1].status, OperationStatus::Failed);
        assert!(!loaded.finished);
    }

    #[test]
    fn failed_workflow_closes_with_later_steps_marked_not_attempted() {
        let dir = tempfile::tempdir().unwrap();
        let (manufacturer, model, build, commit) = profile_values();
        let path = OperationJournal::create(
            dir.path(), WorkflowKind::Cleanup, manufacturer, model, build, 25, commit,
            &["First operation".into(), "Second operation".into()],
        ).unwrap();
        let mut journal = OperationJournal::load(&path).unwrap();
        journal.mark_running(&path, 0).unwrap();
        journal.mark_failed(&path, 0, "verification failed").unwrap();
        journal.finish_failed(&path).unwrap();
        let loaded = OperationJournal::load(&path).unwrap();
        assert!(loaded.finished);
        assert_eq!(loaded.operations[0].status, OperationStatus::Failed);
        assert_eq!(loaded.operations[1].status, OperationStatus::NotAttempted);
    }

    #[test]
    fn finished_journal_requires_every_operation_to_be_resolved() {
        let dir = tempfile::tempdir().unwrap();
        let (manufacturer, model, build, commit) = profile_values();
        let path = OperationJournal::create(
            dir.path(), WorkflowKind::Restore, manufacturer, model, build, 25, commit,
            &["Restore setting".into()],
        ).unwrap();
        let mut journal = OperationJournal::load(&path).unwrap();
        assert!(journal.finish(&path).is_err());
        journal.mark_running(&path, 0).unwrap();
        journal.mark_verified(&path, 0).unwrap();
        journal.finish(&path).unwrap();
        assert!(OperationJournal::load(&path).unwrap().finished);
    }

    #[test]
    fn final_verification_error_is_durable_and_keeps_journal_reviewable() {
        let dir = tempfile::tempdir().unwrap();
        let (manufacturer, model, build, commit) = profile_values();
        let path = OperationJournal::create(
            dir.path(), WorkflowKind::Cleanup, manufacturer, model, build, 25, commit,
            &["Disable package".into()],
        ).unwrap();
        let mut journal = OperationJournal::load(&path).unwrap();
        journal.mark_running(&path, 0).unwrap();
        journal.mark_verified(&path, 0).unwrap();
        journal.finish_with_error(&path, "final package state changed").unwrap();
        let loaded = OperationJournal::load(&path).unwrap();
        assert!(loaded.finished);
        assert_eq!(loaded.workflow_error.as_deref(), Some("final package state changed"));
        assert_eq!(loaded.operations[0].status, OperationStatus::Verified);
    }

    #[test]
    fn journal_does_not_store_network_addresses_or_raw_command_output() {
        let dir = tempfile::tempdir().unwrap();
        let (manufacturer, model, build, commit) = profile_values();
        let path = OperationJournal::create(
            dir.path(), WorkflowKind::Cleanup, manufacturer, model, build, 25, commit,
            &["Disable com.amazon.ads".into()],
        ).unwrap();
        let text = fs::read_to_string(path).unwrap();
        assert!(!text.contains("5555"));
        assert!(!text.contains("192.168."));
        assert!(!text.contains("adbkey"));
    }
}
