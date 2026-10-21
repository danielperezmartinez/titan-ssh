//! titan-ssh's embedded RDP client (ADR-0028): the [engine] and the JNI
//! functions of `io.github.danielperezmartinez.titanssh.rdp.RdpNative`.
//!
//! The JNI surface is deliberately small and one-way: Kotlin calls in, Rust
//! never calls back. Sessions are numbered handles in a registry, so a stale or
//! repeated handle is a no-op instead of undefined behaviour, and every entry
//! point catches panics so that none reaches the JVM.

pub mod engine;

use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use jni::JNIEnv;
use jni::objects::{JByteArray, JObject, JString, ReleaseMode};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jint, jlong, jstring};

use engine::{CopyResult, Event, Input, RdpConfig, RdpSession};

fn sessions() -> &'static Mutex<HashMap<jlong, Arc<RdpSession>>> {
    static SESSIONS: OnceLock<Mutex<HashMap<jlong, Arc<RdpSession>>>> = OnceLock::new();
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

fn session(handle: jlong) -> Option<Arc<RdpSession>> {
    sessions()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&handle)
        .cloned()
}

/// Runs [f], turning a panic into [fallback].
fn guarded<T>(fallback: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(fallback)
}

fn string(env: &mut JNIEnv, value: &JString) -> String {
    if value.is_null() {
        return String::new();
    }
    env.get_string(value).map(Into::into).unwrap_or_default()
}

/// Starts a session and returns its handle, or 0 if it could not start.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_danielperezmartinez_titanssh_rdp_RdpNative_start(
    mut env: JNIEnv,
    _this: JObject,
    username: JString,
    password: JByteArray,
    domain: JString,
    server_name: JString,
    width: jint,
    height: jint,
    allow_tls_login: jboolean,
    client_name: JString,
) -> jlong {
    guarded(0, || {
        let password = env
            .convert_byte_array(&password)
            .ok()
            .and_then(|bytes| String::from_utf8(bytes).ok())
            .unwrap_or_default();
        let config = RdpConfig {
            username: string(&mut env, &username),
            password,
            domain: Some(string(&mut env, &domain)).filter(|d| !d.is_empty()),
            server_name: string(&mut env, &server_name),
            width: u16::try_from(width.clamp(200, 8192)).unwrap_or(1280),
            height: u16::try_from(height.clamp(200, 8192)).unwrap_or(800),
            allow_tls_without_nla: allow_tls_login == JNI_TRUE,
            client_name: string(&mut env, &client_name),
        };
        match RdpSession::start(config) {
            Ok(session) => {
                let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
                sessions()
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .insert(handle, Arc::new(session));
                handle
            }
            Err(_) => 0,
        }
    })
}

