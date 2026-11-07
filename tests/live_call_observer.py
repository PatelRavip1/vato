#!/usr/bin/env python3
"""
tests/live_call_observer.py - Live Voice Call Observer for Physical Phone
Tracks real-time user voice input, STT transcripts, LLM generation, TTS playback,
and turn latencies across 10 live conversational turns on the physical Pixel 10 Pro XL.
"""

import os
import sys
import time
import json
import base64
import subprocess
from pathlib import Path

DEVICE = os.environ.get("DEVICE", "adb-59200DLCQ002UB-J0Pxs6._adb-tls-connect._tcp")
PACKAGE = "com.vato.qwenchat"
TARGET_TURNS = 10
ARTIFACTS_DIR = Path(__file__).parent / "artifacts" / "physical_phone_test"
ARTIFACTS_DIR.mkdir(parents=True, exist_ok=True)


def get_meminfo():
    res = subprocess.run(["adb", "-s", DEVICE, "shell", "dumpsys", "meminfo", PACKAGE], capture_output=True)
    out = res.stdout.decode("utf-8", errors="replace")
    pss_kb = 0
    for line in out.splitlines():
        if "TOTAL PSS:" in line:
            parts = line.split()
            if len(parts) >= 3:
                try:
                    pss_kb = int(parts[2])
                except ValueError:
                    pass
    return {"pss_kb": pss_kb}


def capture_screenshot(filename):
    dest = ARTIFACTS_DIR / filename
    subprocess.run(["adb", "-s", DEVICE, "shell", "screencap", "-p", f"/sdcard/{filename}"], capture_output=True)
    subprocess.run(["adb", "-s", DEVICE, "pull", f"/sdcard/{filename}", str(dest)], capture_output=True)
    return str(dest)


