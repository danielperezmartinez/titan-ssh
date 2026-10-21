//! The RDP session engine (ADR-0028), independent of JNI.
//!
//! The engine never opens a socket: the app hands it the bytes of an SSH
//! `direct-tcpip` channel with [`RdpSession::feed`] and sends what it gets from
//! [`RdpSession::drain`]. In between, IronRDP runs the connection (X.224
//! negotiation, TLS, NLA/CredSSP, capabilities) and the active session on a
//! single-threaded Tokio runtime on a thread of its own. The app learns what happens through
//! [`RdpSession::next_event`] and reads the pixels with
//! [`RdpSession::copy_frame`].

use std::collections::VecDeque;
use std::fmt::Write as _;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

use ironrdp_connector::connection_activation::ConnectionActivationState;
use ironrdp_connector::sspi::generator::NetworkRequest;
use ironrdp_connector::{
    ConnectionResult, ConnectorError, ConnectorErrorKind, ConnectorResult, Credentials,
};
use ironrdp_core::WriteBuf;
use ironrdp_displaycontrol::client::DisplayControlClient;
use ironrdp_displaycontrol::pdu::MonitorLayoutEntry;
use ironrdp_graphics::image_processing::PixelFormat;
use ironrdp_input::{Database, MouseButton, MousePosition, Operation, Scancode, WheelRotations};
use ironrdp_pdu::gcc::KeyboardType;
use ironrdp_pdu::rdp::capability_sets::MajorPlatformType;
use ironrdp_pdu::rdp::client_info::{PerformanceFlags, TimezoneInfo};
use ironrdp_session::image::DecodedImage;
use ironrdp_session::{ActiveStageBuilder, ActiveStageOutput, SessionError, fast_path};
use ironrdp_tokio::{FramedWrite as _, single_sequence_step_read, split_tokio_framed};
use tokio::io::{AsyncReadExt as _, AsyncWriteExt as _, DuplexStream, ReadHalf, WriteHalf};
use tokio::sync::{mpsc, oneshot};

/// How the app connects; the password lives here only for the connection.
#[derive(Clone)]
pub struct RdpConfig {
    pub username: String,
    pub password: String,
    pub domain: Option<String>,
    /// The name the server is known by (CredSSP's target and TLS's SNI).
    pub server_name: String,
    pub width: u16,
    pub height: u16,
    /// Also offers TLS security with the server's own login screen, for servers
    /// without NLA (xrdp). NLA is always offered and preferred.
    pub allow_tls_without_nla: bool,
    pub client_name: String,
}

/// Why a session ended; the app maps each one to its own message.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EndKind {
    /// The app closed it, or the user signed out.
    Closed,
    /// The server ended it (disconnected from elsewhere, idle limit…).
    Server,
    /// The user did not trust the server's certificate.
    CertificateRejected,
    /// The server rejected the credentials.
    Auth,
    /// The server and the client share no security protocol (e.g. NLA is
    /// required but was not allowed, or the other way round).
    Negotiation,
    /// The tunnel closed under the session.
    Transport,
    Error,
}

impl EndKind {
    pub fn code(self) -> &'static str {
        match self {
            EndKind::Closed => "closed",
            EndKind::Server => "server",
            EndKind::CertificateRejected => "certificate",
            EndKind::Auth => "auth",
            EndKind::Negotiation => "negotiation",
            EndKind::Transport => "transport",
            EndKind::Error => "error",
        }
    }
}

/// Something the app must react to, in order.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Event {
    /// The server's TLS certificate, before any credential is sent. The
    /// session waits for [`RdpSession::answer_certificate`].
    Certificate { sha256: String, subject: String },
    /// The desktop is ready, or changed size, at this size.
    Desktop { width: u16, height: u16 },
    /// There are new pixels: take them with [`RdpSession::copy_frame`].
    /// Coalesced: at most one is pending at a time.
    Frame,
    /// The session is over; no event follows.
    Ended { kind: EndKind, message: String },
}

