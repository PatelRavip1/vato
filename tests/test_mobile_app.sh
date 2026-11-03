#!/usr/bin/env bash
# tests/test_mobile_app.sh - Automated on-device test runner for Vato Android app
set -euo pipefail

# Configuration
DEVICE="${DEVICE:-192.168.6.111:44337}"
if [[ "${DEVICE}" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ]]; then
    DEVICE_IP="${DEVICE%%:*}"
else
    DEVICE_IP="192.168.6.111"
fi
PACKAGE_NAME="com.vato.qwenchat"
MAIN_ACTIVITY="com.vato.qwenchat/.MainActivity"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ARTIFACTS_DIR="${SCRIPT_DIR}/artifacts"

mkdir -p "${ARTIFACTS_DIR}"

echo "=========================================================="
echo " Starting Mobile App E2E Test on Device: ${DEVICE}"
echo "=========================================================="

# 1a. Device ping & ADB connection state check
echo "[1/7] Checking device ping and ADB connection state..."
if ! ping -c 1 -W 3 "${DEVICE_IP}" > /dev/null 2>&1; then
    echo "ERROR: Unable to ping ${DEVICE_IP}" >&2
    exit 1
fi
echo "  ✓ Ping to ${DEVICE_IP} succeeded."

ADB_STATE=$(adb -s "${DEVICE}" get-state 2>/dev/null || true)
if [ "${ADB_STATE}" != "device" ]; then
    echo "  ADB not connected, attempting adb connect ${DEVICE}..."
    adb connect "${DEVICE}"
    ADB_STATE=$(adb -s "${DEVICE}" get-state 2>/dev/null || true)
    if [ "${ADB_STATE}" != "device" ]; then
        echo "ERROR: Device ${DEVICE} state is '${ADB_STATE}', expected 'device'" >&2
        exit 1
    fi
fi
echo "  ✓ ADB state: ${ADB_STATE}"

# Wake up screen and dismiss keyguard if needed
adb -s "${DEVICE}" shell input keyevent KEYCODE_WAKEUP || true
adb -s "${DEVICE}" shell wm dismiss-keyguard || true

# 1b. Verification that package is installed
echo "[2/7] Verifying package installation: ${PACKAGE_NAME}..."
if ! adb -s "${DEVICE}" shell pm list packages | grep -q "package:${PACKAGE_NAME}"; then
    echo "ERROR: Package ${PACKAGE_NAME} is not installed on ${DEVICE}" >&2
    exit 1
fi
echo "  ✓ Package ${PACKAGE_NAME} is installed."

# Clear logcat before test run to ensure clean crash scanning
adb -s "${DEVICE}" logcat -c

# 1c. Launch MainActivity with timing
echo "[3/7] Launching ${MAIN_ACTIVITY} with timing..."
START_OUTPUT=$(adb -s "${DEVICE}" shell am start -W -n "${MAIN_ACTIVITY}")
echo "${START_OUTPUT}" | sed 's/^/    /'

if ! echo "${START_OUTPUT}" | grep -q "Status: ok"; then
    echo "ERROR: Failed to launch ${MAIN_ACTIVITY}" >&2
    exit 1
fi
echo "  ✓ Activity launch status is OK."

# Allow UI to settle
sleep 1

# 1d. Verification that the process is alive
echo "[4/7] Verifying process is alive via pidof..."
APP_PID=$(adb -s "${DEVICE}" shell pidof "${PACKAGE_NAME}" | tr -d '\r\n' || true)
if [ -z "${APP_PID}" ]; then
    echo "ERROR: Process ${PACKAGE_NAME} is not running!" >&2
    exit 1
fi
echo "  ✓ Process ${PACKAGE_NAME} is alive with PID: ${APP_PID}"

# 1e. Logcat scan for zero fatal crash / AndroidRuntime exceptions
echo "[5/7] Scanning logcat for fatal crashes / AndroidRuntime exceptions..."
CRASH_BUFFER=$(adb -s "${DEVICE}" logcat -b crash -d)
if [ -n "${CRASH_BUFFER}" ]; then
    echo "ERROR: Crash detected in logcat crash buffer:" >&2
    echo "${CRASH_BUFFER}" >&2
    exit 1
fi

FATAL_LOGS=$(adb -s "${DEVICE}" logcat -d | grep -iE "FATAL EXCEPTION" | grep -F "${PACKAGE_NAME}" || true)
if [ -n "${FATAL_LOGS}" ]; then
    echo "ERROR: Fatal exception detected for ${PACKAGE_NAME}:" >&2
    echo "${FATAL_LOGS}" >&2
    exit 1
fi

RUNTIME_ERRS=$(adb -s "${DEVICE}" logcat -d -s AndroidRuntime:E | grep -F "${PACKAGE_NAME}" || true)
if [ -n "${RUNTIME_ERRS}" ]; then
    echo "ERROR: AndroidRuntime error detected for ${PACKAGE_NAME}:" >&2
    echo "${RUNTIME_ERRS}" >&2
    exit 1
fi
echo "  ✓ Zero fatal crashes and zero AndroidRuntime exceptions detected."

# 1f. UI hierarchy dump via uiautomator dump and pull, asserting essential elements
echo "[6/7] Dumping UI hierarchy via uiautomator dump..."
adb -s "${DEVICE}" shell uiautomator dump /sdcard/window_dump.xml
adb -s "${DEVICE}" pull /sdcard/window_dump.xml "${ARTIFACTS_DIR}/window_dump.xml"

if [ ! -s "${ARTIFACTS_DIR}/window_dump.xml" ]; then
    echo "ERROR: Failed to pull ${ARTIFACTS_DIR}/window_dump.xml or file is empty" >&2
    exit 1
fi

echo "  Checking essential UI hierarchy elements in window_dump.xml..."
if ! grep -q "package=\"${PACKAGE_NAME}\"" "${ARTIFACTS_DIR}/window_dump.xml"; then
    echo "ERROR: Hierarchy missing expected package '${PACKAGE_NAME}'" >&2
    exit 1
fi

if ! grep -q "class=\"android.webkit.WebView\"" "${ARTIFACTS_DIR}/window_dump.xml"; then
    echo "ERROR: Hierarchy missing expected class 'android.webkit.WebView'" >&2
    exit 1
fi

if ! grep -q "resource-id=\"android:id/content\"" "${ARTIFACTS_DIR}/window_dump.xml"; then
    echo "ERROR: Hierarchy missing expected content frame 'android:id/content'" >&2
    exit 1
fi
echo "  ✓ UI hierarchy dump validated: package, WebView, and content root present."

# 1g. Screencap capture and pull into tests/artifacts/
echo "[7/7] Capturing screencap..."
adb -s "${DEVICE}" shell screencap -p /sdcard/vato_test_screen.png
adb -s "${DEVICE}" pull /sdcard/vato_test_screen.png "${ARTIFACTS_DIR}/vato_test_screen.png"

if [ ! -s "${ARTIFACTS_DIR}/vato_test_screen.png" ]; then
    echo "ERROR: Failed to pull screencap ${ARTIFACTS_DIR}/vato_test_screen.png or file is empty" >&2
    exit 1
fi
SCREEN_SIZE=$(stat -c%s "${ARTIFACTS_DIR}/vato_test_screen.png")
echo "  ✓ Screencap pulled to ${ARTIFACTS_DIR}/vato_test_screen.png (${SCREEN_SIZE} bytes)."

echo "=========================================================="
echo " ALL TESTS PASSED: Device ${DEVICE} verified successfully!"
echo "=========================================================="
exit 0
