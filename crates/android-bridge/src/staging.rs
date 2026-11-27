//! Private local staging, proof reconciliation and cancellable verification.
use crate::{
    Bridge, Task,
    dto::{Fault, LocalRequest, Result},
};
use bmd_engine::{
    completion::{NetworkCompleteProof, durable_json, sync_parent},
    state::ResumeState,
};
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, File},
    path::{Path, PathBuf},
    sync::atomic::AtomicU64,
};
use url::Url;

pub const PROOF: &str = "network-complete.json";
const VERIFIED: &str = "verified.payload";
const MARKER: &str = "verified.json";

pub fn directory(bridge: &Bridge, id: &str, given: &str) -> Result<PathBuf> {
    let relative = Path::new("transfers").join(id);
    let supplied = Path::new(given);
    if supplied != bridge.root.join(&relative) && supplied != bridge.canonical_root.join(&relative)
    {
        return Err(Fault::new("PRIVATE_PATH_REFUSED"));
    }
    let mut path = bridge.canonical_root.clone();
    for component in ["transfers", id] {
        path.push(component);
        match fs::symlink_metadata(&path) {
            Ok(m) if m.is_dir() && !m.file_type().is_symlink() => {}
            Ok(_) => return Err(Fault::new("PRIVATE_PATH_REFUSED")),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                fs::create_dir(&path).map_err(disk_error)?;
            }
            Err(e) => return Err(disk_error(e)),
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).map_err(disk_error)?;
        }
    }
    if fs::canonicalize(&path).map_err(disk_error)? != path {
        return Err(Fault::new("PRIVATE_PATH_REFUSED"));
    }
    for name in [
        "payload",
        "payload.bmd.part",
        "payload.bmd.json",
        PROOF,
        "network-complete.json.new",
        VERIFIED,
        MARKER,
        "verified.json.new",
    ] {
        regular_or_missing(&path.join(name))?;
    }
    Ok(path)
}
fn regular_or_missing(path: &Path) -> Result<()> {
    match fs::symlink_metadata(path) {
        Ok(m) if m.is_file() && !m.file_type().is_symlink() => Ok(()),
        Ok(_) => Err(Fault::new("PRIVATE_PATH_REFUSED")),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(disk_error(e)),
    }
}
pub fn destination(dir: &Path) -> PathBuf {
    dir.join("payload")
}
pub fn part(dir: &Path) -> PathBuf {
    bmd_engine::naming::part_path(&destination(dir))
}
pub fn checkpoint(dir: &Path) -> PathBuf {
    bmd_engine::state::state_path(&destination(dir))
}