/// Input from the app, in remote desktop coordinates.
#[derive(Debug, Clone)]
pub enum Input {
    MouseMove {
        x: u16,
        y: u16,
    },
    /// 0 left, 1 middle, 2 right, 3 back, 4 forward.
    MouseButton {
        button: u8,
        down: bool,
    },
    /// 120 units are one notch of a wheel; positive is up (or right).
    Wheel {
        vertical: bool,
        units: i16,
    },
    /// A PC/AT set-1 scancode, `0xE0xx` for the extended ones.
    Key {
        scancode: u16,
        down: bool,
    },
    /// Text that has no key, such as what a soft keyboard types.
    Unicode {
        ch: char,
        down: bool,
    },
    /// Releases every key and button still down (e.g. when the tab loses focus).
    ReleaseAll,
    /// Asks the server for a new desktop size (Display Control channel).
    Resize {
        width: u16,
        height: u16,
    },
    /// Signs out of the RDP connection politely.
    Close,
}

/// The part of a frame [`RdpSession::copy_frame`] copied.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rect {
    pub x: u16,
    pub y: u16,
    pub width: u16,
    pub height: u16,
}

#[derive(Debug, PartialEq, Eq)]
pub enum CopyResult {
    /// Nothing changed since the last copy.
    Unchanged,
    /// The destination does not have the desktop's size; [`RdpSession::frame_size`] has it.
    WrongSize,
    Copied(Rect),
}

struct Dirty {
    left: u16,
    top: u16,
    right: u16,
    bottom: u16,
}

struct FrameState {
    image: Option<DecodedImage>,
    dirty: Option<Dirty>,
}

struct Shared {
    events: Mutex<VecDeque<Event>>,
    events_changed: Condvar,
    frame: Mutex<FrameState>,
    frame_pending: AtomicBool,
    certificate_answer: Mutex<Option<oneshot::Sender<bool>>>,
}

impl Shared {
    fn push(&self, event: Event) {
        let mut events = self.events.lock().unwrap_or_else(|e| e.into_inner());
        events.push_back(event);
        self.events_changed.notify_all();
    }

    fn frame_changed(&self) {
        if !self.frame_pending.swap(true, Ordering::AcqRel) {
            self.push(Event::Frame);
        }
    }
}

const PIPE_CAPACITY: usize = 256 * 1024;

/// One RDP connection. Dropping it stops everything it runs.
pub struct RdpSession {
    shared: Arc<Shared>,
    handle: tokio::runtime::Handle,
    stop: Mutex<Option<oneshot::Sender<()>>>,
    thread: Mutex<Option<std::thread::JoinHandle<()>>>,
    input: mpsc::UnboundedSender<Input>,
    feed: Mutex<Option<WriteHalf<DuplexStream>>>,
    drain: Mutex<Option<ReadHalf<DuplexStream>>>,
}

impl RdpSession {
    /// Starts connecting with [config]; the first bytes go out through [`Self::drain`].
    pub fn start(config: RdpConfig) -> std::io::Result<Self> {
        let shared = Arc::new(Shared {
            events: Mutex::new(VecDeque::new()),
            events_changed: Condvar::new(),
            frame: Mutex::new(FrameState {
                image: None,
                dirty: None,
            }),
            frame_pending: AtomicBool::new(false),
            certificate_answer: Mutex::new(None),
        });
        let (engine_end, app_end) = tokio::io::duplex(PIPE_CAPACITY);
        let (app_read, app_write) = tokio::io::split(app_end);
        let (input_tx, input_rx) = mpsc::unbounded_channel();
        let (stop_tx, stop_rx) = oneshot::channel::<()>();
        // The engine runs on a thread of its own with a single-threaded runtime:
        // IronRDP's futures need not be Send, and nothing else shares it.
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_time()
            .build()?;
        let handle = runtime.handle().clone();
        let task_shared = Arc::clone(&shared);
        let thread = std::thread::Builder::new().name("titan-rdp".to_owned()).spawn(move || {
            runtime.block_on(async move {
                tokio::select! {
                    outcome = run(config, engine_end, &task_shared, input_rx) => {
                        let (kind, message) = match outcome {
                            Ok(()) => (EndKind::Closed, String::new()),
                            Err(failure) => (failure.kind, failure.message),
                        };
                        task_shared.push(Event::Ended { kind, message });
                    }
                    _ = stop_rx => {
                        task_shared.push(Event::Ended { kind: EndKind::Closed, message: String::new() });
                    }
                }
            });
        })?;
        Ok(Self {
            shared,
            handle,
            stop: Mutex::new(Some(stop_tx)),
            thread: Mutex::new(Some(thread)),
            input: input_tx,
            feed: Mutex::new(Some(app_write)),
            drain: Mutex::new(Some(app_read)),
        })
    }

