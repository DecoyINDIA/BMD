//! Mobile contracts exercised against real HTTP/TLS lab sockets and durable files.
use bmd_engine::{
    Destination, Download, Options, Request,
    completion::{NetworkCompleteProof, NetworkCompletion, ReleaseNetwork},
    state::{ResumeState, Validators},
    verify::Hash,
};
use bmd_labserver::{Config, Faults, LabServer};
use reqwest::header::{AUTHORIZATION, HeaderMap};
use std::{
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};
use tokio::sync::Notify;

async fn server(tls: bool, faults: &str) -> LabServer {
    LabServer::start(Config {
        tls,
        faults: Faults::parse(faults).unwrap(),
        ..Default::default()
    })
    .await
    .unwrap()
}
fn options() -> Options {
    Options {
        strict_http: true,
        max_connections: 2,
        max_retries: 0,
        retry_delay: Duration::from_millis(10),
        ..Default::default()
    }
}
fn redirect(target: &str) -> String {
    format!(
        "/redirect?to={}",
        url::form_urlencoded::byte_serialize(target.as_bytes()).collect::<String>()
    )
}

#[tokio::test]
async fn network_proof_waits_for_transport_barrier_and_precedes_optional_hash() {
    let server = server(false, "").await;
    let dir = tempfile::tempdir().unwrap();
    let dest = dir.path().join("payload");
    let proof = dir.path().join("network-complete.json");
    let entered = Arc::new(Notify::new());
    let release = Arc::new(Notify::new());
    let (a, b) = (entered.clone(), release.clone());
    let opts = Options {
        checksum: Hash::parse(&"00".repeat(32)),
        network_completion: Some(NetworkCompletion {
            task_id: "task".into(),
            generation: 7,
            proof_path: proof.clone(),
            release_network: ReleaseNetwork(Arc::new(move || {
                let (a, b) = (a.clone(), b.clone());
                Box::pin(async move {
                    a.notify_one();
                    b.notified().await;
                    Ok(())
                })
            })),
        }),
        ..options()
    };
    let download = Download::start(
        Request {
            url: server.url("/gen/256K/sample.bin?seed=7").parse().unwrap(),
            headers: HeaderMap::new(),
            dest: Destination::File(dest.clone()),
        },
        opts,
    );
    let waiter = tokio::spawn(download.wait());
    tokio::time::timeout(Duration::from_secs(15), entered.notified())
        .await
        .unwrap();
    assert!(!proof.exists(), "transport lease still held");
    assert!(
        !dest.exists(),
        "publication cannot precede local verification"
    );
    assert_eq!(
        std::fs::metadata(bmd_engine::naming::part_path(&dest))
            .unwrap()
            .len(),
        256 * 1024
    );
    release.notify_one();
    let outcome = waiter.await.unwrap().unwrap();
    let evidence = NetworkCompleteProof::load(&proof).unwrap();
    assert_eq!(evidence.generation, 7);
    assert_eq!(evidence.size, outcome.size);
    assert_eq!(evidence.staging_path, outcome.path);
    assert!(evidence.checksum.is_some());
    assert_eq!(evidence.completed_end, 256 * 1024);
    assert!(!dest.exists());
    assert!(
        !bmd_engine::verify::matches(&outcome.path, evidence.checksum.as_ref().unwrap()).unwrap(),
        "wrong expected checksum was deliberately deferred"
    );
}

#[tokio::test]
async fn strict_redirect_drops_credentials_at_changed_port_and_never_adds_referer() {
    let first = server(true, "").await;
    let second = server(true, "").await;
    let dir = tempfile::tempdir().unwrap();
    let mut headers = HeaderMap::new();
    headers.insert(AUTHORIZATION, "Bearer secret-token".parse().unwrap());
    let target = second.url("/gen/64K/file.bin?seed=8");
    let link = format!("{}&secret=source", first.url(&redirect(&target)));
    let opts = Options {
        extra_roots: vec![first.ca_der().unwrap(), second.ca_der().unwrap()],
        ..options()
    };
    Download::start(
        Request {
            url: link.parse().unwrap(),
            headers,
            dest: Destination::File(dir.path().join("file.bin")),
        },
        opts,
    )
    .wait()
    .await
    .unwrap();
    tokio::time::timeout(Duration::from_secs(5), first.settled())
        .await
        .unwrap();
    tokio::time::timeout(Duration::from_secs(5), second.settled())
        .await
        .unwrap();
    // LabServer does not record /redirect. Make an independent same-origin request
    // to prove the header is actually supplied, then verify cross-port stripping below.
    let mut own_headers = HeaderMap::new();
    own_headers.insert(AUTHORIZATION, "Bearer secret-token".parse().unwrap());
    let own_opts = Options {
        extra_roots: vec![first.ca_der().unwrap()],
        ..options()
    };
    bmd_engine::probe(
        first.url("/gen/1K/auth.bin").parse().unwrap(),
        own_headers,
        &own_opts,
    )
    .await
    .unwrap();
    tokio::time::timeout(Duration::from_secs(5), first.settled())
        .await
        .unwrap();
    assert!(first.log().iter().any(|e| e.authorization.is_some()));
    assert!(!second.log().is_empty());
    assert!(
        second
            .log()
            .iter()
            .all(|e| e.authorization.is_none() && e.referer.is_none())
    );
}

#[tokio::test]
async fn strict_https_downgrade_never_reaches_plaintext_target() {
    let secure = server(true, "").await;
    let plain = server(false, "").await;
    let target = plain.url("/gen/1K/file.bin");
    let opts = Options {
        extra_roots: vec![secure.ca_der().unwrap()],
        ..options()
    };
    let result = bmd_engine::probe(
        secure.url(&redirect(&target)).parse().unwrap(),
        HeaderMap::new(),
        &opts,
    )
    .await;
    assert!(result.is_err());
    assert!(plain.log().is_empty());
}