/// Hands the session [length] bytes of [data] from the server.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_danielperezmartinez_titanssh_rdp_RdpNative_feed(
    env: JNIEnv,
    _this: JObject,
    handle: jlong,
    data: JByteArray,
    length: jint,
) -> jboolean {
    guarded(JNI_FALSE, || {
        let Some(session) = session(handle) else {
            return JNI_FALSE;
        };
        let Ok(length) = usize::try_from(length) else {
            return JNI_FALSE;
        };
        let mut buf = vec![0i8; length];
        if env.get_byte_array_region(&data, 0, &mut buf).is_err() {
            return JNI_FALSE;
        }
        // SAFETY: i8 and u8 have the same size and alignment.
        let bytes = unsafe { std::slice::from_raw_parts(buf.as_ptr().cast::<u8>(), buf.len()) };
        if session.feed(bytes) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_danielperezmartinez_titanssh_rdp_RdpNative_feedEof(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
) {
    guarded((), || {
        if let Some(session) = session(handle) {
            session.feed_eof();
        }
    });
}

/// Waits for bytes for the server and copies them into [buffer]: how many,
/// or -1 once the session sends nothing more.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_danielperezmartinez_titanssh_rdp_RdpNative_drain(
    env: JNIEnv,
    _this: JObject,
    handle: jlong,
    buffer: JByteArray,
) -> jint {
    guarded(-1, || {
        let Some(session) = session(handle) else {
            return -1;
        };
        let Ok(capacity) = env.get_array_length(&buffer) else {
            return -1;
        };
        let mut buf = vec![0u8; usize::try_from(capacity).unwrap_or(0)];
        let Some(n) = session.drain(&mut buf) else {
            return -1;
        };
        // SAFETY: i8 and u8 have the same size and alignment.
        let signed = unsafe { std::slice::from_raw_parts(buf.as_ptr().cast::<i8>(), n) };
        if env.set_byte_array_region(&buffer, 0, signed).is_err() {
            return -1;
        }
        jint::try_from(n).unwrap_or(-1)
    })
}

/// The next event as tab-separated text, or null if none came in [timeout_millis].
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_danielperezmartinez_titanssh_rdp_RdpNative_nextEvent(
    env: JNIEnv,
    _this: JObject,
    handle: jlong,
    timeout_millis: jint,
) -> jstring {
    guarded(std::ptr::null_mut(), || {
        let text = match session(handle) {
            None => "ended\tclosed\t".to_owned(),
            Some(session) => {
                let timeout = Duration::from_millis(u64::try_from(timeout_millis).unwrap_or(0));
                match session.next_event(timeout) {
                    None => return std::ptr::null_mut(),
                    Some(Event::Certificate { sha256, subject }) => {
                        format!("certificate\t{sha256}\t{}", clean(&subject))
                    }
                    Some(Event::Desktop { width, height }) => format!("desktop\t{width}\t{height}"),
                    Some(Event::Frame) => "frame".to_owned(),
                    Some(Event::Ended { kind, message }) => {
                        format!("ended\t{}\t{}", kind.code(), clean(&message))
                    }
                }
            }
        };
        env.new_string(text)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut())
    })
}

fn clean(text: &str) -> String {
    text.replace(['\t', '\n', '\r'], " ")
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_danielperezmartinez_titanssh_rdp_RdpNative_answerCertificate(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
    accept: jboolean,
) {
    guarded((), || {
        if let Some(session) = session(handle) {
            session.answer_certificate(accept == JNI_TRUE);
        }
    });
}

/// Input kinds of `RdpNative.input`, mirrored in Kotlin.
const INPUT_MOUSE_MOVE: jint = 0;
const INPUT_MOUSE_BUTTON: jint = 1;
const INPUT_WHEEL: jint = 2;
const INPUT_KEY: jint = 3;
const INPUT_UNICODE: jint = 4;
const INPUT_RELEASE_ALL: jint = 5;
const INPUT_RESIZE: jint = 6;
const INPUT_CLOSE: jint = 7;

fn clamp_u16(value: jint) -> u16 {
    u16::try_from(value.clamp(0, i32::from(u16::MAX))).unwrap_or(0)
}

/// Sends one input to the session; what [a] and [b] mean depends on [kind].
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_danielperezmartinez_titanssh_rdp_RdpNative_input(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
    kind: jint,
    a: jint,
    b: jint,
) {
    guarded((), || {
        let Some(session) = session(handle) else {
            return;
        };
        let input = match kind {
            INPUT_MOUSE_MOVE => Input::MouseMove {
                x: clamp_u16(a),
                y: clamp_u16(b),
            },
            INPUT_MOUSE_BUTTON => Input::MouseButton {
                button: u8::try_from(a).unwrap_or(u8::MAX),
                down: b != 0,
            },
            INPUT_WHEEL => Input::Wheel {
                vertical: a != 0,
                units: i16::try_from(b.clamp(-255, 255)).unwrap_or(0),
            },
            INPUT_KEY => Input::Key {
                scancode: clamp_u16(a),
                down: b != 0,
            },
            INPUT_UNICODE => match u32::try_from(a).ok().and_then(char::from_u32) {
                Some(ch) => Input::Unicode { ch, down: b != 0 },
                None => return,
            },
            INPUT_RELEASE_ALL => Input::ReleaseAll,
            INPUT_RESIZE => Input::Resize {
                width: clamp_u16(a),
                height: clamp_u16(b),
            },
            INPUT_CLOSE => Input::Close,
            _ => return,
        };
        session.send(input);
    });
}

/// Copies what changed into [dst], a whole frame of [width] × [height]
/// pixels. Returns the copied rectangle packed as `x | y << 16 | w << 32 |
/// h << 48`, 0 if nothing changed, or -1 if the frame has another size.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_danielperezmartinez_titanssh_rdp_RdpNative_copyFrame(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    dst: JByteArray,
    width: jint,
    height: jint,
    bgra: jboolean,
) -> jlong {
    guarded(-1, || {
        let Some(session) = session(handle) else {
            return -1;
        };
        let (Ok(width), Ok(height)) = (u16::try_from(width), u16::try_from(height)) else {
            return -1;
        };
        // SAFETY: no other JNI call happens while the critical section is held.
        let Ok(mut elements) =
            (unsafe { env.get_array_elements_critical(&dst, ReleaseMode::CopyBack) })
        else {
            return -1;
        };
        // SAFETY: i8 and u8 have the same size and alignment.
        let bytes = unsafe {
            std::slice::from_raw_parts_mut(elements.as_mut_ptr().cast::<u8>(), elements.len())
        };
        match session.copy_frame(bytes, width, height, bgra == JNI_TRUE) {
            CopyResult::Unchanged => 0,
            CopyResult::WrongSize => -1,
            CopyResult::Copied(rect) => {
                jlong::from(rect.x)
                    | (jlong::from(rect.y) << 16)
                    | (jlong::from(rect.width) << 32)
                    | (jlong::from(rect.height) << 48)
            }
        }
    })
}

/// The desktop size packed as `width | height << 16`; 0 before it is known.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_danielperezmartinez_titanssh_rdp_RdpNative_frameSize(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
) -> jint {
    guarded(0, || {
        session(handle).map_or(0, |session| {
            let (w, h) = session.frame_size();
            jint::from(w) | (jint::from(h) << 16)
        })
    })
}

/// Stops the session and forgets its handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_danielperezmartinez_titanssh_rdp_RdpNative_free(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
) {
    guarded((), || {
        let removed = sessions()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&handle);
        // Threads still inside feed or drain hold the session; stopping the
        // engine lets them return, and the last one drops it.
        if let Some(session) = removed {
            session.shutdown();
        }
    });
}
