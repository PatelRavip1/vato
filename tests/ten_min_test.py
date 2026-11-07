#!/usr/bin/env python3
"""
tests/ten_min_test.py - 10-Minute Continuous Bilingual Voice Call Stress & Endurance Test
Tests English (SenseVoice STT + Sonia TTS) and Gujarati (Native Recognizer + Native TTS)
across 600 seconds of continuous multi-turn dialogue on Android emulator (pixel10proxl).
"""

import os
import sys
import time
import json
import base64
import urllib.request
import subprocess
from pathlib import Path

DEVICE = os.environ.get("DEVICE", "emulator-5554")
PACKAGE = "com.vato.qwenchat"
MAIN_ACT = f"{PACKAGE}/.MainActivity"
TARGET_DURATION_SEC = 600  # 10 minutes
ARTIFACTS_DIR = Path(__file__).parent / "artifacts" / "ten_min_test"
ARTIFACTS_DIR.mkdir(parents=True, exist_ok=True)

ENGLISH_TURNS = [
    ("Hello! How are you doing today?", "HAPPY"),
    ("What is the typical climate like in Gujarat during November?", "NEUTRAL"),
    ("Tell me about the Sabarmati Riverfront development in Ahmedabad.", "NEUTRAL"),
    ("What activities and recreational spots can visitors enjoy there in the evening?", "HAPPY"),
    ("How does Ahmedabad's textile heritage earned it the name Manchester of the East?", "NEUTRAL"),
    ("Can you explain the historical significance of the Dandi March?", "NEUTRAL"),
    ("What are some key advantages of on-device neural transcription over cloud APIs?", "NEUTRAL"),
    ("Describe the wildlife found in Gir National Park.", "HAPPY"),
    ("What architectural features make the Sun Temple at Modhera unique?", "NEUTRAL"),
    ("How does the Sardar Sarovar Dam impact irrigation in western India?", "NEUTRAL"),
    ("What are the best street food destinations around Manek Chowk at night?", "HAPPY"),
    ("Explain how solar parks in Charanka and Khavda contribute to green energy.", "NEUTRAL"),
    ("What makes Lothal an important site for Indus Valley civilization archaeology?", "NEUTRAL"),
    ("Tell me about the craftsmanship behind Patan Patola double ikat silk sarees.", "NEUTRAL"),
    ("What is the significance of the Kite Festival celebrated during Uttarayan?", "HAPPY"),
    ("How do stepwells like Adalaj and Rani ki Vav manage rainwater conservation?", "NEUTRAL"),
    ("Can you summarize the life and legacy of Sardar Vallabhbhai Patel?", "NEUTRAL"),
    ("What are the main export industries currently driving Gujarat's economy?", "NEUTRAL"),
    ("Describe the landscape and salt desert ecosystem of the Great Rann of Kutch.", "NEUTRAL"),
    ("What are the upcoming urban transportation projects in Ahmedabad and Gandhinagar?", "NEUTRAL"),
]

GUJARATI_TURNS = [
    "કેમ છો? ગાંધી આશ્રમ વિશે ટૂંકમાં જણાવો.",
    "ત્યાં પહોંચવા માટે નજીકનું રેલ્વે સ્ટેશન કયું છે?",
    "અમદાવાદમાં ફરવા માટે બીજા કયા જાણીતા સ્થળો છે?",
    "ગુજરાતની કઈ કઈ વાનગીઓ વિશ્વભરમાં પ્રખ્યાત છે?",
    "પાટણના પટોળા શા માટે આટલા મૂલ્યવાન અને ખાસ ગણાય છે?",
    "અડાલજની વાવના સ્થાપત્ય અને ઇતિહાસ વિશે માહિતી આપો.",
    "ગુજરાતમાં નવરાત્રીનો તહેવાર શા માટે આટલો ઉત્સાહપૂર્વક ઉજવાય છે?",
    "ગુજરાતના 1600 કિલોમીટર લાંબા દરિયાકિનારાના મુખ્ય બંદરો કયા છે?",
    "નરસિંહ મહેતાનું ગુજરાતી ભક્તિ સાહિત્યમાં શું મહત્વ છે?",
    "ગીરના એશિયાઈ સિંહોના સંરક્ષણ માટે સરકાર શું પગલાં લઈ રહી છે?",
    "મોઢેરાનું સૂર્યમંદિર કઈ સ્થાપત્ય શૈલીમાં બંધાયેલું છે?",
    "કચ્છના રણોત્સવની વિશેષતા શું છે?",
    "ગુજરાતી થાળીમાં કયા કયા મુખ્ય પકવાનો પીરસવામાં આવે છે?",
    "સ્ટેચ્યુ ઓફ યુનિટી વિશે ટૂંકી અને રસપ્રદ માહિતી આપો.",
    "સરદાર સરોવર યોજનાથી કયા કયા રાજ્યોને ફાયદો થાય છે?",
    "અમદાવાદને વર્લ્ડ હેરિટેજ સિટીનો દરજ્જો ક્યારે અને કેમ મળ્યો?",
    "સૂફી અને સંત પરંપરામાં કબીરવડનું શું મહત્વ છે?",
    "કાંકરિયા તળાવ ખાતે બાળકો અને પરિવારો માટે કયા આકર્ષણો છે?",
    "ગુજરાતમાં દૂધ સહકારી ક્રાંતિ (અમૂલ) ની શરૂઆત કેવી રીતે થઈ?",
    "આપનો ખૂબ ખૂબ આભાર, તમામ માહિતી ખૂબ જ સચોટ અને ઉપયોગી રહી.",
]


