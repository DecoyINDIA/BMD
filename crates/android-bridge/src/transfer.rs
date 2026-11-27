use crate::{
    Bridge, Task,
    dto::{Fault, NetworkRequest, Result},
    network::Gateway,
    staging,
};
use bmd_engine::{
    Destination, Download, Error, Options, Request,
    completion::{NetworkCompleteProof, NetworkCompletion, ReleaseNetwork},
    download::State,
};
use futures::FutureExt;
use reqwest::header::{AUTHORIZATION, HeaderMap, HeaderValue};
use std::{
    path::PathBuf,
    sync::{Arc, atomic::Ordering},
    time::Duration,
};
use url::Url;

pub fn parse_url(value: &str) -> Result<Url> {
    if value.len() > 8192
        || value.bytes().any(|b| b.is_ascii_control() || b == b'\\')
        || raw_path_has_dot_segment(value)?
    {
        return Err(Fault::new("URL_INVALID"));
    }
    let url = Url::parse(value).map_err(|_| Fault::new("URL_INVALID"))?;
    if !matches!(url.scheme(), "https" | "http")
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err(Fault::new("URL_INVALID"));
    }
    Ok(url)
}

fn raw_path_has_dot_segment(value: &str) -> Result<bool> {
    // WHATWG URL parsing removes dot segments, including percent-encoded dots.
    // Reject those inputs before parsing so a signed path is never silently rewritten.
    // Query/fragment bytes are deliberately outside this inspection.
    let resource = value.split(['?', '#']).next().unwrap_or(value);
    let (_, authority_and_path) = resource
        .split_once("://")
        .ok_or(Fault::new("URL_INVALID"))?;
    let Some((_, raw_path)) = authority_and_path.split_once('/') else {
        return Ok(false);
    };
    Ok(raw_path.split('/').any(|segment| {
        let bytes = segment.as_bytes();
        let (mut offset, mut dots) = (0, 0);
        while offset < bytes.len() {
            if bytes[offset] == b'.' {
                offset += 1;
            } else if bytes.get(offset..offset + 3).is_some_and(|escape| {
                escape[0] == b'%' && escape[1] == b'2' && matches!(escape[2], b'e' | b'E')
            }) {
                offset += 3;
            } else {
                return false;
            }
            dots += 1;
            if dots > 2 {
                return false;
            }
        }
        matches!(dots, 1 | 2)
    }))
}
fn bounded_file_name(name: &str) -> String {
    let mut out = String::new();
    for ch in name.chars() {
        if ch.is_control() || ch == '/' || ch == '\\' {
            continue;
        }
        if out.len() + ch.len_utf8() > 180 {
            break;
        }
        out.push(ch);
    }
    if out.is_empty() || out == "." || out == ".." {
        "download".into()
    } else {
        out
    }
}

