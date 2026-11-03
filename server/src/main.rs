use base64::Engine;
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::fs::File;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::process::Command;
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const DEFAULT_PORT: u16 = 8081;

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
    omp_lock: Arc<Mutex<()>>,
}

impl AppState {
    fn new() -> Self {
        Self {
            ansi_re: Regex::new(r"\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])").unwrap(),
            think_re: Regex::new(r"(?s)<think>.*?</think>").unwrap(),
            think_tag_re: Regex::new(r"</?think>").unwrap(),
            working_re: Regex::new(r"^\s*Working\.\.\.\s*").unwrap(),
            omp_lock: Arc::new(Mutex::new(())),
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
            } else if path.starts_with("/v1/audio/speech") {
                handle_tts(&mut stream, &body);
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
    let route = path.split_once('?').map(|(p, _)| p).unwrap_or(path);
    if route == "/vato.apk" || route == "/download" || route == "/update" {
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

    if route == "/version" {
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

    if route == "/v1/models" {
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
    if route.starts_with("/v1/audio/speech") {
        if is_head {
            send_head_response(stream, 200, "audio/mpeg", 0);
        } else {
            handle_tts_get(stream, path);
        }
        return;
    }

    send_response(stream, 404, "text/plain", b"Not Found");
}

#[derive(Deserialize, Debug)]
struct TtsRequest {
    input: Option<String>,
    text: Option<String>,
    voice: Option<String>,
}

fn url_decode(s: &str) -> String {
    let mut out = Vec::new();
    let bytes = s.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let Ok(val) = u8::from_str_radix(std::str::from_utf8(&bytes[i + 1..=i + 2]).unwrap_or("00"), 16) {
                out.push(val);
                i += 3;
                continue;
            }
        } else if bytes[i] == b'+' {
            out.push(b' ');
            i += 1;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).to_string()
}

fn generate_speech(text: &str, voice: Option<&str>) -> Result<Vec<u8>, String> {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return Err("Empty text".to_string());
    }
    let voice_name = voice.unwrap_or("en-GB-SoniaNeural");
    let edge_bin = if std::path::Path::new("/home/ravi/.local/bin/edge-tts").exists() {
        "/home/ravi/.local/bin/edge-tts"
    } else {
        "edge-tts"
    };

    let output = Command::new(edge_bin)
        .arg("--voice").arg(voice_name)
        .arg("--text").arg(trimmed)
        .output()
        .map_err(|e| format!("Failed to spawn edge-tts: {}", e))?;

    if !output.status.success() {
        let err_msg = String::from_utf8_lossy(&output.stderr);
        return Err(format!("edge-tts failed: {}", err_msg));
    }
    Ok(output.stdout)
}

fn handle_tts(stream: &mut TcpStream, body: &[u8]) {
    let req: TtsRequest = match serde_json::from_slice(body) {
        Ok(r) => r,
        Err(_) => {
            send_response(stream, 400, "application/json", b"{\"error\":\"Invalid JSON\"}");
            return;
        }
    };
    let text = req.input.or(req.text).unwrap_or_default();
    match generate_speech(&text, req.voice.as_deref()) {
        Ok(audio_bytes) => {
            send_response(stream, 200, "audio/mpeg", &audio_bytes);
        }
        Err(e) => {
            eprintln!("[-] TTS error: {}", e);
            send_response(stream, 500, "application/json", format!("{{\"error\":\"{}\"}}", e).as_bytes());
        }
    }
}

fn handle_tts_get(stream: &mut TcpStream, path: &str) {
    let mut text = String::new();
    let mut voice = None;
    if let Some(pos) = path.find('?') {
        let query = &path[pos + 1..];
        for pair in query.split('&') {
            let mut parts = pair.splitn(2, '=');
            if let (Some(k), Some(v)) = (parts.next(), parts.next()) {
                let decoded = url_decode(v);
                if k == "text" || k == "input" {
                    text = decoded;
                } else if k == "voice" {
                    voice = Some(decoded);
                }
            }
        }
    }
    match generate_speech(&text, voice.as_deref()) {
        Ok(audio_bytes) => {
            send_response(stream, 200, "audio/mpeg", &audio_bytes);
        }
        Err(e) => {
            eprintln!("[-] TTS error: {}", e);
            send_response(stream, 500, "application/json", format!("{{\"error\":\"{}\"}}", e).as_bytes());
        }
    }
}

fn sanitize_user_msg(msg: &str) -> String {
    if msg.starts_with('@') {
        format!(" {}", msg)
    } else {
        msg.to_string()
    }
}

struct TempFilesGuard(Vec<String>);

impl Drop for TempFilesGuard {
    fn drop(&mut self) {
        for path in &self.0 {
            let _ = std::fs::remove_file(path);
        }
    }
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
    let mut temp_images = TempFilesGuard(Vec::new());
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
                                                        temp_images.0.push(temp_filename);
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

    let default_system = "You are Vato, an AI assistant on WhatsApp with live internet search, system tools, and rich markdown rendering.\n- Language: Always reply in English by default. Only reply in Gujarati if the user speaks or asks in Gujarati.\n- Format replies using clean Markdown (headings, lists, code blocks, tables, bold).\n- When asked for photos, images, or visual references, search for direct image URLs (e.g. Wikimedia Commons, Wikipedia, direct JPG/PNG/WebP links) and embed them inline using markdown image syntax: ![description](image_url).\n- Keep replies direct, concise, and helpful like a WhatsApp message without fluff.";
    let system_instructions = custom_system_prompt.as_deref().unwrap_or(default_system);

    let mut prompt_parts = Vec::new();
    prompt_parts.push(system_instructions.to_string());
    if !system_instructions.contains("Always reply in English by default") {
        prompt_parts.push("\nImportant instruction: Always reply in English by default. Only reply in Gujarati if the user speaks or asks in Gujarati.".to_string());
    }
    if !system_instructions.contains("markdown image syntax") {
        prompt_parts.push("\nNote: The chat UI supports rendering images via Markdown syntax: ![description](image_url). When asked to show or find photos/images, search for direct image URLs and embed them directly.".to_string());
    }
    if !history_lines.is_empty() {
        let start = history_lines.len().saturating_sub(6);
        let recent = history_lines[start..].join("\n");
        prompt_parts.push(format!("\nRecent Conversation:\n{}", recent));
    }

    let raw_user_msg = if !last_user_text.is_empty() {
        last_user_text.as_str()
    } else {
        "Describe this image."
    };
    let user_msg = sanitize_user_msg(raw_user_msg);
    prompt_parts.push(format!("\nUser: {}", user_msg));

    let mut full_prompt = prompt_parts.join("\n");
    if full_prompt.starts_with('@') {
        full_prompt = format!(" {}", full_prompt);
    }

    let omp_bin = get_omp_bin();
    let model = std::env::var("VATO_MODEL").unwrap_or_else(|_| "unqwen".to_string());

    let mut cmd = Command::new(&omp_bin);
    cmd.arg("-p")
        .arg("--model").arg(&model);
    if let Ok(ext) = std::env::var("OMP_EXTENSION") {
        cmd.arg("-e").arg(ext);
    }
    for img_path in &temp_images.0 {
        cmd.arg(format!("@{}", img_path));
    }
    cmd.arg("--");
    cmd.arg(&full_prompt);

    let output = {
        let _guard = state.omp_lock.lock().unwrap_or_else(|e| e.into_inner());
        println!("[*] Dispatching query to OMP ({}): '{}'", model, user_msg);
        cmd.output()
    };

    let output = match output {
        Ok(out) => out,
        Err(e) => {
            eprintln!("[-] Failed to execute OMP: {}", e);
            let err_reply = format!("Error running model: {}", e);
            send_chat_response(stream, &err_reply);
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_path_query_routing() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();

        // Test /version?t=1
        let mut client = TcpStream::connect(addr).unwrap();
        let (mut server, _) = listener.accept().unwrap();
        handle_get(&mut server, "/version?t=1", false);
        drop(server);
        let mut response = String::new();
        client.read_to_string(&mut response).unwrap();
        assert!(response.starts_with("HTTP/1.1 200 OK"));
        assert!(response.contains("1.3.0"));

        // Test /download?v=1
        let mut client2 = TcpStream::connect(addr).unwrap();
        let (mut server2, _) = listener.accept().unwrap();
        handle_get(&mut server2, "/download?v=1", false);
        drop(server2);
        let mut response2_bytes = Vec::new();
        client2.read_to_end(&mut response2_bytes).unwrap();
        let response2 = String::from_utf8_lossy(&response2_bytes);
        assert!(response2.contains("200 OK") || response2.ends_with("\r\n\r\nAPK Not Found"));
        assert!(!response2.ends_with("\r\n\r\nNot Found"));

        // Test unmatched path
        let mut client3 = TcpStream::connect(addr).unwrap();
        let (mut server3, _) = listener.accept().unwrap();
        handle_get(&mut server3, "/nonexistent?foo=bar", false);
        drop(server3);
        let mut response3 = String::new();
        client3.read_to_string(&mut response3).unwrap();
        assert!(response3.contains("404 Not Found"));
    }

    #[test]
    fn test_user_msg_sanitization() {
        assert_eq!(sanitize_user_msg("@etc/passwd"), " @etc/passwd");
        assert_eq!(sanitize_user_msg("@image.png"), " @image.png");
        assert_eq!(sanitize_user_msg("hello @world"), "hello @world");
        assert_eq!(sanitize_user_msg("plain text"), "plain text");
        assert_eq!(sanitize_user_msg(""), "");
    }

    #[test]
    fn test_temp_files_guard_cleanup_on_drop() {
        let tmp1 = format!("/tmp/test_vato_guard_drop_{}_1.jpg", uuid_v4_short());
        let tmp2 = format!("/tmp/test_vato_guard_drop_{}_2.jpg", uuid_v4_short());
        std::fs::write(&tmp1, b"test1").unwrap();
        std::fs::write(&tmp2, b"test2").unwrap();
        assert!(std::path::Path::new(&tmp1).exists());
        assert!(std::path::Path::new(&tmp2).exists());

        {
            let mut guard = TempFilesGuard(Vec::new());
            guard.0.push(tmp1.clone());
            guard.0.push(tmp2.clone());
        } // guard drops here

        assert!(!std::path::Path::new(&tmp1).exists());
        assert!(!std::path::Path::new(&tmp2).exists());
    }

    #[test]
    fn test_temp_files_guard_cleanup_on_panic() {
        let tmp = format!("/tmp/test_vato_guard_panic_{}.jpg", uuid_v4_short());
        std::fs::write(&tmp, b"panic-test").unwrap();
        assert!(std::path::Path::new(&tmp).exists());

        let _ = std::panic::catch_unwind(|| {
            let mut guard = TempFilesGuard(Vec::new());
            guard.0.push(tmp.clone());
            panic!("simulated panic");
        });

        assert!(!std::path::Path::new(&tmp).exists());
    }

    #[test]
    fn test_concurrency_lock() {
        let state = Arc::new(AppState::new());
        let state_clone = Arc::clone(&state);

        let handle = std::thread::spawn(move || {
            let _guard = state_clone.omp_lock.lock().unwrap();
            std::thread::sleep(Duration::from_millis(50));
        });

        std::thread::sleep(Duration::from_millis(10));
        // The lock is held by the worker thread
        let try_lock_result = state.omp_lock.try_lock();
        assert!(try_lock_result.is_err());

        handle.join().unwrap();
        // Now the lock is released
        let try_lock_after = state.omp_lock.try_lock();
        assert!(try_lock_after.is_ok());
    }
}
