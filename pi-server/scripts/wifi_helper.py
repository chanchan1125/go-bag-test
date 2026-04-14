#!/usr/bin/env python3
import json
import os
import shutil
import subprocess
import sys


DEFAULT_TIMEOUT_S = 25.0


def fail(message: str, exit_code: int = 1) -> int:
    print(message, file=sys.stderr)
    return exit_code


def nmcli_path() -> str:
    path = shutil.which("nmcli") or "/usr/bin/nmcli"
    if not path or not os.path.exists(path):
        raise RuntimeError("nmcli is not installed on this Raspberry Pi.")
    return path


def run_nmcli(args: list[str], timeout_s: float) -> subprocess.CompletedProcess[str]:
    try:
        result = subprocess.run(
            [nmcli_path(), *args],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout_s,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError("Wi-Fi helper timed out while talking to NetworkManager.") from exc
    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "").strip() or "Wi-Fi helper command failed."
        raise RuntimeError(detail)
    return result


def read_payload() -> dict:
    raw = sys.stdin.read()
    if not raw.strip():
        return {}
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise RuntimeError("Wi-Fi helper received invalid JSON input.") from exc
    if not isinstance(payload, dict):
        raise RuntimeError("Wi-Fi helper expected a JSON object payload.")
    return payload


def remove_profile_if_present(profile_id: str) -> None:
    if not profile_id:
        return
    result = subprocess.run(
        [nmcli_path(), "connection", "show", "id", profile_id],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=6,
        check=False,
    )
    if result.returncode == 0:
        run_nmcli(["connection", "delete", "id", profile_id], 8)


def connect_network(payload: dict) -> int:
    ssid = str(payload.get("ssid") or "").strip()
    password = str(payload.get("password") or "")
    device = str(payload.get("device") or "").strip()
    requires_password = bool(payload.get("requires_password"))
    profile_id = str(payload.get("profile_id") or "").strip()
    if not ssid:
        return fail("Wi-Fi helper requires an SSID.")
    if requires_password and not password.strip():
        return fail("Wi-Fi helper requires a password for secured networks.")
    if not profile_id:
        return fail("Wi-Fi helper requires a managed profile id.")

    remove_profile_if_present(profile_id)

    add_args = ["connection", "add", "type", "wifi", "con-name", profile_id, "ssid", ssid]
    if device:
        add_args.extend(["ifname", device])
    run_nmcli(add_args, 8)

    modify_args = [
        "connection",
        "modify",
        profile_id,
        "connection.autoconnect",
        "yes",
        "802-11-wireless.mode",
        "infrastructure",
        "ipv4.method",
        "auto",
        "ipv6.method",
        "auto",
    ]
    if device:
        modify_args.extend(["connection.interface-name", device])
    if requires_password:
        modify_args.extend(
            [
                "802-11-wireless-security.key-mgmt",
                "wpa-psk",
                "802-11-wireless-security.psk",
                password,
            ]
        )
    run_nmcli(modify_args, 8)

    up_args = ["connection", "up", "id", profile_id]
    if device:
        up_args.extend(["ifname", device])
    run_nmcli(up_args, DEFAULT_TIMEOUT_S)
    return 0


def main() -> int:
    if os.geteuid() != 0:
        return fail("Wi-Fi helper must run as root.")
    if len(sys.argv) < 2:
        return fail("Wi-Fi helper action is required.")
    action = str(sys.argv[1]).strip().lower()
    payload = read_payload()
    try:
        if action == "connect":
            return connect_network(payload)
        return fail(f"Unsupported Wi-Fi helper action: {action}")
    except RuntimeError as exc:
        return fail(str(exc))


if __name__ == "__main__":
    raise SystemExit(main())
