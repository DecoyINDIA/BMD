//! Durable boundary between network transfer and local verification/publication.
//!
//! The optional mobile completion mode leaves a private part file intact. Its proof is
//! persisted only after the downloader body dropped and the platform transport barrier joined.
//! Desktop callers do not enable this mode and retain the existing verify/rename behavior.

use crate::{state::Validators, verify::Hash};
use serde::{Deserialize, Serialize};
use std::{
    fs::{File, OpenOptions},
    future::Future,
    io::{self, Write},
    path::{Path, PathBuf},
    pin::Pin,
    sync::Arc,
};
use url::Url;

pub type ReleaseFuture = Pin<Box<dyn Future<Output = io::Result<()>> + Send>>;
#[derive(Clone)]
pub struct ReleaseNetwork(pub Arc<dyn Fn() -> ReleaseFuture + Send + Sync>);
impl std::fmt::Debug for ReleaseNetwork {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("ReleaseNetwork")
    }
}

#[derive(Clone, Debug)]
pub struct NetworkCompletion {
    pub task_id: String,
    pub generation: u64,
    pub proof_path: PathBuf,
    pub release_network: ReleaseNetwork,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NetworkCompleteProof {
    pub version: u32,
    pub checkpoint_version: u32,
    pub task_id: String,
    pub generation: u64,
    pub source_url: Url,
    pub final_url: Url,
    pub staging_path: PathBuf,
    pub size: u64,
    /// A complete interval [0, size). Unknown-length streaming gets this only after EOF.
    pub completed_start: u64,
    pub completed_end: u64,
    pub validators: Validators,
    pub checksum: Option<Hash>,
    pub checksum_provenance: ChecksumProvenance,
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ChecksumProvenance {
    User,
    ServerOrCheckpoint,
    None,
}

impl NetworkCompleteProof {
    pub fn persist(&self, path: &Path) -> io::Result<()> {
        if self.version != 1 || self.completed_start != 0 || self.completed_end != self.size {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "invalid completion extent",
            ));
        }
        let mut options = OpenOptions::new();
        options.read(true);
        // FlushFileBuffers needs GENERIC_WRITE. Never create, truncate or change bytes.
        #[cfg(windows)]
        options.write(true);
        let file = options.open(&self.staging_path)?;
        if !file.metadata()?.is_file() || file.metadata()?.len() != self.size {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "staging size changed",
            ));
        }
        file.sync_all()?;
        drop(file);
        durable_json(path, self)
    }

    pub fn load(path: &Path) -> io::Result<Self> {
        let f = File::open(path)?;
        if f.metadata()?.len() > 64 * 1024 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "proof too large",
            ));
        }
        let proof: Self = serde_json::from_reader(f).map_err(io::Error::other)?;
        if proof.version != 1
            || proof.checkpoint_version != 1
            || proof.completed_start != 0
            || proof.completed_end != proof.size
        {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "invalid completion proof",
            ));
        }
        Ok(proof)
    }
}

/// Atomic replace plus file and directory durability. Call only on private local storage.
pub fn durable_json(path: &Path, value: &impl Serialize) -> io::Result<()> {
    let data = serde_json::to_vec(value).map_err(io::Error::other)?;
    let tmp = path.with_extension("json.new");
    let result = (|| {
        let mut file = OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .open(&tmp)?;
        file.write_all(&data)?;
        file.sync_all()?;
        drop(file);
        std::fs::rename(&tmp, path)?;
        sync_parent(path)
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(tmp);
    }
    result
}

pub fn sync_parent(path: &Path) -> io::Result<()> {
    #[cfg(unix)]
    {
        File::open(
            path.parent()
                .ok_or_else(|| io::Error::other("missing parent"))?,
        )?
        .sync_all()?;
    }
    #[cfg(not(unix))]
    let _ = path;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn proof_requires_exact_durable_extent_and_file_size() {
        let dir = tempfile::tempdir().unwrap();
        let staged = dir.path().join("payload.part");
        std::fs::write(&staged, b"complete").unwrap();
        let mut p = NetworkCompleteProof {
            version: 1,
            checkpoint_version: 1,
            task_id: "task".into(),
            generation: 1,
            source_url: Url::parse("https://example.org/a").unwrap(),
            final_url: Url::parse("https://example.org/a").unwrap(),
            staging_path: staged,
            size: 8,
            completed_start: 0,
            completed_end: 8,
            validators: Validators::default(),
            checksum: None,
            checksum_provenance: ChecksumProvenance::None,
        };
        let path = dir.path().join("network-complete.json");
        p.persist(&path).unwrap();
        assert_eq!(NetworkCompleteProof::load(&path).unwrap(), p);
        p.completed_end = 7;
        assert!(p.persist(&path).is_err());
        p.completed_end = 8;
        std::fs::write(&p.staging_path, b"short").unwrap();
        assert!(p.persist(&path).is_err());
    }
}
