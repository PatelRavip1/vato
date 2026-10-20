use base64::Engine;
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::fs::File;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::process::Command;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const DEFAULT_PORT: u16 = 8080;

fn get_port() -> u16 {
    std::env::var("PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(DEFAULT_PORT)
}

fn get_omp_bin() -> String {
    std::env::var("OMP_BIN").unwrap_or_else(|_| "omp".to_string())
}

fn get_apk_path() -> Option<std::path::PathBuf> {
    if let Ok(p) = std::env::var("VATO_APK_PATH") {
        let path = std::path::PathBuf::from(p);
        if path.exists() {
            return Some(path);
        }
    }
    for candidate in &["vato.apk", "../vato.apk", "build/vato.apk"] {
        let path = std::path::PathBuf::from(candidate);
        if path.exists() {
            return Some(path);
        }
    }
    None
}

#[derive(Serialize)]
struct VersionInfo {
    #[serde(rename = "versionCode")]
    version_code: u32,
    #[serde(rename = "versionName")]
    version_name: &'static str,
}

#[derive(Serialize)]
struct ModelItem {
    id: &'static str,
    object: &'static str,
    owned_by: &'static str,
    name: &'static str,
}

#[derive(Serialize)]
struct ModelsList {
    object: &'static str,
    data: Vec<ModelItem>,
}

#[derive(Deserialize, Debug)]
struct ChatCompletionRequest {
    #[serde(default)]
    messages: Vec<ChatMessage>,
}

#[derive(Deserialize, Debug)]
struct ChatMessage {
    role: Option<String>,
    content: Option<serde_json::Value>,
}

#[derive(Serialize)]
struct ChatCompletionResponse {
    id: String,
    object: &'static str,
    created: u64,
    model: &'static str,
    choices: Vec<ChatChoice>,
}

#[derive(Serialize)]
struct ChatChoice {
    index: usize,
    message: ChatResponseMessage,
    finish_reason: &'static str,
}

#[derive(Serialize)]
struct ChatResponseMessage {
    role: &'static str,
    content: String,
}

struct AppState {
    ansi_re: Regex,
    think_re: Regex,
    think_tag_re: Regex,
    working_re: Regex,
}

impl AppState {
    fn new() -> Self {
        Self {
            ansi_re: Regex::new(r"\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])").unwrap(),
            think_re: Regex::new(r"(?s)<think>.*?</think>").unwrap(),
            think_tag_re: Regex::new(r"</?think>").unwrap(),
            working_re: Regex::new(r"^\s*Working\.\.\.\s*").unwrap(),
        }
    }

    fn clean_omp_output(&self, raw: &str) -> String {
        if raw.is_empty() {
            return String::new();
        }
        let no_ansi = self.ansi_re.replace_all(raw, "");
        let no_working = self.working_re.replace(&no_ansi, "");
        let no_think = self.think_re.replace_all(&no_working, "");
        let cleaned = self.think_tag_re.replace_all(&no_think, "");
        cleaned.trim().to_string()
    }
}

fn get_local_ipv4_addrs() -> Vec<Ipv4Addr> {
    let mut addrs = Vec::new();

    unsafe {
        let mut ifaddrs: *mut libc::ifaddrs = std::ptr::null_mut();
        if libc::getifaddrs(&mut ifaddrs) == 0 {
            let mut curr = ifaddrs;
            while !curr.is_null() {
                let ifa = &*curr;
                if !ifa.ifa_addr.is_null() && (*ifa.ifa_addr).sa_family as i32 == libc::AF_INET {
                    let sin = ifa.ifa_addr as *const libc::sockaddr_in;
                    let ip_u32 = u32::from_be((*sin).sin_addr.s_addr);
                    let ipv4 = Ipv4Addr::from(ip_u32);
                    if !ipv4.is_loopback() && !addrs.contains(&ipv4) {
                        addrs.push(ipv4);
                    }
                }
                curr = ifa.ifa_next;
            }
            libc::freeifaddrs(ifaddrs);
        }
    }

    let loopback = Ipv4Addr::new(127, 0, 0, 1);
    if !addrs.contains(&loopback) {
        addrs.push(loopback);
    }

    addrs
}

fn handle_connection(mut stream: TcpStream, state: Arc<AppState>) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(60)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(60)));
    let stream_clone = match stream.try_clone() {
        Ok(c) => c,
        Err(_) => return,
    };
    let mut reader = BufReader::new(stream_clone);
    let mut request_line = String::new();
    if reader.read_line(&mut request_line).is_err() || request_line.is_empty() {
        return;
    }

    let parts: Vec<&str> = request_line.trim().split_whitespace().collect();
    if parts.len() < 2 {
        return;
    }

    let method = parts[0];
    let path = parts[1];

    let mut headers = Vec::new();
    let mut content_length: usize = 0;

    loop {
        let mut line = String::new();
        if reader.read_line(&mut line).is_err() || line.trim().is_empty() {
            break;
        }
        let lower = line.to_lowercase();
        if lower.starts_with("content-length:") {
            if let Some(val_str) = line.split(':').nth(1) {
                if let Ok(val) = val_str.trim().parse::<usize>() {
                    content_length = val;
                }
            }
        }
        headers.push(line);
    }

    const MAX_BODY_SIZE: usize = 20 * 1024 * 1024; // 20 MB max
    if content_length > MAX_BODY_SIZE {
        send_response(&mut stream, 413, "application/json", b"{\"error\":\"Payload Too Large\"}");
        return;
    }

    let mut body = vec![0u8; content_length];
    if content_length > 0 {
        if reader.read_exact(&mut body).is_err() {
            return;
        }
    }

    match method {
        "OPTIONS" => {
            let resp = "HTTP/1.1 200 OK\r\n\
Access-Control-Allow-Origin: *\r\n\
Access-Control-Allow-Methods: GET, POST, OPTIONS, HEAD\r\n\
Access-Control-Allow-Headers: Content-Type, Authorization\r\n\
Content-Length: 0\r\n\
Connection: close\r\n\r\n";
            let _ = stream.write_all(resp.as_bytes());
        }
        "HEAD" | "GET" => {
            handle_get(&mut stream, path, method == "HEAD");
        }
        "POST" => {
            if path.starts_with("/v1/chat/completions") {
                handle_chat_completions(&mut stream, &body, &state);
            } else {
                send_response(&mut stream, 404, "text/plain", b"Not Found");
            }
        }
        _ => {
            send_response(&mut stream, 405, "text/plain", b"Method Not Allowed");
        }
    }
}