pub fn load_checkpoint(dir: &Path) -> Result<Option<ResumeState>> {
    let file = checkpoint(dir);
    if !file.exists() {
        return Ok(None);
    }
    regular_or_missing(&file)?;
    if fs::metadata(&file).map_err(disk_error)?.len() > 1024 * 1024 {
        return Err(Fault::new("CHECKPOINT_INVALID"));
    }
    let state = ResumeState::load(&file).map_err(|_| Fault::new("CHECKPOINT_INVALID"))?;
    if state.dest != destination(dir) {
        return Err(Fault::new("PRIVATE_PATH_REFUSED"));
    }
    Ok(Some(state))
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct VerifiedMarker {
    version: u32,
    task_id: String,
    network_generation: u64,
    source_url: Url,
    size: u64,
    checksum: Option<bmd_engine::verify::Hash>,
}

fn proof(dir: &Path, spec: &LocalRequest) -> Result<NetworkCompleteProof> {
    let p =
        NetworkCompleteProof::load(&dir.join(PROOF)).map_err(|_| Fault::new("PROOF_INVALID"))?;
    let source = crate::transfer::parse_url(&spec.expected_source_url)?;
    if p.task_id != spec.task_id
        || p.generation > spec.generation
        || p.generation == 0
        || p.source_url != source
        || p.staging_path != part(dir)
        || p.size > i64::MAX as u64
    {
        return Err(Fault::new("PROOF_IDENTITY_MISMATCH"));
    }
    Ok(p)
}
fn check_file(path: &Path, size: u64) -> Result<()> {
    regular_or_missing(path)?;
    let m = fs::metadata(path).map_err(disk_error)?;
    if !m.is_file() || m.len() != size {
        Err(Fault::new("STAGED_FILE_CHANGED"))
    } else {
        Ok(())
    }
}
fn valid_marker(dir: &Path, p: &NetworkCompleteProof) -> Result<bool> {
    let file = dir.join(MARKER);
    if !file.exists() {
        return Ok(false);
    }
    regular_or_missing(&file)?;
    if fs::metadata(&file).map_err(disk_error)?.len() > 64 * 1024 {
        return Err(Fault::new("VERIFICATION_MARKER_INVALID"));
    }
    let m: VerifiedMarker = serde_json::from_reader(File::open(file).map_err(disk_error)?)
        .map_err(|_| Fault::new("VERIFICATION_MARKER_INVALID"))?;
    if m.version != 1
        || m.task_id != p.task_id
        || m.network_generation != p.generation
        || m.source_url != p.source_url
        || m.size != p.size
        || m.checksum != p.checksum
    {
        return Err(Fault::new("VERIFICATION_MARKER_INVALID"));
    }
    check_file(&dir.join(VERIFIED), p.size)?;
    Ok(true)
}
pub fn local(bridge: &Bridge, spec: &LocalRequest, task: &Task, verify: bool) -> Result<()> {
    let dir = directory(bridge, &spec.task_id, &spec.private_directory)?;
    if task.cancel.is_cancelled() {
        return Err(Fault::new("CANCELLED"));
    }
    if !dir.join(PROOF).exists() {
        if verify {
            return Err(Fault::new("NETWORK_PROOF_REQUIRED"));
        }
        let source = crate::transfer::parse_url(&spec.expected_source_url)?;
        let saved = load_checkpoint(&dir)?;
        if let Some(saved) = &saved
            && saved.url != source
        {
            return Err(Fault::new("SOURCE_CHANGED_RESTART_REQUIRED"));
        }
        if dir.join(VERIFIED).exists() || dir.join(MARKER).exists() || destination(&dir).exists() {
            return Err(Fault::new("PROOF_INVALID"));
        }
        task.update(|s| {
            s.phase = if saved.is_some() || part(&dir).exists() {
                "interrupted"
            } else {
                "empty"
            };
            s.checkpoint_bytes = saved.as_ref().map_or(0, ResumeState::downloaded);
            s.received_bytes = s.checkpoint_bytes;
            s.total_bytes = saved.as_ref().map(|x| x.size);
            s.resumable = Some(saved.is_some());
            s.source_origin = Some(source.origin().ascii_serialization());
        });
        return Ok(());
    }
    let p = proof(&dir, spec)?;
    let verified = dir.join(VERIFIED);
    let marked = valid_marker(&dir, &p)?;
    if marked {
        if part(&dir).exists() {
            return Err(Fault::new("STAGING_LAYOUT_CONFLICT"));
        }
        task.update(|s| {
            s.phase = "verified";
            s.staged_path = Some(verified.to_string_lossy().into());
            s.total_bytes = Some(p.size);
            s.received_bytes = p.size;
            s.checkpoint_bytes = p.size;
            s.network_complete_proof_path = Some(dir.join(PROOF).to_string_lossy().into());
            s.source_origin = Some(p.source_url.origin().ascii_serialization());
        });
        return Ok(());
    }
    // Rename may have completed before a process died writing the verification marker.
    // Re-verify the existing private payload before creating that marker; never infer it.
    let input = match (part(&dir).exists(), verified.exists()) {
        (true, false) => part(&dir),
        (false, true) => verified.clone(),
        _ => return Err(Fault::new("STAGING_LAYOUT_CONFLICT")),
    };
    check_file(&input, p.size)?;
    task.update(|s| {
        s.phase = "network_complete";
        s.total_bytes = Some(p.size);
        s.received_bytes = p.size;
        s.checkpoint_bytes = p.size;
        s.network_complete_proof_path = Some(dir.join(PROOF).to_string_lossy().into());
        s.source_origin = Some(p.source_url.origin().ascii_serialization());
    });
    if !verify {
        return Ok(());
    }
    task.update(|s| s.phase = "verifying");
    if let Some(hash) = &p.checksum {
        let actual =
            bmd_engine::verify::digest_cancellable(&input, hash.algo, &AtomicU64::new(0), || {
                task.cancel.is_cancelled()
            })
            .map_err(|e| {
                if e.kind() == std::io::ErrorKind::Interrupted {
                    Fault::new("CANCELLED")
                } else {
                    disk_error(e)
                }
            })?;
        if actual != hash.value {
            return Err(Fault::new("CHECKSUM_MISMATCH"));
        }
    }
    if task.cancel.is_cancelled() {
        return Err(Fault::new("CANCELLED"));
    }
    if input != verified {
        fs::rename(&input, &verified).map_err(disk_error)?;
        sync_parent(&verified).map_err(disk_error)?;
    }
    let mut sync_options = fs::OpenOptions::new();
    sync_options.read(true);
    #[cfg(windows)]
    sync_options.write(true); // FlushFileBuffers requires a write-capable existing handle.
    sync_options
        .open(&verified)
        .map_err(disk_error)?
        .sync_all()
        .map_err(disk_error)?;
    let marker = VerifiedMarker {
        version: 1,
        task_id: p.task_id.clone(),
        network_generation: p.generation,
        source_url: p.source_url.clone(),
        size: p.size,
        checksum: p.checksum.clone(),
    };
    durable_json(&dir.join(MARKER), &marker).map_err(disk_error)?;
    task.update(|s| {
        s.phase = "verified";
        s.staged_path = Some(verified.to_string_lossy().into());
    });
    Ok(())
}

pub fn disk_error(error: std::io::Error) -> Fault {
    match error.raw_os_error() {
        Some(libc::ENOSPC) => Fault::retry("STORAGE_FULL"),
        _ => match error.kind() {
            std::io::ErrorKind::PermissionDenied => Fault::new("STORAGE_PERMISSION"),
            std::io::ErrorKind::NotFound => Fault::retry("STAGED_FILE_MISSING"),
            _ => Fault::retry("STORAGE_IO"),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use bmd_engine::{
        completion::{ChecksumProvenance, NetworkCompleteProof},
        state::Validators,
    };
    fn spec(bridge: &Bridge) -> LocalRequest {
        LocalRequest {
            task_id: "task".into(),
            generation: 2,
            private_directory: bridge.root.join("transfers/task").to_string_lossy().into(),
            expected_source_url: "https://example.org/payload".into(),
        }
    }
    fn staged(bridge: &Bridge) -> (LocalRequest, PathBuf) {
        let s = spec(bridge);
        let dir = directory(bridge, "task", &s.private_directory).unwrap();
        fs::write(part(&dir), b"verified bytes").unwrap();
        let p = NetworkCompleteProof {
            version: 1,
            checkpoint_version: 1,
            task_id: "task".into(),
            generation: 1,
            source_url: Url::parse(&s.expected_source_url).unwrap(),
            final_url: Url::parse(&s.expected_source_url).unwrap(),
            staging_path: part(&dir),
            size: 14,
            completed_start: 0,
            completed_end: 14,
            validators: Validators::default(),
            checksum: Some(bmd_engine::verify::Hash {
                algo: bmd_engine::verify::Algo::Sha256,
                value: bmd_engine::verify::digest(&part(&dir), bmd_engine::verify::Algo::Sha256)
                    .unwrap(),
            }),
            checksum_provenance: ChecksumProvenance::User,
        };
        p.persist(&dir.join(PROOF)).unwrap();
        (s, dir)
    }
    #[test]
    fn local_generation_can_verify_previous_network_proof_and_reconcile() {
        let tmp = tempfile::tempdir().unwrap();
        let b = Bridge::new(tmp.path().into()).unwrap();
        let (s, dir) = staged(&b);
        let t = b.admit("task", 2, None, None).unwrap();
        local(&b, &s, &t, true).unwrap();
        assert_eq!(t.snapshot().phase, "verified");
        assert!(dir.join(VERIFIED).exists());
        local(&b, &s, &t, false).unwrap();
        assert_eq!(t.snapshot().checkpoint_bytes, 14);
    }
    #[test]
    fn crash_after_rename_requires_hash_before_new_marker() {
        let tmp = tempfile::tempdir().unwrap();
        let b = Bridge::new(tmp.path().into()).unwrap();
        let (s, dir) = staged(&b);
        fs::rename(part(&dir), dir.join(VERIFIED)).unwrap();
        fs::write(dir.join(VERIFIED), b"corruptedbytes").unwrap();
        let t = b.admit("task", 2, None, None).unwrap();
        assert_eq!(
            local(&b, &s, &t, true).unwrap_err().code,
            "CHECKSUM_MISMATCH"
        );
        assert!(!dir.join(MARKER).exists());
    }
    #[test]
    fn source_identity_and_symlink_prefix_are_refused() {
        let tmp = tempfile::tempdir().unwrap();
        let b = Bridge::new(tmp.path().into()).unwrap();
        let (mut s, _) = staged(&b);
        s.expected_source_url = "https://example.org/different".into();
        let t = b.admit("task", 2, None, None).unwrap();
        assert_eq!(
            local(&b, &s, &t, true).unwrap_err().code,
            "PROOF_IDENTITY_MISMATCH"
        );
        assert!(
            directory(
                &b,
                "other",
                &format!("{}-outside/transfers/other", b.root.display())
            )
            .is_err()
        );
    }
    #[cfg(unix)]
    #[test]
    fn symlinked_task_directory_is_rejected() {
        let tmp = tempfile::tempdir().unwrap();
        let b = Bridge::new(tmp.path().into()).unwrap();
        fs::create_dir(b.root.join("transfers")).unwrap();
        std::os::unix::fs::symlink(tmp.path(), b.root.join("transfers/task")).unwrap();
        assert_eq!(
            directory(&b, "task", spec(&b).private_directory.as_str())
                .unwrap_err()
                .code,
            "PRIVATE_PATH_REFUSED"
        );
    }
}
