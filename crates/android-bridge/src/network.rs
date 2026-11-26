//! Per-executor authenticated SOCKS5h relay. Every external DNS/connect is bound to one
//! admitted Android Network. No process binding, system proxy, or direct-route fallback.
use serde::{Deserialize, Serialize};
use std::{
    io,
    net::{Ipv4Addr, Ipv6Addr, SocketAddr},
    sync::Arc,
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    sync::{Mutex, Semaphore},
    task::{JoinHandle, JoinSet},
};
use tokio_util::sync::CancellationToken;

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Network {
    pub handle: u64,
    pub generation: u64,
}

pub struct Gateway {
    address: SocketAddr,
    username: String,
    password: String,
    stop: CancellationToken,
    admission: Arc<std::sync::Mutex<bool>>,
    // Keep the lock through await: simultaneous shutdown callers all observe the join.
    runner: Mutex<Runner>,
}
struct Runner {
    task: Option<JoinHandle<io::Result<()>>>,
    succeeded: Option<bool>,
}
impl Gateway {
    pub async fn start(network: Network) -> io::Result<Arc<Self>> {
        if network.handle == 0 || network.generation == 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "network not admitted",
            ));
        }
        let mut entropy = [0u8; 48];
        getrandom::fill(&mut entropy)
            .map_err(|_| io::Error::other("secure entropy unavailable"))?;
        let username = hex::encode(&entropy[..16]);
        let password = hex::encode(&entropy[16..]);
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
        let address = listener.local_addr()?;
        let stop = CancellationToken::new();
        let child = stop.clone();
        let admission = Arc::new(std::sync::Mutex::new(true));
        let gate = admission.clone();
        let user = username.clone();
        let pass = password.clone();
        let runner = tokio::spawn(async move {
            let slots = Arc::new(Semaphore::new(16));
            let mut tasks = JoinSet::new();
            let mut failed = false;
            loop {
                tokio::select! {
                    biased;
                    _ = child.cancelled() => break,
                    result = tasks.join_next(), if !tasks.is_empty() => {
                        if result.is_some_and(|r| r.is_err()) { failed = true; child.cancel(); break; }
                    }
                    accepted = listener.accept() => {
                        let (socket, peer) = match accepted { Ok(value) => value, Err(_) => { failed = true; child.cancel(); break; } };
                        if !peer.ip().is_loopback() { continue; }
                        let Ok(permit) = slots.clone().try_acquire_owned() else { continue; };
                        let (u,p,token,admitted) = (user.clone(),pass.clone(),child.clone(),gate.clone());
                        tasks.spawn(async move {
                            let _permit = permit;
                            // Connection-level refusal is normal; never return endpoint/error text.
                            let _ = relay(socket, network, &u, &p, &token, admitted).await;
                        });
                    }
                }
            }
            drop(listener); // admission closes before draining live sockets / blocking DNS.
            child.cancel();
            while let Some(joined) = tasks.join_next().await {
                failed |= joined.is_err();
            }
            if failed {
                Err(io::Error::other("relay worker failed"))
            } else {
                Ok(())
            }
        });
        Ok(Arc::new(Self {
            address,
            username,
            password,
            stop,
            admission,
            runner: Mutex::new(Runner {
                task: Some(runner),
                succeeded: None,
            }),
        }))
    }
    pub fn proxy(&self) -> bmd_engine::Proxy {
        bmd_engine::Proxy::Manual {
            url: format!("socks5h://{}", self.address),
            login: Some((self.username.clone(), self.password.clone())),
            http: true,
            https: true,
        }
    }
    pub fn invalidate(&self) {
        *self.admission.lock().unwrap_or_else(|e| e.into_inner()) = false;
        self.stop.cancel();
    }
    pub async fn shutdown(&self) -> io::Result<()> {
        self.invalidate();
        let mut guard = self.runner.lock().await;
        if let Some(runner) = guard.task.as_mut() {
            let succeeded = matches!(runner.await, Ok(Ok(())));
            guard.task.take();
            guard.succeeded = Some(succeeded);
        }
        if guard.succeeded == Some(true) {
            Ok(())
        } else {
            Err(io::Error::other("relay supervisor failed"))
        }
    }
}