pub fn validate_request(spec: &NetworkRequest, probe: bool) -> Result<()> {
    crate::validate_identity(&spec.task_id, spec.generation)?;
    crate::validate_limit(spec.speed_limit_bytes_per_second)?;
    let url = parse_url(&spec.url)?;
    let expected = parse_url(&spec.expected_origin)?;
    if expected.path() != "/"
        || expected.query().is_some()
        || expected.fragment().is_some()
        || expected.origin() != url.origin()
    {
        return Err(Fault::new("ORIGIN_MISMATCH"));
    }
    if url.scheme() == "http" && !spec.http_consent {
        return Err(Fault::new("HTTP_CONSENT_REQUIRED"));
    }
    if let Some(auth) = &spec.authorization {
        let origin = spec
            .credential_origin
            .as_deref()
            .ok_or(Fault::new("CREDENTIAL_ORIGIN_REQUIRED"))?;
        let credential = parse_url(origin)?;
        if credential.path() != "/"
            || credential.query().is_some()
            || credential.fragment().is_some()
            || credential.origin() != url.origin()
        {
            return Err(Fault::new("CREDENTIAL_ORIGIN_MISMATCH"));
        }
        if url.scheme() != "https" {
            return Err(Fault::new("CREDENTIALS_REQUIRE_HTTPS"));
        }
        if auth.len() > 8192
            || !(auth.starts_with("Basic ") || auth.starts_with("Bearer "))
            || HeaderValue::from_str(auth).is_err()
        {
            return Err(Fault::new("CREDENTIALS_INVALID"));
        }
    }
    if spec.max_connections == 0 || spec.max_connections > 8 {
        return Err(Fault::new("CONNECTION_LIMIT_INVALID"));
    }
    if let Some(hash) = &spec.expected_sha256
        && (hash.len() != 64 || !hash.bytes().all(|b| b.is_ascii_hexdigit()))
    {
        return Err(Fault::new("CHECKSUM_INVALID"));
    }
    if !probe {
        if spec
            .private_directory
            .as_ref()
            .is_none_or(|p| p.len() > 4096)
        {
            return Err(Fault::new("PRIVATE_PATH_REFUSED"));
        }
        if spec.file_name.as_ref().is_none_or(|n| {
            n.is_empty()
                || n.len() > 180
                || n == "."
                || n == ".."
                || n.chars().any(|c| c.is_control() || c == '/' || c == '\\')
        }) {
            return Err(Fault::new("FILE_NAME_INVALID"));
        }
    }
    Ok(())
}