    /// Runs [future] to the end on the calling thread. The pipe halves need no
    /// IO or timer driver, so any thread can drive them.
    fn block_on<F: std::future::Future>(&self, future: F) -> F::Output {
        self.handle.block_on(future)
    }

    /// Hands the engine bytes that came from the server. Blocks while the
    /// engine is behind; false once the session no longer reads.
    pub fn feed(&self, data: &[u8]) -> bool {
        let mut guard = self.feed.lock().unwrap_or_else(|e| e.into_inner());
        let Some(writer) = guard.as_mut() else {
            return false;
        };
        self.block_on(writer.write_all(data)).is_ok()
    }

    /// The server side of the tunnel closed: the engine reads an end of stream.
    pub fn feed_eof(&self) {
        let mut guard = self.feed.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(mut writer) = guard.take() {
            let _ = self.block_on(writer.shutdown());
        }
    }

    /// Waits for bytes to send to the server and puts them in [buf]. Returns
    /// how many, or `None` once the engine will send nothing more.
    pub fn drain(&self, buf: &mut [u8]) -> Option<usize> {
        let mut guard = self.drain.lock().unwrap_or_else(|e| e.into_inner());
        let reader = guard.as_mut()?;
        match self.block_on(reader.read(buf)) {
            Ok(0) | Err(_) => {
                guard.take();
                None
            }
            Ok(n) => Some(n),
        }
    }

    /// The next event, waiting up to [timeout] for one.
    pub fn next_event(&self, timeout: Duration) -> Option<Event> {
        let events = self.shared.events.lock().unwrap_or_else(|e| e.into_inner());
        let (mut events, _) = self
            .shared
            .events_changed
            .wait_timeout_while(events, timeout, |events| events.is_empty())
            .unwrap_or_else(|e| e.into_inner());
        events.pop_front()
    }

    /// Answers [`Event::Certificate`]: [accept] lets the connection go on.
    pub fn answer_certificate(&self, accept: bool) {
        let sender = self
            .shared
            .certificate_answer
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take();
        if let Some(sender) = sender {
            let _ = sender.send(accept);
        }
    }

    pub fn send(&self, input: Input) {
        let _ = self.input.send(input);
    }

    /// The desktop's current size, or (0, 0) before it is connected.
    pub fn frame_size(&self) -> (u16, u16) {
        let frame = self.shared.frame.lock().unwrap_or_else(|e| e.into_inner());
        frame
            .image
            .as_ref()
            .map_or((0, 0), |image| (image.width(), image.height()))
    }

