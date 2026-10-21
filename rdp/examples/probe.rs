//! Development probe of the engine, without the app: connects to an RDP server
//! over plain TCP (e.g. the far end of `ssh -L`), trusts its certificate,
//! waits a few seconds and saves the desktop as a PPM image.
//!
//! ```shell
//! cargo run --example probe -- <host> <port> <user> <password> <out.ppm> [--no-tls-login]
//! ```
//! The password can also come from the TITAN_RDP_PASSWORD environment variable
//! (pass `-` as the password).

use std::io::{Read as _, Write as _};
use std::net::TcpStream;
use std::sync::Arc;
use std::time::{Duration, Instant};

use titan_rdp::engine::{CopyResult, Event, Input, RdpConfig, RdpSession};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() < 5 {
        eprintln!("usage: probe <host> <port> <user> <password|-> <out.ppm> [--no-tls-login]");
        std::process::exit(2);
    }
    let password = if args[3] == "-" {
        std::env::var("TITAN_RDP_PASSWORD").unwrap_or_default()
    } else {
        args[3].clone()
    };
    let config = RdpConfig {
        username: args[2].clone(),
        password,
        domain: None,
        server_name: args[0].clone(),
        width: 1280,
        height: 800,
        allow_tls_without_nla: !args.iter().any(|a| a == "--no-tls-login"),
        client_name: "titan-probe".to_owned(),
    };
    let stream = TcpStream::connect((args[0].as_str(), args[1].parse::<u16>().expect("port")))
        .expect("connect");
    let session = Arc::new(RdpSession::start(config).expect("start"));

    let mut inbound = stream.try_clone().expect("clone");
    let feeder = Arc::clone(&session);
    std::thread::spawn(move || {
        let mut buf = [0u8; 16384];
        loop {
            match inbound.read(&mut buf) {
                Ok(0) | Err(_) => {
                    feeder.feed_eof();
                    break;
                }
                Ok(n) => {
                    if !feeder.feed(&buf[..n]) {
                        break;
                    }
                }
            }
        }
    });
    let mut outbound = stream;
    let drainer = Arc::clone(&session);
    std::thread::spawn(move || {
        let mut buf = [0u8; 16384];
        while let Some(n) = drainer.drain(&mut buf) {
            if outbound.write_all(&buf[..n]).is_err() {
                break;
            }
        }
        let _ = outbound.shutdown(std::net::Shutdown::Write);
    });

    let mut frame = Vec::new();
    let (mut width, mut height) = (0u16, 0u16);
    let mut connected_at: Option<Instant> = None;
    let deadline = Instant::now() + Duration::from_secs(40);
    while Instant::now() < deadline {
        if let Some(at) = connected_at
            && at.elapsed() > Duration::from_secs(12)
        {
            session.send(Input::Close);
            connected_at = None;
        }
        match session.next_event(Duration::from_millis(200)) {
            Some(Event::Certificate { sha256, subject }) => {
                println!("certificate {subject} sha256={sha256}");
                session.answer_certificate(true);
            }
            Some(Event::Desktop {
                width: w,
                height: h,
            }) => {
                println!("desktop {w}x{h}");
                width = w;
                height = h;
                frame = vec![0u8; usize::from(w) * usize::from(h) * 4];
                connected_at.get_or_insert_with(Instant::now);
                // A click in the middle and some typing, to see input arrive.
                session.send(Input::MouseMove { x: w / 2, y: h / 2 });
            }
            Some(Event::Frame) => match session.copy_frame(&mut frame, width, height, false) {
                CopyResult::Copied(rect) => println!("frame {rect:?}"),
                other => println!("copy {other:?}"),
            },
            Some(Event::Ended { kind, message }) => {
                println!("ended {} {message}", kind.code());
                break;
            }
            None => {}
        }
    }
    if width > 0 {
        let mut out = std::fs::File::create(&args[4]).expect("create output");
        write!(out, "P6\n{width} {height}\n255\n").expect("write");
        let rgb: Vec<u8> = frame
            .as_chunks::<4>()
            .0
            .iter()
            .flat_map(|p| [p[0], p[1], p[2]])
            .collect();
        out.write_all(&rgb).expect("write");
        println!("saved {}", args[4]);
    }
}