def run_cmd(cmd_list):
    res = subprocess.run(cmd_list, capture_output=True)
    out = res.stdout.decode("utf-8", errors="replace").strip()
    err = res.stderr.decode("utf-8", errors="replace").strip()
    return out, err, res.returncode

def eval_js(js_code):
    b64 = base64.b64encode(js_code.encode("utf-8")).decode("ascii")
    payload = f'"eval(decodeURIComponent(escape(atob(\\"{b64}\\"))))"'
    cmd = [
        "adb", "-s", DEVICE, "shell",
        "am", "start", "-a", "android.intent.action.VIEW",
        "-n", MAIN_ACT,
        "-f", "0x20000000",
        "--es", "eval_js", payload,
    ]
    return subprocess.run(cmd, capture_output=True, text=True)


def get_webview_state(timeout=5.0):
    run_cmd(["adb", "-s", DEVICE, "logcat", "-c"])
    dump_js = 'console.log("VATO_STATE:" + JSON.stringify({active: liveActive, phase: livePhase, lang: liveLang, count: chatMessages.length, last: chatMessages[chatMessages.length-1] || null}))'
    eval_js(dump_js)
    t0 = time.time()
    while time.time() - t0 < timeout:
        out, _, _ = run_cmd(["adb", "-s", DEVICE, "logcat", "-d", "-s", "VatoWeb:D"])
        for line in out.splitlines():
            if "VATO_STATE:" in line:
                payload = line.split("VATO_STATE:", 1)[1].rsplit(" (line ", 1)[0].strip()
                try:
                    return json.loads(payload)
                except Exception:
                    pass
        time.sleep(0.15)
    return None


def get_meminfo():
    out, _, _ = run_cmd(["adb", "-s", DEVICE, "shell", "dumpsys", "meminfo", PACKAGE])
    pss_kb = 0
    rss_kb = 0
    for line in out.splitlines():
        if "TOTAL PSS:" in line:
            parts = line.split()
            if len(parts) >= 3:
                try:
                    pss_kb = int(parts[2])
                except ValueError:
                    pass
        elif "TOTAL RSS:" in line:
            parts = line.split()
            if len(parts) >= 3:
                try:
                    rss_kb = int(parts[2])
                except ValueError:
                    pass
    return {"pss_kb": pss_kb, "rss_kb": rss_kb}


def capture_screenshot(filename):
    dest = ARTIFACTS_DIR / filename
    run_cmd(["adb", "-s", DEVICE, "shell", "screencap", "-p", f"/sdcard/{filename}"])
    run_cmd(["adb", "-s", DEVICE, "pull", f"/sdcard/{filename}", str(dest)])
    return str(dest)


def check_crashes():
    out, _, _ = run_cmd(["adb", "-s", DEVICE, "logcat", "-b", "crash", "-d"])
    if out.strip():
        return out.strip()
    out, _, _ = run_cmd(["adb", "-s", DEVICE, "logcat", "-d", "-s", "AndroidRuntime:E"])
    if "FATAL EXCEPTION" in out:
        return out.strip()
    return None


