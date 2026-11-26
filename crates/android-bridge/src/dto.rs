use crate::network::Network;
use serde::{Deserialize, Serialize};

#[derive(Deserialize)]
#[serde(
    tag = "command",
    rename_all = "snake_case",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
pub enum Command {
    Probe(NetworkRequest),
    Start(NetworkRequest),
    Verify(LocalRequest),
    Reconcile(LocalRequest),
    Stop {
        handle: u64,
    },
    Release {
        handle: u64,
    },
    SetLimit {
        handle: u64,
        speed_limit_bytes_per_second: Option<u64>,
    },
    InvalidateNetwork {
        network: Network,
    },
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NetworkRequest {
    pub task_id: String,
    pub generation: u64,
    pub url: String,
    pub expected_origin: String,
    pub credential_origin: Option<String>,
    pub private_directory: Option<String>,
    pub file_name: Option<String>,
    #[serde(default)]
    pub resume: bool,
    #[serde(default)]
    pub http_consent: bool,
    pub expected_sha256: Option<String>,
    pub authorization: Option<String>,
    pub network: Network,
    #[serde(default = "default_connections")]
    pub max_connections: u32,
    pub speed_limit_bytes_per_second: Option<u64>,
}
fn default_connections() -> u32 {
    4
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalRequest {
    pub task_id: String,
    pub generation: u64,
    pub private_directory: String,
    pub expected_source_url: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Snapshot {
    pub handle: u64,
    pub task_id: String,
    pub generation: u64,
    pub phase: &'static str,
    pub received_bytes: u64,
    pub checkpoint_bytes: u64,
    pub total_bytes: Option<u64>,
    pub resumable: Option<bool>,
    pub file_name: Option<String>,
    pub source_origin: Option<String>,
    pub web_page: bool,
    pub network_complete_proof_path: Option<String>,
    pub staged_path: Option<String>,
    pub error_code: Option<&'static str>,
    pub retryable: bool,
    pub terminal: bool,
    pub writer_released: bool,
}
impl Snapshot {
    pub fn new(handle: u64, task_id: String, generation: u64) -> Self {
        Self {
            handle,
            task_id,
            generation,
            phase: "queued",
            received_bytes: 0,
            checkpoint_bytes: 0,
            total_bytes: None,
            resumable: None,
            file_name: None,
            source_origin: None,
            web_page: false,
            network_complete_proof_path: None,
            staged_path: None,
            error_code: None,
            retryable: false,
            terminal: false,
            writer_released: false,
        }
    }
}
#[derive(Clone, Copy, Debug)]
pub struct Fault {
    pub code: &'static str,
    pub retryable: bool,
}
impl Fault {
    pub const fn new(code: &'static str) -> Self {
        Self {
            code,
            retryable: false,
        }
    }
    pub const fn retry(code: &'static str) -> Self {
        Self {
            code,
            retryable: true,
        }
    }
}
pub type Result<T> = std::result::Result<T, Fault>;