#[tokio::test]
async fn strict_http_credentials_are_refused_before_request() {
    let plain = server(false, "").await;
    let mut headers = HeaderMap::new();
    headers.insert(AUTHORIZATION, "Bearer secret".parse().unwrap());
    assert!(
        bmd_engine::probe(
            plain.url("/gen/1K/file.bin").parse().unwrap(),
            headers,
            &options()
        )
        .await
        .is_err()
    );
    assert!(plain.log().is_empty());
}

#[tokio::test]
async fn cancelled_network_has_no_completion_proof_and_joins_release() {
    let server = server(false, "rate=64K").await;
    let dir = tempfile::tempdir().unwrap();
    let dest = dir.path().join("payload");
    let proof = dir.path().join("proof.json");
    let joined = Arc::new(AtomicBool::new(false));
    let mark = joined.clone();
    let opts = Options {
        save_interval: Duration::from_millis(20),
        network_completion: Some(NetworkCompletion {
            task_id: "task".into(),
            generation: 1,
            proof_path: proof.clone(),
            release_network: ReleaseNetwork(Arc::new(move || {
                let mark = mark.clone();
                Box::pin(async move {
                    mark.store(true, Ordering::SeqCst);
                    Ok(())
                })
            })),
        }),
        ..options()
    };
    let download = Download::start(
        Request {
            url: server.url("/gen/2M/file.bin").parse().unwrap(),
            headers: HeaderMap::new(),
            dest: Destination::File(dest.clone()),
        },
        opts,
    );
    tokio::time::timeout(Duration::from_secs(10), async {
        while download.snapshot().downloaded < 32 * 1024 {
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .unwrap();
    download.cancel();
    assert!(matches!(
        download.wait().await,
        Err(bmd_engine::Error::Cancelled)
    ));
    assert!(joined.load(Ordering::SeqCst));
    assert!(!proof.exists());
    let checkpoint = ResumeState::load(&bmd_engine::state::state_path(&dest)).unwrap();
    assert!(checkpoint.downloaded() > 0);
}

#[tokio::test]
async fn changed_source_resume_is_restart_only_before_network() {
    let dir = tempfile::tempdir().unwrap();
    let dest = dir.path().join("payload");
    let source: url::Url = "https://example.invalid/original".parse().unwrap();
    let saved = ResumeState::new(
        source.clone(),
        source,
        dest.clone(),
        8,
        Validators::default(),
    );
    let state = bmd_engine::state::state_path(&dest);
    saved.write(&state).unwrap();
    std::fs::write(bmd_engine::naming::part_path(&dest), b"partial!").unwrap();
    let d = Download::resume_or_start(
        state,
        Request {
            url: "https://example.invalid/replacement".parse().unwrap(),
            headers: HeaderMap::new(),
            dest: Destination::File(dest),
        },
        options(),
        None,
        0,
    );
    assert!(matches!(
        d.wait().await,
        Err(bmd_engine::Error::BadState(_))
    ));
}

#[tokio::test]
async fn strict_partial_response_must_keep_the_expected_etag_even_when_server_ignores_if_range() {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let saw_range = Arc::new(AtomicBool::new(false));
    let observed = saw_range.clone();
    let server = tokio::spawn(async move {
        let mut workers = tokio::task::JoinSet::new();
        loop {
            let (mut stream, _) = listener.accept().await.unwrap();
            let observed = observed.clone();
            workers.spawn(async move{
                let mut request=Vec::new();let mut byte=[0u8;1];
                while request.len()<8192&&!request.ends_with(b"\r\n\r\n") {if stream.read_exact(&mut byte).await.is_err(){return}request.push(byte[0]);}
                let text=String::from_utf8_lossy(&request);let range=text.lines().find_map(|line|line.to_ascii_lowercase().strip_prefix("range: bytes=").map(str::to_owned));
                let total=2*1024*1024;
                if let Some(range)=range{
                    observed.store(true,Ordering::SeqCst);let(a,b)=range.trim().split_once('-').unwrap();let(start,end)=(a.parse::<usize>().unwrap(),b.parse::<usize>().unwrap());
                    let head=format!("HTTP/1.1 206 Partial Content\r\nContent-Type: application/octet-stream\r\nETag: \"different\"\r\nContent-Range: bytes {start}-{end}/{total}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",end-start+1);
                    let _=stream.write_all(head.as_bytes()).await;let _=stream.write_all(&vec![7;end-start+1]).await;
                }else{
                    let head=format!("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\nETag: \"original\"\r\nContent-Length: {total}\r\nConnection: close\r\n\r\n");if stream.write_all(head.as_bytes()).await.is_err(){return}
                    for _ in 0..(total/16384){if stream.write_all(&[7;16384]).await.is_err(){break}tokio::time::sleep(Duration::from_millis(10)).await;}
                }
            });
        }
    });
    let dir = tempfile::tempdir().unwrap();
    let result = tokio::time::timeout(
        Duration::from_secs(10),
        Download::start(
            Request {
                url: format!("http://{address}/payload.bin").parse().unwrap(),
                headers: HeaderMap::new(),
                dest: Destination::File(dir.path().join("payload.bin")),
            },
            options(),
        )
        .wait(),
    )
    .await
    .unwrap();
    server.abort();
    let _ = server.await;
    assert!(saw_range.load(Ordering::SeqCst));
    assert!(matches!(result, Err(bmd_engine::Error::FileChanged)));
}