def test_stt_endpoint():
    """Directly test backend SenseVoice STT on /v1/audio/transcriptions."""
    import wave
    import struct
    import math

    wav_path = "/tmp/stt_test_probe.wav"
    with wave.open(wav_path, "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(16000)
        for i in range(int(16000 * 0.8)):
            val = int(32767.0 * 0.08 * math.sin(2.0 * math.pi * 440.0 * i / 16000.0))
            f.writeframes(struct.pack("<h", val))
    with open(wav_path, "rb") as f:
        data = f.read()

    t0 = time.time()
    req = urllib.request.Request(
        "http://127.0.0.1:8081/v1/audio/transcriptions",
        data=data,
        headers={"Content-Type": "audio/wav"},
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        res = json.loads(resp.read().decode())
    stt_ms = (time.time() - t0) * 1000
    return res, stt_ms


def main():
    print(f"=== Starting 10-Minute Bilingual Voice Call Stress Test on {DEVICE} ===")
    start_time = time.time()
    end_time = start_time + TARGET_DURATION_SEC

    # 1. Reverse port forward & wake device
    run_cmd(["adb", "-s", DEVICE, "reverse", "tcp:8081", "tcp:8081"])
    run_cmd(["adb", "-s", DEVICE, "shell", "input", "keyevent", "KEYCODE_WAKEUP"])
    run_cmd(["adb", "-s", DEVICE, "shell", "wm", "dismiss-keyguard"])

    # 2. Reset chat & launch call
    print("[1] Initializing clean chat state and starting Live Call...")
    eval_js("window.__clearChat && window.__clearChat()")
    time.sleep(1.0)
    eval_js("window.__liveStart && window.__liveStart()")
    time.sleep(1.5)

    initial_state = get_webview_state()
    print(f"Initial state: {initial_state}")
    initial_mem = get_meminfo()
    print(f"Initial meminfo: PSS={initial_mem['pss_kb']} KB, RSS={initial_mem['rss_kb']} KB")
    capture_screenshot("screencap_00m_start.png")

    turns_log = []
    screencap_checkpoints = [
        (120, "screencap_02m.png"),
        (240, "screencap_04m.png"),
        (360, "screencap_06m.png"),
        (480, "screencap_08m.png"),
    ]
    screencaps_taken = set()

    turn_idx = 0
    en_idx = 0
    gu_idx = 0
    current_lang = "en-IN"

    while time.time() < end_time:
        turn_idx += 1
        elapsed = time.time() - start_time
        remaining = end_time - time.time()

        # Check screencap milestones
        for sec, fname in screencap_checkpoints:
            if elapsed >= sec and fname not in screencaps_taken:
                capture_screenshot(fname)
                screencaps_taken.add(fname)
                print(f"[Milestone] Screenshot captured at {elapsed:.1f}s -> {fname}")

        # Alternate language every 3 turns
        target_lang = "en-IN" if (turn_idx // 3) % 2 == 0 else "gu-IN"
        if target_lang != current_lang:
            current_lang = target_lang
            print(f"\n--- Switching Call Language to: {current_lang} (Elapsed: {elapsed:.1f}s) ---")
            eval_js(f"window.__setLiveLang && window.__setLiveLang('{current_lang}')")
            time.sleep(0.5)

        turn_start = time.time()
        is_english = current_lang == "en-IN"
        if is_english:
            prompt, emotion = ENGLISH_TURNS[en_idx % len(ENGLISH_TURNS)]
            en_idx += 1
        else:
            prompt = GUJARATI_TURNS[gu_idx % len(GUJARATI_TURNS)]
            emotion = ""
            gu_idx += 1

        print(f"\n[Turn {turn_idx}] [{current_lang}] ({elapsed:.1f}s / {TARGET_DURATION_SEC}s)")
        print(f"  User: {prompt} {f'[{emotion}]' if emotion else ''}")

        # Probe SenseVoice STT
        stt_latency_ms = None
        if is_english:
            try:
                stt_res, stt_latency_ms = test_stt_endpoint()
            except Exception as e:
                print(f"  [Warning] STT probe error: {e}")

        # Get current message count before dispatching turn
        pre_st = get_webview_state()
        pre_count = pre_st["count"] if pre_st else 0

        # Inject voice turn
        if emotion:
            js_call = f"window.__onVoiceFinal({json.dumps(prompt)}, {json.dumps(emotion)})"
        else:
            js_call = f"window.__onVoiceFinal({json.dumps(prompt)})"
        eval_js(js_call)

        # Wait for LLM generation (new assistant message in chat)
        llm_start = time.time()
        llm_reply = None
        llm_latency_ms = None
        while time.time() - llm_start < 28.0:
            st = get_webview_state()
            if st and st.get("count", 0) >= pre_count + 2 and st.get("last"):
                if st["last"].get("role") == "assistant":
                    llm_reply = st["last"].get("content", "")
                    llm_latency_ms = (time.time() - llm_start) * 1000
                    break
            time.sleep(0.3)

        if not llm_reply:
            print("  [ERROR] Turn timed out waiting for assistant reply!")
            error_note = "LLM timeout"
        else:
            first_line = llm_reply.splitlines()[0] if llm_reply else ""
            print(f"  Assistant ({llm_latency_ms:.0f}ms): {first_line[:80]}...")
            error_note = None

        # Allow natural TTS playback to progress
        tts_start = time.time()
        while time.time() - tts_start < 5.0:
            st = get_webview_state()
            if st and st.get("phase") == "listening":
                break
            time.sleep(0.4)
        tts_duration_ms = (time.time() - tts_start) * 1000
        total_turn_ms = (time.time() - turn_start) * 1000

        # Memory snapshot
        mem = get_meminfo()

        # Check for any crash in logcat
        crash = check_crashes()
        if crash:
            print(f"  [FATAL] Crash detected in logcat:\n{crash}")
            break

        turn_record = {
            "turn": turn_idx,
            "elapsed_sec": round(elapsed, 1),
            "language": current_lang,
            "prompt": prompt,
            "emotion": emotion,
            "stt_ms": round(stt_latency_ms, 1) if stt_latency_ms else None,
            "llm_ms": round(llm_latency_ms, 1) if llm_latency_ms else None,
            "tts_ms": round(tts_duration_ms, 1),
            "total_turn_ms": round(total_turn_ms, 1),
            "pss_kb": mem["pss_kb"],
            "rss_kb": mem["rss_kb"],
            "error": error_note,
            "reply_snippet": (llm_reply[:120] if llm_reply else None),
        }
        turns_log.append(turn_record)

        # Brief pause between turns
        time.sleep(0.8)

    # 10 minutes reached!
    final_elapsed = time.time() - start_time
    print(f"\n========================================================")
    print(f"10-Minute Run Completed in {final_elapsed:.1f} seconds! Total turns: {turn_idx}")
    print(f"========================================================")

    # Capture final screen and end call cleanly
    capture_screenshot("screencap_10m_final.png")
    eval_js("window.__exitLiveTalk && window.__exitLiveTalk()")
    time.sleep(1.0)
    capture_screenshot("screencap_10m_ended.png")

    final_mem = get_meminfo()

    # Compile statistics
    successful_turns = [t for t in turns_log if not t["error"]]
    en_turns = [t for t in successful_turns if t["language"] == "en-IN"]
    gu_turns = [t for t in successful_turns if t["language"] == "gu-IN"]

    def stats(vals):
        if not vals:
            return {"count": 0, "avg": 0, "min": 0, "max": 0, "p95": 0}
        s = sorted(vals)
        p95 = s[int(len(s) * 0.95)] if len(s) > 1 else s[-1]
        return {
            "count": len(s),
            "avg": round(sum(s) / len(s), 1),
            "min": round(s[0], 1),
            "max": round(s[-1], 1),
            "p95": round(p95, 1),
        }

    en_llm_stats = stats([t["llm_ms"] for t in en_turns if t["llm_ms"]])
    gu_llm_stats = stats([t["llm_ms"] for t in gu_turns if t["llm_ms"]])
    stt_stats = stats([t["stt_ms"] for t in en_turns if t["stt_ms"]])
    total_stats = stats([t["total_turn_ms"] for t in successful_turns])

    summary = {
        "device": DEVICE,
        "package": PACKAGE,
        "target_duration_sec": TARGET_DURATION_SEC,
        "actual_duration_sec": round(final_elapsed, 1),
        "total_turns": turn_idx,
        "successful_turns": len(successful_turns),
        "failed_turns": len(turns_log) - len(successful_turns),
        "success_rate_pct": round(len(successful_turns) / max(1, turn_idx) * 100.0, 1),
        "initial_memory_pss_kb": initial_mem["pss_kb"],
        "final_memory_pss_kb": final_mem["pss_kb"],
        "memory_delta_pss_kb": final_mem["pss_kb"] - initial_mem["pss_kb"],
        "initial_memory_rss_kb": initial_mem["rss_kb"],
        "final_memory_rss_kb": final_mem["rss_kb"],
        "memory_delta_rss_kb": final_mem["rss_kb"] - initial_mem["rss_kb"],
        "stats": {
            "english_llm_ms": en_llm_stats,
            "gujarati_llm_ms": gu_llm_stats,
            "sensevoice_stt_ms": stt_stats,
            "overall_turn_ms": total_stats,
        },
        "turns": turns_log,
    }

    report_json = ARTIFACTS_DIR / "ten_min_test_report.json"
    with open(report_json, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2, ensure_ascii=False)
    print(f"[+] Detailed JSON report saved to: {report_json}")

    # Generate Markdown report
    report_md = ARTIFACTS_DIR / "ten_min_test_report.md"
    with open(report_md, "w", encoding="utf-8") as f:
        f.write("# Vato 10-Minute Bilingual Live Voice Call Stress Test Report\n\n")
        f.write(f"- **Date/Timestamp**: {time.strftime('%Y-%m-%d %H:%M:%S')}\n")
        f.write(f"- **Target Duration**: {TARGET_DURATION_SEC}s (10 min)\n")
        f.write(f"- **Actual Duration**: {final_elapsed:.1f}s\n")
        f.write(f"- **Total Turns**: {turn_idx}\n")
        f.write(f"- **Success Rate**: {summary['success_rate_pct']}%\n")
        f.write(f"- **Zero Fatal Crashes / ANRs**: {'PASSED' if not check_crashes() else 'FAILED'}\n\n")

        f.write("## Latency Profile\n\n")
        f.write("| Component | Language | Count | Min (ms) | Avg (ms) | P95 (ms) | Max (ms) |\n")
        f.write("|---|---|---|---|---|---|---|\n")
        f.write(f"| SenseVoice STT | en-IN | {stt_stats['count']} | {stt_stats['min']} | {stt_stats['avg']} | {stt_stats['p95']} | {stt_stats['max']} |\n")
        f.write(f"| LLM Generation | en-IN | {en_llm_stats['count']} | {en_llm_stats['min']} | {en_llm_stats['avg']} | {en_llm_stats['p95']} | {en_llm_stats['max']} |\n")
        f.write(f"| LLM Generation | gu-IN | {gu_llm_stats['count']} | {gu_llm_stats['min']} | {gu_llm_stats['avg']} | {gu_llm_stats['p95']} | {gu_llm_stats['max']} |\n")
        f.write(f"| Complete Turn | Bilingual | {total_stats['count']} | {total_stats['min']} | {total_stats['avg']} | {total_stats['p95']} | {total_stats['max']} |\n\n")

        f.write("## Memory Profile (Android Dumpsys Meminfo)\n\n")
        f.write(f"- Initial PSS: {initial_mem['pss_kb']} KB | Final PSS: {final_mem['pss_kb']} KB (Delta: {final_mem['pss_kb'] - initial_mem['pss_kb']:+} KB)\n")
        f.write(f"- Initial RSS: {initial_mem['rss_kb']} KB | Final RSS: {final_mem['rss_kb']} KB (Delta: {final_mem['rss_kb'] - initial_mem['rss_kb']:+} KB)\n\n")

        f.write("## Turn Log Excerpt (First 5 & Last 5 Turns)\n\n")
        f.write("| # | Time (s) | Lang | User Prompt | LLM Latency | Reply Snippet |\n")
        f.write("|---|---|---|---|---|---|\n")
        excerpt = turns_log[:5] + (turns_log[-5:] if len(turns_log) > 10 else [])
        for t in excerpt:
            snip = (t["reply_snippet"] or "").replace("\n", " ")[:60]
            f.write(f"| {t['turn']} | {t['elapsed_sec']} | {t['language']} | {t['prompt'][:35]} | {t['llm_ms']}ms | {snip} |\n")

    print(f"[+] Markdown report saved to: {report_md}")


if __name__ == "__main__":
    main()