    /// Copies what changed since the last copy into [dst], a whole frame of
    /// [width] × [height] pixels, 4 bytes each: R, G, B, A, or B, G, R, A with
    /// [bgra]. Alpha is always opaque.
    pub fn copy_frame(&self, dst: &mut [u8], width: u16, height: u16, bgra: bool) -> CopyResult {
        self.shared.frame_pending.store(false, Ordering::Release);
        let mut frame = self.shared.frame.lock().unwrap_or_else(|e| e.into_inner());
        let FrameState { image, dirty } = &mut *frame;
        let Some(image) = image.as_ref() else {
            return CopyResult::Unchanged;
        };
        if image.width() != width
            || image.height() != height
            || dst.len() < usize::from(width) * usize::from(height) * 4
        {
            return CopyResult::WrongSize;
        }
        let Some(d) = dirty.take() else {
            return CopyResult::Unchanged;
        };
        let right = d.right.min(width - 1);
        let bottom = d.bottom.min(height - 1);
        if d.left > right || d.top > bottom {
            return CopyResult::Unchanged;
        }
        let src = image.data();
        let stride = image.stride();
        let row_bytes = (usize::from(right - d.left) + 1) * 4;
        for y in usize::from(d.top)..=usize::from(bottom) {
            let start = y * stride + usize::from(d.left) * 4;
            let src_row = &src[start..start + row_bytes];
            let dst_start = (y * usize::from(width) + usize::from(d.left)) * 4;
            let dst_row = &mut dst[dst_start..dst_start + row_bytes];
            if bgra {
                for (s, t) in src_row
                    .as_chunks::<4>()
                    .0
                    .iter()
                    .zip(dst_row.as_chunks_mut::<4>().0.iter_mut())
                {
                    t[0] = s[2];
                    t[1] = s[1];
                    t[2] = s[0];
                    t[3] = 0xFF;
                }
            } else {
                for (s, t) in src_row
                    .as_chunks::<4>()
                    .0
                    .iter()
                    .zip(dst_row.as_chunks_mut::<4>().0.iter_mut())
                {
                    t[0] = s[0];
                    t[1] = s[1];
                    t[2] = s[2];
                    t[3] = 0xFF;
                }
            }
        }
        CopyResult::Copied(Rect {
            x: d.left,
            y: d.top,
            width: right - d.left + 1,
            height: bottom - d.top + 1,
        })
    }
}

impl RdpSession {
    /// Stops the engine without waiting: a blocked [`Self::feed`] or
    /// [`Self::drain`] returns, and [`Event::Ended`] follows if it had not yet.
    pub fn shutdown(&self) {
        self.answer_certificate(false);
        if let Some(stop) = self.stop.lock().unwrap_or_else(|e| e.into_inner()).take() {
            let _ = stop.send(());
        }
    }
}

impl Drop for RdpSession {
    fn drop(&mut self) {
        self.shutdown();
        self.feed.lock().unwrap_or_else(|e| e.into_inner()).take();
        self.drain.lock().unwrap_or_else(|e| e.into_inner()).take();
        if let Some(thread) = self.thread.lock().unwrap_or_else(|e| e.into_inner()).take() {
            let _ = thread.join();
        }
    }
}

/// How a session failed.
#[derive(Debug)]
pub struct Failure {
    pub kind: EndKind,
    pub message: String,
}

impl Failure {
    fn new(kind: EndKind, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
        }
    }
}

fn describe(error: &dyn std::error::Error) -> String {
    let mut text = error.to_string();
    let mut source = error.source();
    while let Some(cause) = source {
        let _ = write!(text, ": {cause}");
        source = cause.source();
    }
    text
}

impl From<ConnectorError> for Failure {
    fn from(error: ConnectorError) -> Self {
        let kind = match error.kind() {
            ConnectorErrorKind::Credssp(_) | ConnectorErrorKind::AccessDenied => EndKind::Auth,
            ConnectorErrorKind::Negotiation(_) => EndKind::Negotiation,
            _ if is_transport(&error) => EndKind::Transport,
            _ => EndKind::Error,
        };
        Failure::new(kind, describe(&error))
    }
}

impl From<SessionError> for Failure {
    fn from(error: SessionError) -> Self {
        let kind = if is_transport(&error) {
            EndKind::Transport
        } else {
            EndKind::Error
        };
        Failure::new(kind, describe(&error))
    }
}