fn handle_get(stream: &mut TcpStream, path: &str, is_head: bool) {
    if path == "/vato.apk" || path == "/download" || path == "/update" {
        let apk_path = match get_apk_path() {
            Some(p) => p,
            None => {
                send_response(stream, 404, "text/plain", b"APK Not Found");
                return;
            }
        };

        if let Ok(metadata) = std::fs::metadata(&apk_path) {
            let size = metadata.len();
            let headers = format!(
                "HTTP/1.1 200 OK\r\n\
Content-Type: application/vnd.android.package-archive\r\n\
Content-Disposition: attachment; filename=\"vato.apk\"\r\n\
Access-Control-Allow-Origin: *\r\n\
Content-Length: {}\r\n\
Connection: close\r\n\r\n",
                size
            );
            let _ = stream.write_all(headers.as_bytes());
            if !is_head {
                if let Ok(mut file) = File::open(&apk_path) {
                    let mut buffer = [0u8; 64 * 1024];
                    while let Ok(n) = file.read(&mut buffer) {
                        if n == 0 {
                            break;
                        }
                        if stream.write_all(&buffer[..n]).is_err() {
                            break;
                        }
                    }
                }
            }
        } else {
            send_response(stream, 404, "text/plain", b"File Metadata Error");
        }
        return;
    }

    if path == "/version" {
        let version_info = VersionInfo {
            version_code: 4,
            version_name: "1.3.0",
        };
        let json_bytes = serde_json::to_vec(&version_info).unwrap_or_default();
        if is_head {
            send_head_response(stream, 200, "application/json", json_bytes.len());
        } else {
            send_response(stream, 200, "application/json", &json_bytes);
        }
        return;
    }

    if path == "/v1/models" {
        let models = ModelsList {
            object: "list",
            data: vec![ModelItem {
                id: "unqwen",
                object: "model",
                owned_by: "local-llamacpp",
                name: "Unqwen (Local Qwen3.8-Flash-Next Uncensored via OMP)",
            }],
        };
        let json_bytes = serde_json::to_vec(&models).unwrap_or_default();
        if is_head {
            send_head_response(stream, 200, "application/json", json_bytes.len());
        } else {
            send_response(stream, 200, "application/json", &json_bytes);
        }
        return;
    }

    send_response(stream, 404, "text/plain", b"Not Found");
}