impl Drop for Gateway {
    fn drop(&mut self) {
        self.invalidate();
    }
}

async fn relay(
    mut downstream: TcpStream,
    network: Network,
    user: &str,
    pass: &str,
    stop: &CancellationToken,
    admission: Arc<std::sync::Mutex<bool>>,
) -> io::Result<()> {
    let target = tokio::select! {
        biased;
        _ = stop.cancelled() => return Err(cancelled()),
        value = tokio::time::timeout(Duration::from_secs(10), handshake(&mut downstream,user,pass)) => value.map_err(|_|io::Error::new(io::ErrorKind::TimedOut,"proxy handshake timeout"))??,
    };
    // Resolution is noninterruptible on API 26. Always join its thread even after timeout /
    // stop; and re-check admission after it finishes BEFORE creating any upstream socket.
    let host = target.0;
    let port = target.1;
    let resolver_stop = stop.clone();
    let mut resolver = tokio::task::spawn_blocking(move || {
        resolve_if_admitted(network.handle, &host, port, &resolver_stop)
    });
    let addresses = tokio::select! {
        value = &mut resolver => value.map_err(|_|io::Error::other("resolver failed"))??,
        _ = stop.cancelled() => { let _ = resolver.await; return Err(cancelled()); },
        _ = tokio::time::sleep(Duration::from_secs(15)) => { let _ = resolver.await; return Err(io::Error::new(io::ErrorKind::TimedOut,"resolution timeout")); },
    };
    if stop.is_cancelled() {
        return Err(cancelled());
    }
    if addresses.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::NotFound,
            "no network address",
        ));
    }
    // Sequential address attempts are deliberate: no race may introduce an unbound socket.
    let mut connected = None;
    for address in addresses.into_iter().take(16) {
        if stop.is_cancelled() {
            return Err(cancelled());
        }
        let result = tokio::select! {
            biased;
            _ = stop.cancelled() => return Err(cancelled()),
            value = tokio::time::timeout(Duration::from_secs(10), platform::connect(network.handle,address,&admission)) => value,
        };
        if let Ok(Ok(socket)) = result {
            connected = Some(socket);
            break;
        }
    }
    let Some(mut upstream) = connected else {
        downstream
            .write_all(&[5, 4, 0, 1, 0, 0, 0, 0, 0, 0])
            .await?;
        return Err(io::Error::new(
            io::ErrorKind::NotConnected,
            "admitted route unavailable",
        ));
    };
    if stop.is_cancelled() {
        return Err(cancelled());
    }
    downstream
        .write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0])
        .await?;
    tokio::select! {
        biased;
        _ = stop.cancelled() => Err(cancelled()),
        result = tokio::io::copy_bidirectional_with_sizes(&mut downstream,&mut upstream,16*1024,16*1024) => result.map(|_|()),
    }
}

fn cancelled() -> io::Error {
    io::Error::new(io::ErrorKind::Interrupted, "network admission closed")
}

fn resolve_if_admitted(
    network: u64,
    host: &str,
    port: u16,
    stop: &CancellationToken,
) -> io::Result<Vec<SocketAddr>> {
    // A queued blocking job is not an in-flight platform lookup. Recheck when its
    // worker starts so stopping an executor does not begin queued DNS work later.
    if stop.is_cancelled() {
        return Err(cancelled());
    }
    platform::resolve(network, host, port)
}

