//! Process-owned JNI bridge for native Android. No user URL/header/error is ever logged.
//! See `docs/android/NATIVE_CONTRACT.md` for Kotlin lifecycle and JSON contracts.
// Only Android exports JNI entry points; host builds exercise the same state machine in tests.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]
mod dto;
#[cfg(target_os = "android")]
#[allow(unsafe_code)]
mod native;
mod network;
mod staging;
mod transfer;

use dto::{Command, Fault, Result, Snapshot};
use futures::FutureExt;
use network::{Gateway, Network};
use serde_json::{Value, json};
use std::{
    collections::{BTreeMap, HashMap, HashSet},
    path::PathBuf,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicU64, Ordering},
    },
};
use tokio_util::sync::CancellationToken;

const INPUT_LIMIT: usize = 32 * 1024;
const OUTPUT_LIMIT: usize = 64 * 1024;
const MAX_HANDLES: usize = 64;
const MAX_ACTIVE: usize = 4;
static BRIDGE: OnceLock<Arc<Bridge>> = OnceLock::new();
static INIT_LOCK: Mutex<()> = Mutex::new(());

struct Registry {
    next: u64,
    handles: BTreeMap<u64, Arc<Task>>,
    claims: HashMap<String, u64>,
    invalid_networks: HashSet<(u64, u64)>,
    network_history_full: bool,
}
pub struct Bridge {
    root: PathBuf,
    canonical_root: PathBuf,
    runtime: tokio::runtime::Runtime,
    registry: Mutex<Registry>,
}
struct Task {
    snapshot: Mutex<Snapshot>,
    cancel: CancellationToken,
    speed: AtomicU64,
    network: Option<Network>,
    gateway: Mutex<Option<Arc<Gateway>>>,
    monitor: Mutex<Option<bmd_engine::download::DownloadMonitor>>,
}
impl Task {
    fn update(&self, f: impl FnOnce(&mut Snapshot)) {
        let mut s = self.snapshot.lock().unwrap_or_else(|e| e.into_inner());
        f(&mut s);
    }
    fn snapshot(&self) -> Snapshot {
        self.snapshot
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
    }
    fn stop(&self) {
        self.cancel.cancel();
        if let Some(m) = self
            .monitor
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
        {
            m.cancel();
        }
        if let Some(g) = self
            .gateway
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
        {
            g.invalidate();
        }
        self.update(|s| {
            if !s.terminal
                && !matches!(
                    s.phase,
                    "network_complete" | "verified" | "probed" | "empty" | "interrupted"
                )
            {
                s.phase = "stopping";
            }
        });
    }
    fn finish(&self, result: Result<()>, released: bool) {
        self.update(|s| {
            if let Err(e) = result {
                s.phase = if e.code == "CANCELLED" {
                    "cancelled"
                } else {
                    "failed"
                };
                s.error_code = Some(e.code);
                s.retryable = e.retryable;
            }
            s.terminal = true;
            s.writer_released = released;
        });
    }
}
impl Bridge {
    fn new(root: PathBuf) -> Result<Arc<Self>> {
        if !root.is_absolute() {
            return Err(Fault::new("PRIVATE_STORAGE_INVALID"));
        }
        std::fs::create_dir_all(&root).map_err(|_| Fault::new("PRIVATE_STORAGE_UNAVAILABLE"))?;
        let canonical_root =
            std::fs::canonicalize(&root).map_err(|_| Fault::new("PRIVATE_STORAGE_UNAVAILABLE"))?;
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .max_blocking_threads(4)
            .thread_name("bmd-native")
            .enable_all()
            .build()
            .map_err(|_| Fault::new("RUNTIME_UNAVAILABLE"))?;
        Ok(Arc::new(Self {
            root,
            canonical_root,
            runtime,
            registry: Mutex::new(Registry {
                next: 1,
                handles: BTreeMap::new(),
                claims: HashMap::new(),
                invalid_networks: HashSet::new(),
                network_history_full: false,
            }),
        }))
    }
    fn handle(&self, handle: u64) -> Result<Arc<Task>> {
        self.registry
            .lock()
            .map_err(|_| Fault::new("NATIVE_STATE"))?
            .handles
            .get(&handle)
            .cloned()
            .ok_or(Fault::new("HANDLE_NOT_FOUND"))
    }
    fn admit(
        &self,
        id: &str,
        generation: u64,
        network: Option<Network>,
        speed: Option<u64>,
    ) -> Result<Arc<Task>> {
        validate_identity(id, generation)?;
        let mut r = self
            .registry
            .lock()
            .map_err(|_| Fault::new("NATIVE_STATE"))?;
        if r.handles.len() >= MAX_HANDLES {
            return Err(Fault::new("HANDLE_LIMIT"));
        }
        if r.claims.contains_key(id) {
            return Err(Fault::new("WRITER_ALREADY_OWNED"));
        }
        if r.handles
            .values()
            .filter(|t| !t.snapshot().writer_released)
            .count()
            >= MAX_ACTIVE
        {
            return Err(Fault::retry("EXECUTOR_LIMIT"));
        }
        if let Some(n) = network
            && (r.network_history_full
                || n.handle == 0
                || n.generation == 0
                || r.invalid_networks.contains(&(n.handle, n.generation)))
        {
            return Err(Fault::retry("NETWORK_NOT_ADMITTED"));
        }
        let handle = r.next;
        r.next = r
            .next
            .checked_add(1)
            .filter(|x| *x <= i64::MAX as u64)
            .ok_or(Fault::new("HANDLE_LIMIT"))?;
        let task = Arc::new(Task {
            snapshot: Mutex::new(Snapshot::new(handle, id.into(), generation)),
            cancel: CancellationToken::new(),
            speed: AtomicU64::new(speed.unwrap_or(0)),
            network,
            gateway: Mutex::new(None),
            monitor: Mutex::new(None),
        });
        r.claims.insert(id.into(), handle);
        r.handles.insert(handle, task.clone());
        Ok(task)
    }
    fn command(self: &Arc<Self>, request: Command) -> Result<Value> {
        match request {
            Command::Probe(spec) => self.network(spec, true),
            Command::Start(spec) => self.network(spec, false),
            Command::Verify(spec) => self.local(spec, true),
            Command::Reconcile(spec) => self.local(spec, false),
            Command::Stop { handle } => {
                self.handle(handle)?.stop();
                Ok(json!({"accepted":true}))
            }
            Command::SetLimit {
                handle,
                speed_limit_bytes_per_second,
            } => {
                validate_limit(speed_limit_bytes_per_second)?;
                self.handle(handle)?
                    .speed
                    .store(speed_limit_bytes_per_second.unwrap_or(0), Ordering::Relaxed);
                Ok(json!({"accepted":true}))
            }
            Command::Release { handle } => {
                let mut r = self
                    .registry
                    .lock()
                    .map_err(|_| Fault::new("NATIVE_STATE"))?;
                let task = r
                    .handles
                    .get(&handle)
                    .ok_or(Fault::new("HANDLE_NOT_FOUND"))?;
                let s = task.snapshot();
                if !s.terminal || !s.writer_released {
                    return Err(Fault::retry("WRITER_NOT_RELEASED"));
                }
                r.handles.remove(&handle);
                r.claims.remove(&s.task_id);
                Ok(json!({"released":true}))
            }
            Command::InvalidateNetwork { network } => {
                let mut r = self
                    .registry
                    .lock()
                    .map_err(|_| Fault::new("NATIVE_STATE"))?;
                if r.invalid_networks.len() >= 4096 {
                    r.network_history_full = true;
                } else {
                    r.invalid_networks
                        .insert((network.handle, network.generation));
                }
                for task in r.handles.values() {
                    if task.network == Some(network) {
                        task.stop();
                    }
                }
                Ok(json!({"accepted":true}))
            }
        }
    }
    fn network(self: &Arc<Self>, spec: dto::NetworkRequest, probe: bool) -> Result<Value> {
        transfer::validate_request(&spec, probe)?;
        let task = self.admit(
            &spec.task_id,
            spec.generation,
            Some(spec.network),
            spec.speed_limit_bytes_per_second,
        )?;
        let handle = task.snapshot().handle;
        let bridge = self.clone();
        self.runtime.spawn(async move {
            let result =
                std::panic::AssertUnwindSafe(transfer::run(bridge, spec, task.clone(), probe))
                    .catch_unwind()
                    .await;
            match result {
                Ok((result, released)) => task.finish(result, released),
                Err(_) => {
                    task.stop();
                    let gateway = {
                        task.gateway
                            .lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .clone()
                    };
                    if let Some(g) = gateway {
                        let _ = g.shutdown().await;
                    }
                    task.finish(Err(Fault::new("NATIVE_PANIC")), false);
                }
            }
        });
        Ok(json!({"handle":handle}))
    }
    fn local(self: &Arc<Self>, spec: dto::LocalRequest, verify: bool) -> Result<Value> {
        let task = self.admit(&spec.task_id, spec.generation, None, None)?;
        let handle = task.snapshot().handle;
        let bridge = self.clone();
        self.runtime.spawn(async move {
            let worker_task = task.clone();
            let result = tokio::task::spawn_blocking(move || {
                staging::local(&bridge, &spec, &worker_task, verify)
            })
            .await;
            match result {
                Ok(value) => task.finish(value, true),
                Err(_) => task.finish(Err(Fault::new("NATIVE_PANIC")), false),
            }
        });
        Ok(json!({"handle":handle}))
    }
}
fn validate_identity(id: &str, generation: u64) -> Result<()> {
    if id.is_empty()
        || id.len() > 128
        || !id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
        || generation == 0
        || generation > i64::MAX as u64
    {
        Err(Fault::new("IDENTITY_INVALID"))
    } else {
        Ok(())
    }
}
fn validate_limit(limit: Option<u64>) -> Result<()> {
    if limit.is_some_and(|n| n == 0 || n > 10 * 1024 * 1024 * 1024) {
        Err(Fault::new("LIMIT_INVALID"))
    } else {
        Ok(())
    }
}