/// Whether [error] comes from the tunnel closing rather than from RDP itself.
fn is_transport(error: &(dyn std::error::Error + 'static)) -> bool {
    let mut current: Option<&(dyn std::error::Error + 'static)> = Some(error);
    while let Some(e) = current {
        if let Some(io) = e.downcast_ref::<std::io::Error>() {
            return matches!(
                io.kind(),
                std::io::ErrorKind::UnexpectedEof
                    | std::io::ErrorKind::BrokenPipe
                    | std::io::ErrorKind::ConnectionReset
                    | std::io::ErrorKind::ConnectionAborted
            );
        }
        current = e.source();
    }
    false
}

/// Kerberos would need to reach a KDC; without one, CredSSP uses NTLM.
struct NoNetwork;

impl ironrdp_tokio::NetworkClient for NoNetwork {
    async fn send(&mut self, _request: &NetworkRequest) -> ConnectorResult<Vec<u8>> {
        Err(ironrdp_connector::general_err!(
            "no network access for Kerberos; NTLM is used"
        ))
    }
}

fn connector_config(config: &RdpConfig) -> ironrdp_connector::Config {
    ironrdp_connector::Config {
        credentials: Credentials::UsernamePassword {
            username: config.username.clone(),
            password: config.password.clone(),
        },
        domain: config.domain.clone().filter(|d| !d.is_empty()),
        enable_tls: config.allow_tls_without_nla,
        enable_credssp: true,
        keyboard_type: KeyboardType::IbmEnhanced,
        keyboard_subtype: 0,
        // 0: the server keeps its own layout, which is what scancodes mean there.
        keyboard_layout: 0,
        keyboard_functional_keys_count: 12,
        ime_file_name: String::new(),
        dig_product_id: String::new(),
        desktop_size: ironrdp_connector::DesktopSize {
            width: config.width,
            height: config.height,
        },
        bitmap: None,
        client_build: 0,
        client_name: config.client_name.clone(),
        client_dir: "C:\\Windows\\System32\\mstscax.dll".to_owned(),
        #[cfg(target_os = "android")]
        platform: MajorPlatformType::ANDROID,
        #[cfg(windows)]
        platform: MajorPlatformType::WINDOWS,
        #[cfg(not(any(target_os = "android", windows)))]
        platform: MajorPlatformType::UNIX,
        // The server's pointer is drawn into the frame: the app paints one image.
        enable_server_pointer: true,
        pointer_software_rendering: true,
        request_data: None,
        // With TLS security the server would show its own login screen; the
        // credentials in the Client Info PDU sign in straight away.
        autologon: !config.password.is_empty(),
        enable_audio_playback: false,
        compression_type: None,
        multitransport_flags: None,
        performance_flags: PerformanceFlags::default(),
        desktop_scale_factor: 0,
        hardware_id: None,
        license_cache: None,
        timezone_info: TimezoneInfo::default(),
        alternate_shell: String::new(),
        work_dir: String::new(),
    }
}

trait AsyncStream: tokio::io::AsyncRead + tokio::io::AsyncWrite {}
impl<T> AsyncStream for T where T: tokio::io::AsyncRead + tokio::io::AsyncWrite {}
type Upgraded = ironrdp_tokio::TokioFramed<Box<dyn AsyncStream + Unpin + Send + Sync>>;

async fn run(
    config: RdpConfig,
    io: DuplexStream,
    shared: &Arc<Shared>,
    mut input: mpsc::UnboundedReceiver<Input>,
) -> Result<(), Failure> {
    let (result, framed) = connect(&config, io, shared).await?;
    active_session(result, framed, shared, &mut input).await
}

async fn connect(
    config: &RdpConfig,
    io: DuplexStream,
    shared: &Arc<Shared>,
) -> Result<(ConnectionResult, Upgraded), Failure> {
    let mut framed = ironrdp_tokio::TokioFramed::new(io);
    // The client address the server logs; the real one is the SSH server's.
    let client_addr = core::net::SocketAddr::from(([127, 0, 0, 1], 0));
    let drdynvc = ironrdp_dvc::DrdynvcClient::new()
        .with_dynamic_channel(DisplayControlClient::new(|_| Ok(Vec::new())));
    let mut connector =
        ironrdp_connector::ClientConnector::new(connector_config(config), client_addr)
            .with_static_channel(drdynvc);

    let should_upgrade = ironrdp_tokio::connect_begin(&mut framed, &mut connector).await?;
    let (stream, leftover) = framed.into_inner();
    let (tls, certificate) = tls::upgrade(stream, &config.server_name)
        .await
        .map_err(|e| Failure::new(EndKind::Error, format!("TLS: {}", describe(&e))))?;

    // The certificate is checked before CredSSP or the Client Info PDU sends
    // any credential (ADR-0028, like the SSH host key of ADR-0005).
    let (sha256, subject) = tls::fingerprint(&certificate);
    let (answer_tx, answer_rx) = oneshot::channel();
    *shared
        .certificate_answer
        .lock()
        .unwrap_or_else(|e| e.into_inner()) = Some(answer_tx);
    shared.push(Event::Certificate { sha256, subject });
    if !answer_rx.await.unwrap_or(false) {
        return Err(Failure::new(
            EndKind::CertificateRejected,
            "certificate not trusted",
        ));
    }

    let upgraded = ironrdp_tokio::mark_as_upgraded(should_upgrade, &mut connector);
    let erased: Box<dyn AsyncStream + Unpin + Send + Sync> = Box::new(tls);
    let mut framed = ironrdp_tokio::TokioFramed::new_with_leftover(erased, leftover);
    let server_public_key = tls::public_key(&certificate).ok_or_else(|| {
        Failure::new(
            EndKind::Error,
            "the server certificate has no usable public key",
        )
    })?;
    let result = ironrdp_tokio::connect_finalize(
        upgraded,
        connector,
        &mut framed,
        &mut NoNetwork,
        ironrdp_connector::ServerName::new(config.server_name.clone()),
        server_public_key,
        None,
    )
    .await?;
    Ok((result, framed))
}

fn set_image(shared: &Shared, width: u16, height: u16) {
    let mut frame = shared.frame.lock().unwrap_or_else(|e| e.into_inner());
    frame.image = Some(DecodedImage::new(PixelFormat::RgbA32, width, height));
    frame.dirty = None;
    drop(frame);
    shared.push(Event::Desktop { width, height });
}

fn operations(
    db: &mut Database,
    input: Input,
) -> Option<smallvec::SmallVec<[ironrdp_pdu::input::fast_path::FastPathInputEvent; 2]>> {
    let op = match input {
        Input::MouseMove { x, y } => Operation::MouseMove(MousePosition { x, y }),
        Input::MouseButton { button, down } => {
            let button = MouseButton::from_idx(usize::from(button))?;
            if down {
                Operation::MouseButtonPressed(button)
            } else {
                Operation::MouseButtonReleased(button)
            }
        }
        Input::Wheel { vertical, units } => Operation::WheelRotations(WheelRotations {
            is_vertical: vertical,
            rotation_units: units,
        }),
        Input::Key { scancode, down } => {
            let code = Scancode::from_u16(scancode);
            if down {
                Operation::KeyPressed(code)
            } else {
                Operation::KeyReleased(code)
            }
        }
        Input::Unicode { ch, down } => {
            if down {
                Operation::UnicodeKeyPressed(ch)
            } else {
                Operation::UnicodeKeyReleased(ch)
            }
        }
        Input::ReleaseAll => return Some(db.release_all()),
        Input::Resize { .. } | Input::Close => return None,
    };
    Some(db.apply([op]))
}

async fn active_session(
    result: ConnectionResult,
    framed: Upgraded,
    shared: &Arc<Shared>,
    input: &mut mpsc::UnboundedReceiver<Input>,
) -> Result<(), Failure> {
    let (mut reader, mut writer) = split_tokio_framed(framed);
    set_image(
        shared,
        result.desktop_size.width,
        result.desktop_size.height,
    );
    let activation_factory = result.activation_factory;
    let mut stage = ActiveStageBuilder {
        static_channels: result.static_channels,
        user_channel_id: result.user_channel_id,
        io_channel_id: result.io_channel_id,
        message_channel_id: result.message_channel_id,
        share_id: result.share_id,
        compression_type: result.compression_type,
        enable_server_pointer: result.enable_server_pointer,
        pointer_software_rendering: result.pointer_software_rendering,
    }
    .build();
    let mut db = Database::new();
    let mut closing = false;

    loop {
        let outputs = tokio::select! {
            frame = reader.read_pdu() => {
                let (action, payload) = match frame {
                    Ok(frame) => frame,
                    Err(e) if closing => { let _ = e; return Ok(()); }
                    Err(e) => return Err(Failure::new(
                        if e.kind() == std::io::ErrorKind::UnexpectedEof { EndKind::Transport } else { EndKind::Error },
                        describe(&e),
                    )),
                };
                let mut frame = shared.frame.lock().unwrap_or_else(|e| e.into_inner());
                let image = frame.image.as_mut().expect("set before the loop");
                match stage.process(image, action, &payload) {
                    Ok(outputs) => outputs,
                    // Servers end the connection in many ways once asked to.
                    Err(_) if closing => return Ok(()),
                    Err(e) => return Err(e.into()),
                }
            }
            event = input.recv() => {
                let Some(event) = event else { return Ok(()) };
                match event {
                    Input::Close => {
                        closing = true;
                        stage.graceful_shutdown()?
                    }
                    Input::Resize { width, height } => {
                        let (width, height) = MonitorLayoutEntry::adjust_display_size(u32::from(width), u32::from(height));
                        match stage.encode_resize(width, height, None, None) {
                            Some(frame) => vec![ActiveStageOutput::ResponseFrame(frame?)],
                            None => Vec::new(),
                        }
                    }
                    other => match operations(&mut db, other) {
                        Some(events) if !events.is_empty() => {
                            let mut frame = shared.frame.lock().unwrap_or_else(|e| e.into_inner());
                            let image = frame.image.as_mut().expect("set before the loop");
                            stage.process_fastpath_input(image, &events)?
                        }
                        _ => Vec::new(),
                    },
                }
            }
        };

        for output in outputs {
            match output {
                ActiveStageOutput::ResponseFrame(frame) => writer
                    .write_all(&frame)
                    .await
                    .map_err(|e| Failure::new(EndKind::Transport, describe(&e)))?,
                ActiveStageOutput::GraphicsUpdate(region) => {
                    let mut frame = shared.frame.lock().unwrap_or_else(|e| e.into_inner());
                    frame.dirty = Some(match frame.dirty.take() {
                        None => Dirty {
                            left: region.left,
                            top: region.top,
                            right: region.right,
                            bottom: region.bottom,
                        },
                        Some(d) => Dirty {
                            left: d.left.min(region.left),
                            top: d.top.min(region.top),
                            right: d.right.max(region.right),
                            bottom: d.bottom.max(region.bottom),
                        },
                    });
                    drop(frame);
                    shared.frame_changed();
                }
                ActiveStageOutput::DeactivateAll => {
                    // Deactivation-Reactivation Sequence: the server may change the desktop size.
                    let mut activation = activation_factory.create();
                    let mut buf = WriteBuf::new();
                    loop {
                        let written =
                            single_sequence_step_read(&mut reader, &mut activation, &mut buf)
                                .await?;
                        if written.size().is_some() {
                            writer
                                .write_all(buf.filled())
                                .await
                                .map_err(|e| Failure::new(EndKind::Transport, describe(&e)))?;
                        }
                        if let ConnectionActivationState::Finalized {
                            desktop_size,
                            share_id,
                            enable_server_pointer,
                            pointer_software_rendering,
                        } = activation.connection_activation_state()
                        {
                            set_image(shared, desktop_size.width, desktop_size.height);
                            stage.set_fastpath_processor(
                                fast_path::ProcessorBuilder {
                                    io_channel_id: activation.io_channel_id(),
                                    user_channel_id: activation.user_channel_id(),
                                    share_id,
                                    enable_server_pointer,
                                    pointer_software_rendering,
                                    bulk_decompressor: None,
                                }
                                .build(),
                            );
                            stage.set_share_id(share_id);
                            stage.set_enable_server_pointer(enable_server_pointer);
                            break;
                        }
                    }
                }
                ActiveStageOutput::Terminate(reason) => {
                    return match reason {
                        ironrdp_session::GracefulDisconnectReason::UserInitiated => Ok(()),
                        other if closing => {
                            let _ = other;
                            Ok(())
                        }
                        other => Err(Failure::new(EndKind::Server, other.description())),
                    };
                }
                ActiveStageOutput::PointerDefault
                | ActiveStageOutput::PointerHidden
                | ActiveStageOutput::PointerPosition { .. }
                | ActiveStageOutput::PointerBitmap(_)
                | ActiveStageOutput::MultitransportRequest(_)
                | ActiveStageOutput::AutoDetect(_) => {}
            }
        }
    }
}

mod tls {
    use std::sync::Arc;

    use sha2::{Digest as _, Sha256};
    use tokio_rustls::rustls;
    use tokio_rustls::rustls::client::danger::{
        HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier,
    };
    use tokio_rustls::rustls::crypto::{CryptoProvider, WebPkiSupportedAlgorithms};
    use tokio_rustls::rustls::pki_types::{CertificateDer, ServerName, UnixTime};
    use tokio_rustls::rustls::{DigitallySignedStruct, SignatureScheme};
    use x509_cert::der::Decode as _;

    /// RDP servers present self-signed certificates: no chain is checked here.
    /// The handshake signatures still are, so whoever answers holds the key of
    /// the certificate the user trusts afterwards by its fingerprint.
    #[derive(Debug)]
    struct TrustLater {
        algorithms: WebPkiSupportedAlgorithms,
    }

    impl ServerCertVerifier for TrustLater {
        fn verify_server_cert(
            &self,
            _: &CertificateDer<'_>,
            _: &[CertificateDer<'_>],
            _: &ServerName<'_>,
            _: &[u8],
            _: UnixTime,
        ) -> Result<ServerCertVerified, rustls::Error> {
            Ok(ServerCertVerified::assertion())
        }

        fn verify_tls12_signature(
            &self,
            message: &[u8],
            cert: &CertificateDer<'_>,
            dss: &DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, rustls::Error> {
            rustls::crypto::verify_tls12_signature(message, cert, dss, &self.algorithms)
        }

        fn verify_tls13_signature(
            &self,
            message: &[u8],
            cert: &CertificateDer<'_>,
            dss: &DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, rustls::Error> {
            rustls::crypto::verify_tls13_signature(message, cert, dss, &self.algorithms)
        }

        fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
            self.algorithms.supported_schemes()
        }
    }

    pub(super) async fn upgrade<S>(
        stream: S,
        server_name: &str,
    ) -> std::io::Result<(tokio_rustls::client::TlsStream<S>, Vec<u8>)>
    where
        S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
    {
        let provider: Arc<CryptoProvider> = Arc::new(rustls::crypto::ring::default_provider());
        let verifier = TrustLater {
            algorithms: provider.signature_verification_algorithms,
        };
        let mut config = rustls::ClientConfig::builder_with_provider(Arc::clone(&provider))
            .with_safe_default_protocol_versions()
            .map_err(std::io::Error::other)?
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(verifier))
            .with_no_client_auth();
        // CredSSP does not support TLS session resumption.
        config.resumption = rustls::client::Resumption::disabled();
        let name = ServerName::try_from(server_name.to_owned())
            .unwrap_or_else(|_| ServerName::try_from("localhost".to_owned()).expect("valid name"));
        let tls = tokio_rustls::TlsConnector::from(Arc::new(config))
            .connect(name, stream)
            .await?;
        let certificate = tls
            .get_ref()
            .1
            .peer_certificates()
            .and_then(|chain| chain.first())
            .map(|cert| cert.as_ref().to_vec())
            .ok_or_else(|| std::io::Error::other("the server sent no certificate"))?;
        Ok((tls, certificate))
    }

    /// SHA-256 of the certificate (lowercase hex) and its subject.
    pub(super) fn fingerprint(der: &[u8]) -> (String, String) {
        let digest = Sha256::digest(der);
        let hex = digest
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect::<String>();
        let subject = x509_cert::Certificate::from_der(der)
            .map(|cert| cert.tbs_certificate.subject.to_string())
            .unwrap_or_default();
        (hex, subject)
    }

    /// The certificate's public key, as CredSSP binds the credentials to it.
    pub(super) fn public_key(der: &[u8]) -> Option<Vec<u8>> {
        let cert = x509_cert::Certificate::from_der(der).ok()?;
        Some(
            cert.tbs_certificate
                .subject_public_key_info
                .subject_public_key
                .as_bytes()?
                .to_vec(),
        )
    }
}