async fn handshake(socket: &mut TcpStream, user: &str, pass: &str) -> io::Result<(String, u16)> {
    let version = socket.read_u8().await?;
    let count = socket.read_u8().await? as usize;
    if version != 5 || count == 0 || count > 16 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "SOCKS greeting refused",
        ));
    }
    let mut methods = vec![0; count];
    socket.read_exact(&mut methods).await?;
    if !methods.contains(&2) {
        socket.write_all(&[5, 255]).await?;
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "proxy authentication required",
        ));
    }
    socket.write_all(&[5, 2]).await?;
    if socket.read_u8().await? != 1 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "auth version refused",
        ));
    }
    let n = socket.read_u8().await? as usize;
    let mut u = vec![0; n];
    socket.read_exact(&mut u).await?;
    let n = socket.read_u8().await? as usize;
    let mut p = vec![0; n];
    socket.read_exact(&mut p).await?;
    if !constant_eq(&u, user.as_bytes()) || !constant_eq(&p, pass.as_bytes()) {
        socket.write_all(&[1, 1]).await?;
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "proxy authentication refused",
        ));
    }
    socket.write_all(&[1, 0]).await?;
    let mut command = [0u8; 4];
    socket.read_exact(&mut command).await?;
    if command[..3] != [5, 1, 0] {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "CONNECT required",
        ));
    }
    let host = match command[3] {
        1 => {
            let mut ip = [0; 4];
            socket.read_exact(&mut ip).await?;
            Ipv4Addr::from(ip).to_string()
        }
        4 => {
            let mut ip = [0; 16];
            socket.read_exact(&mut ip).await?;
            Ipv6Addr::from(ip).to_string()
        }
        3 => {
            let n = socket.read_u8().await? as usize;
            if n == 0 {
                return Err(io::Error::other("empty host"));
            };
            let mut h = vec![0; n];
            socket.read_exact(&mut h).await?;
            String::from_utf8(h).map_err(|_| io::Error::other("invalid host"))?
        }
        _ => return Err(io::Error::other("address type refused")),
    };
    let port = socket.read_u16().await?;
    if port == 0 || host.contains('\0') {
        return Err(io::Error::other("target refused"));
    }
    Ok((host, port))
}
fn constant_eq(a: &[u8], b: &[u8]) -> bool {
    let mut difference = a.len() ^ b.len();
    for (i, byte) in b.iter().enumerate() {
        difference |= (a.get(i).copied().unwrap_or(0) ^ byte) as usize;
    }
    difference == 0
}