pub async fn run(
    bridge: Arc<Bridge>,
    spec: NetworkRequest,
    task: Arc<Task>,
    probe: bool,
) -> (Result<()>, bool) {
    let result = inner(bridge, spec, task.clone(), probe).await;
    let gateway = {
        task.gateway
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
    };
    if let Some(gateway) = gateway
        && gateway.shutdown().await.is_err()
    {
        return (Err(Fault::new("TRANSPORT_JOIN_FAILED")), false);
    }
    let released = !matches!(
        result,
        Err(Fault {
            code: "NATIVE_PANIC",
            ..
        })
    );
    (result, released)
}
async fn inner(
    bridge: Arc<Bridge>,
    spec: NetworkRequest,
    task: Arc<Task>,
    probe: bool,
) -> Result<()> {
    if task.cancel.is_cancelled() {
        return Err(Fault::new("CANCELLED"));
    }
    let url = parse_url(&spec.url)?;
    let source_origin = url.origin().ascii_serialization();
    task.update(|s| {
        s.phase = "probing";
        s.source_origin = Some(source_origin);
        s.file_name = spec.file_name.clone();
    });
    let dir = if probe {
        None
    } else {
        let (bridge, id, given, resume, source) = (
            bridge.clone(),
            spec.task_id.clone(),
            spec.private_directory
                .clone()
                .ok_or(Fault::new("PRIVATE_PATH_REFUSED"))?,
            spec.resume,
            url.clone(),
        );
        Some(
            tokio::task::spawn_blocking(move || {
                let dir = staging::directory(&bridge, &id, &given)?;
                if dir.join(staging::PROOF).exists() || dir.join("verified.payload").exists() {
                    return Err(Fault::new("NETWORK_ALREADY_COMPLETE"));
                }
                let saved = staging::load_checkpoint(&dir)?;
                if let Some(saved) = &saved
                    && saved.url != source
                {
                    return Err(Fault::new("SOURCE_CHANGED_RESTART_REQUIRED"));
                }
                if !resume
                    && (saved.is_some()
                        || staging::part(&dir).exists()
                        || staging::destination(&dir).exists())
                {
                    return Err(Fault::new("STAGING_ALREADY_EXISTS"));
                }
                if resume && saved.is_none() && staging::part(&dir).exists() {
                    return Err(Fault::new("NON_RESUMABLE_RESTART_REQUIRED"));
                }
                Ok(dir)
            })
            .await
            .map_err(|_| Fault::new("NATIVE_PANIC"))??,
        )
    };
    if task.cancel.is_cancelled() {
        return Err(Fault::new("CANCELLED"));
    }
    let gateway = Gateway::start(spec.network)
        .await
        .map_err(|_| Fault::retry("NETWORK_GATEWAY_UNAVAILABLE"))?;
    {
        let mut slot = task
            .gateway
            .lock()
            .map_err(|_| Fault::new("NATIVE_STATE"))?;
        *slot = Some(gateway.clone());
    }
    if task.cancel.is_cancelled() {
        gateway.invalidate();
        return Err(Fault::new("CANCELLED"));
    }
    let mut headers = HeaderMap::new();
    if let Some(auth) = &spec.authorization {
        let mut value =
            HeaderValue::from_str(auth).map_err(|_| Fault::new("CREDENTIALS_INVALID"))?;
        value.set_sensitive(true);
        headers.insert(AUTHORIZATION, value);
    }
    let mut opts = Options {
        strict_http: true,
        buffer_budget: 8 * 1024 * 1024,
        max_connections: spec.max_connections,
        proxy: gateway.proxy(),
        connect_timeout: Duration::from_secs(20),
        read_timeout: Duration::from_secs(30),
        max_retries: 3,
        save_interval: Duration::from_secs(2),
        speed_limit: spec.speed_limit_bytes_per_second,
        ..Options::default()
    };
    opts.speak_as("BlackMarlin-Android/1.0".into());
    opts.checksum = spec
        .expected_sha256
        .as_deref()
        .and_then(bmd_engine::verify::Hash::parse);
    if probe {
        let result=tokio::select!{
            biased;
            _=task.cancel.cancelled()=>return Err(Fault::new("CANCELLED")),
            value=tokio::time::timeout(Duration::from_secs(15),bmd_engine::probe(url,headers,&opts))=>value.map_err(|_|Fault::retry("PROBE_TIMEOUT"))?,
        }.map_err(engine_error)?;
        if result.size.is_some_and(|s| s > i64::MAX as u64) {
            return Err(Fault::new("FILE_TOO_LARGE"));
        }
        // Shutting down and joining the gateway happens before this terminal snapshot is acked.
        task.update(|s| {
            s.phase = "probed";
            s.total_bytes = result.size;
            s.file_name = Some(bounded_file_name(&result.file_name));
            s.web_page = result.web_page;
        });
        return Ok(());
    }
    let dir = dir.ok_or(Fault::new("PRIVATE_PATH_REFUSED"))?;
    let ending = gateway.clone();
    opts.network_completion = Some(NetworkCompletion {
        task_id: spec.task_id.clone(),
        generation: spec.generation,
        proof_path: dir.join(staging::PROOF),
        release_network: ReleaseNetwork(Arc::new(move || {
            let gateway = ending.clone();
            Box::pin(async move { gateway.shutdown().await })
        })),
    });
    let request = Request {
        url,
        headers,
        dest: Destination::File(staging::destination(&dir)),
    };
    let download = if spec.resume {
        Download::resume_or_start(staging::checkpoint(&dir), request, opts, None, 0)
    } else {
        Download::start(request, opts)
    };
    let monitor = download.monitor();
    {
        let mut slot = task
            .monitor
            .lock()
            .map_err(|_| Fault::new("NATIVE_STATE"))?;
        *slot = Some(monitor.clone());
    }
    if task.cancel.is_cancelled() {
        monitor.cancel();
        gateway.invalidate();
    }
    let waiting = std::panic::AssertUnwindSafe(download.wait()).catch_unwind();
    tokio::pin!(waiting);
    let mut tick = tokio::time::interval(Duration::from_millis(250));
    let mut stop_sent = false;
    let mut checkpoint_tick = 0;
    let result = loop {
        tokio::select! {
            result=&mut waiting=>break result,
            _=task.cancel.cancelled(),if !stop_sent=>{stop_sent=true;gateway.invalidate();monitor.cancel();},
            _=tick.tick()=>{
                let rate=task.speed.load(Ordering::Relaxed);monitor.set_speed_limit((rate>0).then_some(rate));
                let snapshot=monitor.snapshot();
                task.update(|s|{if !stop_sent{s.phase=match snapshot.state{State::Probing=>"probing",State::Stopping=>"stopping",_=>"downloading"};}s.received_bytes=snapshot.downloaded.min(i64::MAX as u64);s.total_bytes=snapshot.size.filter(|n|*n<=i64::MAX as u64);s.resumable=snapshot.resumable;});
                checkpoint_tick+=1;
                if checkpoint_tick%8==0{let _=update_checkpoint(&dir,&task).await;}
            }
        }
    };
    // wait consumed and joined Download. Never acknowledge based only on a cancel request.
    task.monitor
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .take();
    let _ = update_checkpoint(&dir, &task).await;
    let outcome = match result {
        Ok(Ok(outcome)) => outcome,
        Ok(Err(error)) => {
            return Err(if task.cancel.is_cancelled() {
                Fault::new("CANCELLED")
            } else {
                engine_error(error)
            });
        }
        Err(_) => return Err(Fault::new("NATIVE_PANIC")),
    };
    let proof_path = dir.join(staging::PROOF);
    let read_path = proof_path.clone();
    let proof = tokio::task::spawn_blocking(move || NetworkCompleteProof::load(&read_path))
        .await
        .map_err(|_| Fault::new("NATIVE_PANIC"))?
        .map_err(|_| Fault::new("PROOF_INVALID"))?;
    if proof.task_id != spec.task_id
        || proof.generation != spec.generation
        || proof.size != outcome.size
        || proof.staging_path != outcome.path
    {
        return Err(Fault::new("PROOF_IDENTITY_MISMATCH"));
    }
    task.update(|s| {
        s.phase = "network_complete";
        s.received_bytes = proof.size;
        s.checkpoint_bytes = proof.size;
        s.total_bytes = Some(proof.size);
        s.network_complete_proof_path = Some(proof_path.to_string_lossy().into());
    });
    Ok(())
}
async fn update_checkpoint(dir: &std::path::Path, task: &Task) -> Result<()> {
    let dir: PathBuf = dir.into();
    let saved = tokio::task::spawn_blocking(move || staging::load_checkpoint(&dir))
        .await
        .map_err(|_| Fault::new("NATIVE_PANIC"))?;
    if let Ok(Some(saved)) = saved {
        task.update(|s| s.checkpoint_bytes = saved.downloaded().min(i64::MAX as u64));
    }
    Ok(())
}
fn engine_error(error: Error) -> Fault {
    match error {
        Error::Cancelled => Fault::new("CANCELLED"),
        Error::NoSpace { .. } | Error::DiskFull => Fault::retry("STORAGE_FULL"),
        Error::Io(error) => match error.raw_os_error() {
            Some(libc::ENOSPC) => Fault::retry("STORAGE_FULL"),
            _ => Fault::retry("STORAGE_IO"),
        },
        Error::DestinationUnavailable(_) | Error::DriveGone => Fault::retry("STORAGE_UNAVAILABLE"),
        Error::FileChanged => Fault::new("SOURCE_CHANGED_RESTART_REQUIRED"),
        Error::ChecksumMismatch(_) => Fault::new("CHECKSUM_MISMATCH"),
        Error::BadState(_) => Fault::new("CHECKPOINT_INVALID"),
        Error::NoRanges | Error::RangesUnavailable => Fault::new("RANGE_RESUME_UNAVAILABLE"),
        Error::Certificate(_) | Error::NoCertificates => Fault::new("TLS_CERTIFICATE_REFUSED"),
        Error::Status(status) => match status.as_u16() {
            401 => Fault::new("SOURCE_AUTH_REQUIRED"),
            403 => Fault::new("SOURCE_FORBIDDEN_OR_EXPIRED"),
            404 | 410 => Fault::new("SOURCE_UNAVAILABLE"),
            _ => Fault::retry("HTTP_ERROR"),
        },
        Error::GotWebPage | Error::LinkExpired => Fault::new("SOURCE_PAGE_OR_EXPIRED"),
        _ => Fault::retry("TRANSFER_FAILED"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn request(url: &str) -> NetworkRequest {
        NetworkRequest {
            task_id: "task".into(),
            generation: 1,
            url: url.into(),
            expected_origin: Url::parse(url).unwrap().origin().ascii_serialization(),
            credential_origin: None,
            private_directory: None,
            file_name: None,
            resume: false,
            http_consent: false,
            expected_sha256: None,
            authorization: None,
            network: crate::network::Network {
                handle: 1,
                generation: 1,
            },
            max_connections: 4,
            speed_limit_bytes_per_second: None,
        }
    }
    #[test]
    fn parsed_scheme_requires_consent_for_uppercase_http() {
        let mut r = request("HTTP://example.org/file");
        assert_eq!(
            validate_request(&r, true).unwrap_err().code,
            "HTTP_CONSENT_REQUIRED"
        );
        r.http_consent = true;
        assert!(validate_request(&r, true).is_ok());
        r.authorization = Some("Bearer secret".into());
        r.credential_origin = Some("http://example.org".into());
        assert_eq!(
            validate_request(&r, true).unwrap_err().code,
            "CREDENTIALS_REQUIRE_HTTPS"
        );
    }
    #[test]
    fn credentials_reject_embedded_userinfo_and_preserve_origin_port() {
        assert!(parse_url("https://user:secret@example.org/a").is_err());
        let u = parse_url("hTtPs://EXAMPLE.org:8443/a?secret=hidden").unwrap();
        assert_eq!(u.origin().ascii_serialization(), "https://example.org:8443");
    }
    #[test]
    fn raw_and_percent_encoded_dot_segments_are_refused_before_probe() {
        for path in [
            "/./file",
            "/a/../file",
            "/a/%2e/file",
            "/a/%2E%2e/file",
            "/a/.%2E/file",
            "/a/%2e./file",
            "/a//../file",
        ] {
            let spec = request(&format!("https://example.org{path}?sig=unchanged"));
            assert_eq!(
                validate_request(&spec, true).unwrap_err().code,
                "URL_INVALID",
                "{path}"
            );
        }
        assert!(parse_url(r"https://example.org\a\..\file").is_err());
    }
    #[test]
    fn signed_query_and_non_dot_percent_encoded_path_are_preserved() {
        for path in ["/a/.../file", "/a/%252e/file", "/a%2Eb/file", "/a/%2Efile"] {
            let raw = format!("https://example.org{path}");
            assert_eq!(parse_url(&raw).unwrap().path(), path);
        }
        let raw = "https://example.org/a%2Fb/%252e/file?sig=a%2Fb%2Bc+z&item=one&item=two&next=/a/../b&dot=%2e%2e%2f#review";
        assert_eq!(parse_url(raw).unwrap().as_str(), raw);
    }
    #[test]
    fn expected_and_credential_origins_include_the_nondefault_port() {
        let mut r = request("https://example.org:8443/file");
        r.expected_origin = "https://example.org".into();
        assert_eq!(
            validate_request(&r, true).unwrap_err().code,
            "ORIGIN_MISMATCH"
        );
        r.expected_origin = "https://EXAMPLE.org:8443".into();
        r.authorization = Some("Bearer secret".into());
        r.credential_origin = Some("https://example.org".into());
        assert_eq!(
            validate_request(&r, true).unwrap_err().code,
            "CREDENTIAL_ORIGIN_MISMATCH"
        );
        r.credential_origin = Some("https://example.org:8443".into());
        assert!(validate_request(&r, true).is_ok());
    }
    #[test]
    fn metadata_names_are_bounded_in_utf8_bytes() {
        let name = bounded_file_name(&"界".repeat(200));
        assert_eq!(name.len(), 180);
        assert!(!name.contains('/'));
    }
}