fn initialize(root: PathBuf) -> Result<Value> {
    let _guard = INIT_LOCK.lock().map_err(|_| Fault::new("NATIVE_STATE"))?;
    if BRIDGE.get().is_none() {
        // A caught panic must never send a possibly sensitive panic payload to logcat.
        std::panic::set_hook(Box::new(|_| {}));
        let bridge = Bridge::new(root)?;
        BRIDGE.set(bridge).map_err(|_| Fault::new("NATIVE_STATE"))?;
    }
    Ok(json!({"initialized":true,"maxActiveTasks":MAX_ACTIVE,"apiVersion":1}))
}
fn dispatch(text: &str) -> Result<Value> {
    if text.len() > INPUT_LIMIT {
        return Err(Fault::new("REQUEST_TOO_LARGE"));
    }
    let command: Command = serde_json::from_str(text).map_err(|_| Fault::new("COMMAND_INVALID"))?;
    let bridge = BRIDGE.get().ok_or(Fault::new("NOT_INITIALIZED"))?;
    bridge.command(command)
}
fn snapshot(handle: u64) -> Result<Value> {
    let bridge = BRIDGE.get().ok_or(Fault::new("NOT_INITIALIZED"))?;
    serde_json::to_value(bridge.handle(handle)?.snapshot()).map_err(|_| Fault::new("NATIVE_STATE"))
}
fn envelope(result: Result<Value>) -> String {
    let value = match result {
        Ok(data) => json!({"apiVersion":1,"ok":true,"data":data}),
        Err(error) => {
            json!({"apiVersion":1,"ok":false,"error":{"code":error.code,"retryable":error.retryable}})
        }
    };
    let text=serde_json::to_string(&value).unwrap_or_else(|_|"{\"apiVersion\":1,\"ok\":false,\"error\":{\"code\":\"NATIVE_STATE\",\"retryable\":false}}".into());
    if text.len() > OUTPUT_LIMIT {
        envelope(Err(Fault::new("RESPONSE_TOO_LARGE")))
    } else {
        text
    }
}
fn contained(f: impl FnOnce() -> Result<Value>) -> String {
    envelope(
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(f))
            .unwrap_or(Err(Fault::new("NATIVE_PANIC"))),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn commands_are_bounded_and_unknown_fields_refused() {
        assert!(
            serde_json::from_str::<Command>(r#"{"command":"stop","handle":1,"url":"secret"}"#)
                .is_err()
        );
        assert_eq!(
            dispatch(&"x".repeat(INPUT_LIMIT + 1)).unwrap_err().code,
            "REQUEST_TOO_LARGE"
        );
    }
    #[test]
    fn identities_do_not_allow_paths_or_zero_generation() {
        for id in ["../task", "task/name", "", "a.b"] {
            assert!(validate_identity(id, 1).is_err());
        }
        assert!(validate_identity("safe-task_1", 1).is_ok());
        assert!(validate_identity("safe", 0).is_err());
    }
    #[test]
    fn no_release_before_ack_and_no_two_generations() {
        let dir = tempfile::tempdir().unwrap();
        let bridge = Bridge::new(dir.path().into()).unwrap();
        let task = bridge.admit("task", 1, None, None).unwrap();
        let h = task.snapshot().handle;
        assert!(bridge.admit("task", 2, None, None).is_err());
        assert!(bridge.command(Command::Release { handle: h }).is_err());
        task.finish(Ok(()), true);
        bridge.command(Command::Release { handle: h }).unwrap();
        assert!(bridge.admit("task", 2, None, None).is_ok());
    }
    #[test]
    fn network_history_saturation_still_cancels_and_blocks_new_admission() {
        let dir = tempfile::tempdir().unwrap();
        let bridge = Bridge::new(dir.path().into()).unwrap();
        let network = Network {
            handle: 9000,
            generation: 1,
        };
        let task = bridge.admit("active", 1, Some(network), None).unwrap();
        {
            let mut r = bridge.registry.lock().unwrap();
            for n in 1..=4096 {
                r.invalid_networks.insert((n, 1));
            }
        }
        bridge
            .command(Command::InvalidateNetwork { network })
            .unwrap();
        assert!(task.cancel.is_cancelled());
        assert!(
            bridge
                .admit(
                    "other",
                    1,
                    Some(Network {
                        handle: 9001,
                        generation: 1
                    }),
                    None
                )
                .is_err()
        );
    }
    #[test]
    fn late_stop_cannot_replace_a_completed_phase_with_terminal_stopping() {
        let dir = tempfile::tempdir().unwrap();
        let bridge = Bridge::new(dir.path().into()).unwrap();
        let task = bridge.admit("task", 1, None, None).unwrap();
        task.update(|s| s.phase = "network_complete");
        task.stop();
        task.finish(Ok(()), true);
        assert_eq!(task.snapshot().phase, "network_complete");
        assert!(task.snapshot().writer_released);
    }
}