#[cfg(target_os = "android")]
#[allow(unsafe_code)]
mod platform {
    use super::*;
    use std::{ffi::CString, net::IpAddr, os::fd::AsRawFd};
    #[link(name = "android")]
    unsafe extern "C" {
        fn android_getaddrinfofornetwork(
            network: u64,
            node: *const libc::c_char,
            service: *const libc::c_char,
            hints: *const libc::addrinfo,
            result: *mut *mut libc::addrinfo,
        ) -> libc::c_int;
        fn android_setsocknetwork(network: u64, fd: libc::c_int) -> libc::c_int;
    }
    pub fn resolve(network: u64, host: &str, port: u16) -> io::Result<Vec<SocketAddr>> {
        if network == 0 {
            return Err(io::Error::other("missing network"));
        }
        if let Ok(ip) = host.parse::<IpAddr>() {
            return Ok(vec![SocketAddr::new(ip, port)]);
        }
        let host = CString::new(host).map_err(|_| io::Error::other("invalid host"))?;
        let service =
            CString::new(port.to_string()).map_err(|_| io::Error::other("invalid port"))?;
        // SAFETY: addrinfo is a C POD; pointers are live for the synchronous call. Android
        // allocates the result chain, which is read with family/length checks and freed once.
        unsafe {
            let mut hints: libc::addrinfo = std::mem::zeroed();
            hints.ai_family = libc::AF_UNSPEC;
            hints.ai_socktype = libc::SOCK_STREAM;
            hints.ai_protocol = libc::IPPROTO_TCP;
            let mut head = std::ptr::null_mut();
            if android_getaddrinfofornetwork(
                network,
                host.as_ptr(),
                service.as_ptr(),
                &hints,
                &mut head,
            ) != 0
            {
                return Err(io::Error::new(
                    io::ErrorKind::NotFound,
                    "admitted DNS refused",
                ));
            }
            struct Results(*mut libc::addrinfo);
            impl Drop for Results {
                fn drop(&mut self) {
                    unsafe { libc::freeaddrinfo(self.0) }
                }
            }
            let _owned = Results(head);
            let mut cursor = head;
            let mut out = Vec::new();
            let mut seen = 0;
            while !cursor.is_null() && out.len() < 16 && seen < 256 {
                seen += 1;
                let a = &*cursor;
                if !a.ai_addr.is_null()
                    && a.ai_family == libc::AF_INET
                    && a.ai_addrlen as usize >= std::mem::size_of::<libc::sockaddr_in>()
                {
                    let address = &*(a.ai_addr as *const libc::sockaddr_in);
                    out.push(SocketAddr::new(
                        IpAddr::V4(Ipv4Addr::from(address.sin_addr.s_addr.to_ne_bytes())),
                        u16::from_be(address.sin_port),
                    ));
                } else if !a.ai_addr.is_null()
                    && a.ai_family == libc::AF_INET6
                    && a.ai_addrlen as usize >= std::mem::size_of::<libc::sockaddr_in6>()
                {
                    let address = &*(a.ai_addr as *const libc::sockaddr_in6);
                    out.push(SocketAddr::V6(std::net::SocketAddrV6::new(
                        Ipv6Addr::from(address.sin6_addr.s6_addr),
                        u16::from_be(address.sin6_port),
                        address.sin6_flowinfo,
                        address.sin6_scope_id,
                    )));
                }
                cursor = a.ai_next;
            }
            if out.is_empty() {
                Err(io::Error::new(
                    io::ErrorKind::NotFound,
                    "admitted DNS empty",
                ))
            } else {
                Ok(out)
            }
        }
    }
    pub async fn connect(
        network: u64,
        address: SocketAddr,
        admission: &std::sync::Mutex<bool>,
    ) -> io::Result<TcpStream> {
        let socket = {
            let admitted = admission
                .lock()
                .map_err(|_| io::Error::other("admission poisoned"))?;
            if !*admitted {
                return Err(cancelled());
            }
            if network == 0 {
                return Err(io::Error::other("missing network"));
            }
            let socket = socket2::Socket::new(
                socket2::Domain::for_address(address),
                socket2::Type::STREAM,
                Some(socket2::Protocol::TCP),
            )?;
            // SAFETY: fd is a live, exclusively-owned unconnected socket; network is the admitted
            // Android Network handle. A failure closes it by RAII and never retries unbound.
            if unsafe { android_setsocknetwork(network, socket.as_raw_fd()) } != 0 {
                return Err(io::Error::last_os_error());
            }
            socket.set_nonblocking(true)?;
            socket.set_tcp_nodelay(true)?;
            match socket.connect(&address.into()) {
                Ok(()) => {}
                Err(e) if e.raw_os_error() == Some(libc::EINPROGRESS) => {}
                Err(e) => return Err(e),
            }
            TcpStream::from_std(socket.into())?
        };
        socket.writable().await?;
        if let Some(error) = socket.take_error()? {
            return Err(error);
        };
        Ok(socket)
    }
}