fn handle_chat_completions(stream: &mut TcpStream, body: &[u8], state: &AppState) {
    let req: ChatCompletionRequest = match serde_json::from_slice(body) {
        Ok(r) => r,
        Err(e) => {
            eprintln!("[-] Error parsing chat request JSON: {}", e);
            send_response(stream, 400, "application/json", b"{\"error\":\"Invalid JSON\"}");
            return;
        }
    };

    let mut history_lines = Vec::new();
    let mut last_user_text = String::new();
    let mut temp_image_path: Option<String> = None;
    let mut custom_system_prompt: Option<String> = None;

    let total_msgs = req.messages.len();
    for (i, msg) in req.messages.into_iter().enumerate() {
        let is_last = i == total_msgs.saturating_sub(1);
        let role = msg.role.as_deref().unwrap_or("user");

        if role == "system" {
            if let Some(serde_json::Value::String(s)) = msg.content {
                custom_system_prompt = Some(s);
            }
            continue;
        }

        if !is_last {
            let role_name = if role == "user" { "User" } else { "Vato" };
            if let Some(val) = msg.content {
                let text = extract_text_from_value(&val);
                if !text.is_empty() {
                    history_lines.push(format!("{}: {}", role_name, text));
                }
            }
        } else {
            // Last message
            if let Some(val) = msg.content {
                match val {
                    serde_json::Value::String(s) => {
                        last_user_text = s.trim().to_string();
                    }
                    serde_json::Value::Array(arr) => {
                        for part in arr {
                            if let Some(obj) = part.as_object() {
                                let part_type = obj.get("type").and_then(|v| v.as_str()).unwrap_or("");
                                if part_type == "text" {
                                    if let Some(t) = obj.get("text").and_then(|v| v.as_str()) {
                                        last_user_text = t.trim().to_string();
                                    }
                                } else if part_type == "image_url" {
                                    if let Some(img_obj) = obj.get("image_url").and_then(|v| v.as_object()) {
                                        if let Some(url) = img_obj.get("url").and_then(|v| v.as_str()) {
                                            if let Some(b64) = url.split("base64,").nth(1) {
                                                if let Ok(decoded) = base64::engine::general_purpose::STANDARD.decode(b64) {
                                                    let temp_filename = format!("/tmp/vato_{}.jpg", &uuid_v4_short());
                                                    if std::fs::write(&temp_filename, &decoded).is_ok() {
                                                        temp_image_path = Some(temp_filename);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    _ => {}
                }
            }
        }
    }

    let default_system = "You are Vato, an AI assistant on WhatsApp with full live internet search and system tools.\nBe fast and direct. Limit to 1-2 web search lookups if searching. Keep your reply short, direct, and concise like a WhatsApp message. No filler or corporate fluff.";
    let system_instructions = custom_system_prompt.as_deref().unwrap_or(default_system);

    let mut prompt_parts = Vec::new();
    prompt_parts.push(system_instructions.to_string());

    if !history_lines.is_empty() {
        let start = history_lines.len().saturating_sub(6);
        let recent = history_lines[start..].join("\n");
        prompt_parts.push(format!("\nRecent Conversation:\n{}", recent));
    }

    let user_msg = if !last_user_text.is_empty() {
        last_user_text.as_str()
    } else {
        "Describe this image."
    };
    prompt_parts.push(format!("\nUser: {}", user_msg));

    let full_prompt = prompt_parts.join("\n");

    let omp_bin = get_omp_bin();
    let model = std::env::var("VATO_MODEL").unwrap_or_else(|_| "unqwen".to_string());
    println!("[*] Dispatching query to OMP ({}): '{}'", model, user_msg);

    let mut cmd = Command::new(&omp_bin);
    cmd.arg("-p")
        .arg("--model").arg(&model);
    if let Ok(ext) = std::env::var("OMP_EXTENSION") {
        cmd.arg("-e").arg(ext);
    }
    if let Some(img_path) = &temp_image_path {
        cmd.arg(format!("@{}", img_path));
    }
    cmd.arg(&full_prompt);

    let output = match cmd.output() {
        Ok(out) => out,
        Err(e) => {
            eprintln!("[-] Failed to execute OMP: {}", e);
            let err_reply = format!("Error running model: {}", e);
            send_chat_response(stream, &err_reply);
            if let Some(img_path) = &temp_image_path {
                let _ = std::fs::remove_file(img_path);
            }
            return;
        }
    };

    let stdout_str = String::from_utf8_lossy(&output.stdout);
    let stderr_str = String::from_utf8_lossy(&output.stderr);

    let mut clean_reply = state.clean_omp_output(&stdout_str);
    if clean_reply.is_empty() && !stderr_str.is_empty() {
        clean_reply = state.clean_omp_output(&stderr_str);
    }
    if clean_reply.is_empty() {
        clean_reply = "Done.".to_string();
    }

    if let Some(img_path) = &temp_image_path {
        let _ = std::fs::remove_file(img_path);
    }

    send_chat_response(stream, &clean_reply);
}

fn extract_text_from_value(val: &serde_json::Value) -> String {
    match val {
        serde_json::Value::String(s) => s.trim().to_string(),
        serde_json::Value::Array(arr) => {
            let mut parts = Vec::new();
            for part in arr {
                if let Some(obj) = part.as_object() {
                    if obj.get("type").and_then(|v| v.as_str()) == Some("text") {
                        if let Some(t) = obj.get("text").and_then(|v| v.as_str()) {
                            parts.push(t.trim());
                        }
                    }
                }
            }
            parts.join(" ")
        }
        _ => String::new(),
    }
}

fn uuid_v4_short() -> String {
    use std::time::SystemTime;
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    format!("{:012x}", nanos & 0xFFFF_FFFF_FFFF)
}

fn send_chat_response(stream: &mut TcpStream, reply: &str) {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();

    let resp_obj = ChatCompletionResponse {
        id: format!("chatcmpl-{}", uuid_v4_short()),
        object: "chat.completion",
        created: now,
        model: "unqwen",
        choices: vec![ChatChoice {
            index: 0,
            message: ChatResponseMessage {
                role: "assistant",
                content: reply.to_string(),
            },
            finish_reason: "stop",
        }],
    };

    let json_bytes = serde_json::to_vec(&resp_obj).unwrap_or_default();
    send_response(stream, 200, "application/json", &json_bytes);
}

fn send_head_response(stream: &mut TcpStream, status: u16, content_type: &str, length: usize) {
    let status_line = match status {
        200 => "200 OK",
        404 => "404 Not Found",
        405 => "405 Method Not Allowed",
        _ => "200 OK",
    };
    let headers = format!(
        "HTTP/1.1 {}\r\n\
Content-Type: {}\r\n\
Access-Control-Allow-Origin: *\r\n\
Access-Control-Allow-Methods: GET, POST, OPTIONS, HEAD\r\n\
Access-Control-Allow-Headers: Content-Type, Authorization\r\n\
Content-Length: {}\r\n\
Connection: close\r\n\r\n",
        status_line, content_type, length
    );
    let _ = stream.write_all(headers.as_bytes());
}

fn send_response(stream: &mut TcpStream, status: u16, content_type: &str, body: &[u8]) {
    let status_line = match status {
        200 => "200 OK",
        400 => "400 Bad Request",
        404 => "404 Not Found",
        405 => "405 Method Not Allowed",
        413 => "413 Payload Too Large",
        500 => "500 Internal Server Error",
        _ => "200 OK",
    };
    let headers = format!(
        "HTTP/1.1 {}\r\n\
Content-Type: {}\r\n\
Access-Control-Allow-Origin: *\r\n\
Access-Control-Allow-Methods: GET, POST, OPTIONS, HEAD\r\n\
Access-Control-Allow-Headers: Content-Type, Authorization\r\n\
Content-Length: {}\r\n\
Connection: close\r\n\r\n",
        status_line, content_type, body.len()
    );
    let _ = stream.write_all(headers.as_bytes());
    let _ = stream.write_all(body);
}

fn main() {
    let state = Arc::new(AppState::new());
    let addrs = get_local_ipv4_addrs();

    let port = get_port();
    println!("[*] Starting Vato Rust Proxy on port {}...", port);

    let mut listeners = Vec::new();
    for ip in &addrs {
        let sock_addr = SocketAddr::from((*ip, port));
        match TcpListener::bind(sock_addr) {
            Ok(listener) => {
                println!("[+] Bound listener to http://{}", sock_addr);
                listeners.push(listener);
            }
            Err(e) => {
                eprintln!("[-] Could not bind to {}: {}", sock_addr, e);
            }
        }
    }

    if listeners.is_empty() {
        eprintln!("[!] FATAL: Could not bind to any interface on port {}", port);
        std::process::exit(1);
    }

    let mut handles = Vec::new();
    for listener in listeners {
        let state_clone = Arc::clone(&state);
        let handle = std::thread::spawn(move || {
            for stream_res in listener.incoming() {
                match stream_res {
                    Ok(stream) => {
                        let st = Arc::clone(&state_clone);
                        std::thread::spawn(move || {
                            handle_connection(stream, st);
                        });
                    }
                    Err(e) => {
                        eprintln!("[-] Accept error: {}", e);
                    }
                }
            }
        });
        handles.push(handle);
    }

    println!("[+] Vato Rust Proxy is ready and listening.");
    for h in handles {
        let _ = h.join();
    }
}