def main():
    print(f"=== Starting Real-Time Voice Call Observer on Physical Phone: {DEVICE} ===")
    print(f"Waiting for user to speak 10 conversational turns in call mode...\n")

    subprocess.run(["adb", "-s", DEVICE, "logcat", "-c"], capture_output=True)
    b64 = base64.b64encode(b"window.__liveStart && window.__liveStart()").decode("ascii")
    payload = f'"eval(decodeURIComponent(escape(atob(\\"{b64}\\"))))"'
    subprocess.run(["adb", "-s", DEVICE, "shell", "am", "start", "-a", "android.intent.action.VIEW", "-n", f"{PACKAGE}/.MainActivity", "-f", "0x20000000", "--es", "eval_js", payload], capture_output=True)
    time.sleep(1.0)
    initial_mem = get_meminfo()
    print(f"Initial Memory PSS: {initial_mem['pss_kb']} KB")
    capture_screenshot("screencap_phone_start.png")
    cmd = ["adb", "-s", DEVICE, "logcat", "-v", "time", "-s", "VatoWeb:D"]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    turns = []
    current_turn = None
    turn_idx = 0

    try:
        for line_bytes in iter(proc.stdout.readline, b""):
            line = line_bytes.decode("utf-8", errors="replace").strip()
            if not line:
                continue

            # 1. User speech captured & final transcript received
            if "VATO_CALL_USER:" in line:
                raw_json = line.split("VATO_CALL_USER:", 1)[1].rsplit(" (line ", 1)[0].strip()
                try:
                    user_data = json.loads(raw_json)
                except Exception:
                    user_data = {"text": raw_json, "lang": "unknown", "emotion": ""}
                if current_turn is not None:
                    if current_turn["bot_text"]:
                        current_turn["total_turn_ms"] = (time.time() - current_turn["t_start"]) * 1000
                        turns.append(current_turn)
                    current_turn = None

                turn_idx += 1
                current_turn = {
                    "turn": turn_idx,
                    "t_start": time.time(),
                    "user_text": user_data.get("text", ""),
                    "emotion": user_data.get("emotion", ""),
                    "lang": user_data.get("lang", ""),
                    "bot_text": None,
                    "llm_latency_ms": None,
                    "tts_duration_ms": None,
                    "total_turn_ms": None,
                    "t_thinking_start": time.time(),
                    "t_speaking_start": None,
                }
                lang_tag = current_turn["lang"]
                emo_tag = f" [{current_turn['emotion']}]" if current_turn["emotion"] else ""
                print(f"\n[Turn {turn_idx}/{TARGET_TURNS}] [{lang_tag}]{emo_tag}")
                print(f"  User: {current_turn['user_text']}")

            # 2. Bot reply generated
            elif "VATO_CALL_BOT:" in line:
                raw_json = line.split("VATO_CALL_BOT:", 1)[1].rsplit(" (line ", 1)[0].strip()
                try:
                    bot_data = json.loads(raw_json)
                except Exception:
                    bot_data = {"text": raw_json, "lang": "unknown"}

                if current_turn:
                    current_turn["bot_text"] = bot_data.get("text", "")
                    current_turn["llm_latency_ms"] = (time.time() - current_turn["t_thinking_start"]) * 1000
                    first_line = current_turn["bot_text"].splitlines()[0] if current_turn["bot_text"] else ""
                    print(f"  Assistant ({current_turn['llm_latency_ms']:.0f}ms): {first_line[:90]}")

            # 3. Phase change
            elif "VATO_CALL_PHASE:" in line:
                phase = line.split("VATO_CALL_PHASE:", 1)[1].rsplit(" (line ", 1)[0].strip()
                if current_turn:
                    if phase == "speaking" and current_turn["t_speaking_start"] is None:
                        current_turn["t_speaking_start"] = time.time()
                    elif phase == "listening" and current_turn["t_speaking_start"] is not None:
                        # Turn complete!
                        current_turn["tts_duration_ms"] = (time.time() - current_turn["t_speaking_start"]) * 1000
                        current_turn["total_turn_ms"] = (time.time() - current_turn["t_start"]) * 1000
                        print(f"  TTS duration: {current_turn['tts_duration_ms']:.0f}ms | Total turn: {current_turn['total_turn_ms']:.0f}ms")
                        turns.append(current_turn)
                        current_turn = None

                        if turn_idx == 5:
                            capture_screenshot("screencap_phone_turn5.png")
                            print("  [Screenshot captured at Turn 5]")

                        if turn_idx >= TARGET_TURNS:
                            print(f"\n========================================================")
                            print(f"All {TARGET_TURNS} Turns Successfully Completed on Physical Phone!")
                            print(f"========================================================")
                            break
    finally:
        proc.terminate()

    capture_screenshot("screencap_phone_final.png")
    final_mem = get_meminfo()

    # Calculate statistics
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

    llm_stats = stats([t["llm_latency_ms"] for t in turns if t["llm_latency_ms"]])
    tts_stats = stats([t["tts_duration_ms"] for t in turns if t["tts_duration_ms"]])
    total_stats = stats([t["total_turn_ms"] for t in turns if t["total_turn_ms"]])

    report = {
        "device": DEVICE,
        "target_turns": TARGET_TURNS,
        "completed_turns": len(turns),
        "initial_pss_kb": initial_mem["pss_kb"],
        "final_pss_kb": final_mem["pss_kb"],
        "pss_delta_kb": final_mem["pss_kb"] - initial_mem["pss_kb"],
        "stats": {
            "llm_latency_ms": llm_stats,
            "tts_duration_ms": tts_stats,
            "total_turn_ms": total_stats,
        },
        "turns": turns,
    }

    report_json = ARTIFACTS_DIR / "physical_phone_10turn_report.json"
    with open(report_json, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)

    report_md = ARTIFACTS_DIR / "physical_phone_10turn_report.md"
    with open(report_md, "w", encoding="utf-8") as f:
        f.write("# Vato Physical Phone 10-Turn Live Voice Call Verification Report\n\n")
        f.write(f"- **Device**: Pixel 10 Pro XL (`{DEVICE}`)\n")
        f.write(f"- **Input Method**: Physical Device Microphone (Spoken by User)\n")
        f.write(f"- **Completed Turns**: {len(turns)} / {TARGET_TURNS}\n")
        f.write(f"- **Initial Memory (PSS)**: {initial_mem['pss_kb']} KB | **Final PSS**: {final_mem['pss_kb']} KB\n\n")

        f.write("## Latency Breakdown\n\n")
        f.write("| Component | Count | Min (ms) | Avg (ms) | P95 (ms) | Max (ms) |\n")
        f.write("|---|---|---|---|---|---|\n")
        f.write(f"| LLM Generation | {llm_stats['count']} | {llm_stats['min']} | {llm_stats['avg']} | {llm_stats['p95']} | {llm_stats['max']} |\n")
        f.write(f"| TTS Playback | {tts_stats['count']} | {tts_stats['min']} | {tts_stats['avg']} | {tts_stats['p95']} | {tts_stats['max']} |\n")
        f.write(f"| Total Turn Time | {total_stats['count']} | {total_stats['min']} | {total_stats['avg']} | {total_stats['p95']} | {total_stats['max']} |\n\n")

        f.write("## Verified Turn Log\n\n")
        f.write("| # | Lang | User Spoken Transcript | LLM (ms) | Assistant Reply Snippet |\n")
        f.write("|---|---|---|---|---|\n")
        for t in turns:
            snip = (t["bot_text"] or "").replace("\n", " ")[:70]
            f.write(f"| {t['turn']} | {t['lang']} | {t['user_text'][:40]} | {t['llm_latency_ms']:.0f}ms | {snip} |\n")

    print(f"\n[+] Report saved to {report_md} and {report_json}")


if __name__ == "__main__":
    main()