// Host builds exercise bridge state/disk behavior. Production never falls back to host DNS.
#[cfg(not(target_os = "android"))]
mod platform {
    use super::*;
    pub fn resolve(_network: u64, _host: &str, _port: u16) -> io::Result<Vec<SocketAddr>> {
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "Android Network required",
        ))
    }
    pub async fn connect(
        _network: u64,
        _address: SocketAddr,
        _admission: &std::sync::Mutex<bool>,
    ) -> io::Result<TcpStream> {
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "Android Network required",
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn queued_resolver_does_not_begin_after_stop() {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .max_blocking_threads(1)
            .enable_all()
            .build()
            .unwrap();
        runtime.block_on(async {
            let (entered, ready) = tokio::sync::oneshot::channel();
            let (release, blocked) = std::sync::mpsc::channel();
            let occupying = tokio::task::spawn_blocking(move || {
                entered.send(()).unwrap();
                blocked.recv().unwrap();
            });
            ready.await.unwrap();
            let stop = CancellationToken::new();
            let queued_stop = stop.clone();
            let queued = tokio::task::spawn_blocking(move || {
                resolve_if_admitted(1, "example.invalid", 443, &queued_stop)
            });
            stop.cancel();
            release.send(()).unwrap();
            occupying.await.unwrap();
            // The host adapter would return Unsupported if platform resolution ran.
            assert_eq!(
                queued.await.unwrap().unwrap_err().kind(),
                io::ErrorKind::Interrupted
            );
        });
    }
    #[tokio::test]
    async fn proxy_refuses_unauthenticated_clients_and_shutdown_joins() {
        let gateway = Gateway::start(Network {
            handle: 1,
            generation: 1,
        })
        .await
        .unwrap();
        let mut socket = TcpStream::connect(gateway.address).await.unwrap();
        socket.write_all(&[5, 1, 0]).await.unwrap();
        let mut answer = [0; 2];
        socket.read_exact(&mut answer).await.unwrap();
        assert_eq!(answer, [5, 255]);
        gateway.shutdown().await.unwrap();
        assert!(TcpStream::connect(gateway.address).await.is_err());
    }
    #[test]
    fn secret_comparison_checks_length_and_every_byte() {
        assert!(constant_eq(b"secret", b"secret"));
        assert!(!constant_eq(b"secretX", b"secret"));
        assert!(!constant_eq(b"secrex", b"secret"));
    }
    #[tokio::test]
    async fn cancelled_shutdown_future_never_loses_join_ownership() {
        let release = Arc::new(tokio::sync::Notify::new());
        let done = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let (r, d) = (release.clone(), done.clone());
        let runner = tokio::spawn(async move {
            r.notified().await;
            d.store(true, std::sync::atomic::Ordering::SeqCst);
            Ok(())
        });
        let gateway = Arc::new(Gateway {
            address: "127.0.0.1:1".parse().unwrap(),
            username: "u".into(),
            password: "p".into(),
            stop: CancellationToken::new(),
            admission: Arc::new(std::sync::Mutex::new(true)),
            runner: Mutex::new(Runner {
                task: Some(runner),
                succeeded: None,
            }),
        });
        assert!(
            tokio::time::timeout(Duration::from_millis(5), gateway.shutdown())
                .await
                .is_err()
        );
        assert!(!done.load(std::sync::atomic::Ordering::SeqCst));
        let g = gateway.clone();
        let second = tokio::spawn(async move { g.shutdown().await });
        tokio::task::yield_now().await;
        assert!(!second.is_finished());
        release.notify_one();
        second.await.unwrap().unwrap();
        assert!(done.load(std::sync::atomic::Ordering::SeqCst));
        gateway.shutdown().await.unwrap();
    }
    #[tokio::test]
    async fn proxy_failure_never_falls_back_to_a_reachable_direct_socket() {
        let target = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let address = target.local_addr().unwrap();
        let gateway = Gateway::start(Network {
            handle: 1,
            generation: 1,
        })
        .await
        .unwrap();
        let url = url::Url::parse(&format!("http://{address}/payload")).unwrap();
        let options = bmd_engine::Options {
            strict_http: true,
            proxy: gateway.proxy(),
            ..Default::default()
        };
        let client = bmd_engine::http_client(&options, &url).unwrap();
        assert!(
            tokio::time::timeout(Duration::from_secs(2), client.get(url).send())
                .await
                .unwrap()
                .is_err()
        );
        assert!(
            tokio::time::timeout(Duration::from_millis(20), target.accept())
                .await
                .is_err()
        );
        gateway.shutdown().await.unwrap();
    }
}
