import asyncio
import importlib.util
import os
import shutil
import subprocess
import time
import unittest
import uuid
from pathlib import Path
from unittest import mock

from fastapi.testclient import TestClient


ROOT = Path(__file__).resolve().parent
TEST_TEMP_ROOT = ROOT.parent / ".tmp-pi-tests"


def load_server_module(temp_dir: str):
    os.environ["GOBAG_DATA_DIR"] = temp_dir
    module_name = f"gobag_pi_main_{uuid.uuid4().hex}"
    spec = importlib.util.spec_from_file_location(module_name, ROOT / "main.py")
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class PiServerApiTests(unittest.TestCase):
    def setUp(self):
        TEST_TEMP_ROOT.mkdir(parents=True, exist_ok=True)
        self.temp_dir = str(TEST_TEMP_ROOT / f"case-{uuid.uuid4().hex}")
        os.makedirs(self.temp_dir, exist_ok=True)
        self.module = load_server_module(self.temp_dir)
        self.module.init_db()
        self.client = TestClient(self.module.app)

    def tearDown(self):
        self.client.close()
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_health_and_status_endpoints(self):
        health = self.client.get("/health")
        self.assertEqual(health.status_code, 200)
        self.assertEqual(health.json()["status"], "ok")

        device = self.client.get("/device/status")
        self.assertEqual(device.status_code, 200)
        payload = device.json()
        self.assertEqual(payload["device_name"], "GO BAG Raspberry Pi")
        self.assertIn("gobag.db", payload["database_path"])

        sync_status = self.client.get("/sync/status")
        self.assertEqual(sync_status.status_code, 200)
        self.assertIn("connection_status", sync_status.json())

    def test_bag_and_item_crud(self):
        categories = self.client.get("/categories")
        self.assertEqual(categories.status_code, 200)
        category_id = categories.json()[0]["id"]

        bag = self.client.post("/bags", json={"name": "Family Bag", "bag_type": "44l"})
        self.assertEqual(bag.status_code, 201)
        bag_id = bag.json()["id"]

        item = self.client.post(
            f"/bags/{bag_id}/items",
            json={
                "category_id": category_id,
                "name": "Bottled Water",
                "quantity": 6,
                "unit": "pcs",
                "packed_status": True,
                "essential": True,
                "expiry_date": "2026-12-31",
                "minimum_quantity": 3,
                "condition_status": "good",
                "notes": "Rotate monthly",
            },
        )
        self.assertEqual(item.status_code, 201)
        item_id = item.json()["id"]

        items = self.client.get(f"/bags/{bag_id}/items")
        self.assertEqual(items.status_code, 200)
        self.assertEqual(len(items.json()), 1)
        self.assertEqual(items.json()[0]["name"], "Bottled Water")

        updated = self.client.put(
            f"/items/{item_id}",
            json={
                "category_id": category_id,
                "name": "Bottled Water",
                "quantity": 4,
                "unit": "pcs",
                "packed_status": False,
                "essential": True,
                "expiry_date": "2026-12-31",
                "minimum_quantity": 2,
                "condition_status": "good",
                "notes": "Updated quantity",
            },
        )
        self.assertEqual(updated.status_code, 200)
        self.assertEqual(updated.json()["quantity"], 4)
        self.assertFalse(updated.json()["packed_status"])

        alerts = self.client.get("/alerts")
        self.assertEqual(alerts.status_code, 200)

        deleted = self.client.delete(f"/items/{item_id}")
        self.assertEqual(deleted.status_code, 200)

    def test_data_persists_after_reload(self):
        categories = self.client.get("/categories").json()
        bag = self.client.post("/bags", json={"name": "Reload Bag", "bag_type": "44l"}).json()
        self.client.post(
            f"/bags/{bag['id']}/items",
            json={
                "category_id": categories[0]["id"],
                "name": "Flashlight",
                "quantity": 1,
                "unit": "pcs",
                "packed_status": True,
                "essential": True,
                "expiry_date": None,
                "minimum_quantity": 1,
                "condition_status": "good",
                "notes": "",
            },
        )

        reloaded = load_server_module(self.temp_dir)
        with TestClient(reloaded.app) as client:
            bags = client.get("/bags")
            self.assertEqual(bags.status_code, 200)
            names = [row["name"] for row in bags.json()]
            self.assertIn("Reload Bag", names)

            stored_items = client.get(f"/bags/{bag['id']}/items")
            self.assertEqual(stored_items.status_code, 200)
            self.assertEqual(len(stored_items.json()), 1)

    def test_sync_accepts_items_with_missing_expiry_date_ms(self):
        device_status = self.client.get("/device/status")
        self.assertEqual(device_status.status_code, 200)

        pair = self.client.post(
            "/pair",
            json={
                "phone_device_id": "phone-sync-test",
                "pair_code": device_status.json()["pair_code"],
            },
        )
        self.assertEqual(pair.status_code, 200)
        token = pair.json()["auth_token"]

        bag_id = str(uuid.uuid4())
        item_id = str(uuid.uuid4())
        sync = self.client.post(
            "/sync",
            headers={"Authorization": f"Bearer {token}"},
            json={
                "phone_device_id": "phone-sync-test",
                "last_sync_at": 0,
                "changed_bags": [
                    {
                        "bag_id": bag_id,
                        "name": "Sync Bag",
                        "size_liters": 44,
                        "template_id": "template_44l",
                        "updated_at": 1000,
                        "updated_by": "phone-sync-test",
                    }
                ],
                "changed_items": [
                    {
                        "id": item_id,
                        "bag_id": bag_id,
                        "name": "Bandage Roll",
                        "category": "Medical & Health",
                        "quantity": 2,
                        "unit": "pcs",
                        "packed_status": True,
                        "notes": "",
                        "deleted": False,
                        "updated_at": 1001,
                        "updated_by": "phone-sync-test",
                    }
                ],
            },
        )
        self.assertEqual(sync.status_code, 200)

        items = self.client.get(f"/bags/{bag_id}/items")
        self.assertEqual(items.status_code, 200)
        self.assertEqual(len(items.json()), 1)
        self.assertEqual(items.json()[0]["name"], "Bandage Roll")

    def test_alerts_include_item_bag_and_expiry_details(self):
        category_id = self.client.get("/categories").json()[0]["id"]
        bag = self.client.post("/bags", json={"name": "Medic Bag", "bag_type": "44l"})
        self.assertEqual(bag.status_code, 201)
        bag_id = bag.json()["id"]

        tomorrow = time.strftime("%Y-%m-%d", time.gmtime((self.module.now_ms() + self.module.DAY_MS) / 1000))
        item = self.client.post(
            f"/bags/{bag_id}/items",
            json={
                "category_id": category_id,
                "name": "Bandage Roll",
                "quantity": 2,
                "unit": "pcs",
                "packed_status": True,
                "essential": True,
                "expiry_date": tomorrow,
                "minimum_quantity": 1,
                "condition_status": "good",
                "notes": "",
            },
        )
        self.assertEqual(item.status_code, 201)

        alerts = self.client.get("/alerts")
        self.assertEqual(alerts.status_code, 200)
        payload = alerts.json()
        self.assertEqual(len(payload), 1)
        self.assertEqual(payload[0]["bag_id"], bag_id)
        self.assertEqual(payload[0]["bag_name"], "Medic Bag")
        self.assertEqual(payload[0]["item_name"], "Bandage Roll")
        self.assertEqual(payload[0]["type"], "expiring_soon")
        self.assertIsInstance(payload[0]["expiry_date_ms"], int)

    def test_manual_add_merges_same_batch_and_preserves_separate_expiry_batches(self):
        category_id = self.client.get("/categories").json()[0]["id"]
        bag = self.client.post("/bags", json={"name": "Merge Bag", "bag_type": "25l"})
        self.assertEqual(bag.status_code, 201)
        bag_id = bag.json()["id"]

        first = self.client.post(
            f"/bags/{bag_id}/items",
            json={
                "category_id": category_id,
                "name": "Canned Tuna",
                "quantity": 2,
                "unit": "1 can",
                "packed_status": False,
                "expiry_date": "2026-10-01",
                "notes": "",
            },
        )
        self.assertEqual(first.status_code, 201)

        merged = self.client.post(
            f"/bags/{bag_id}/items",
            json={
                "category_id": category_id,
                "name": "Canned Tuna",
                "quantity": 3,
                "unit": "1 can",
                "packed_status": False,
                "expiry_date": "2026-10-01",
                "notes": "",
            },
        )
        self.assertEqual(merged.status_code, 201)
        self.assertEqual(merged.json()["quantity"], 5)
        self.assertEqual(merged.json()["id"], first.json()["id"])

        second_batch = self.client.post(
            f"/bags/{bag_id}/items",
            json={
                "category_id": category_id,
                "name": "Canned Tuna",
                "quantity": 4,
                "unit": "1 can",
                "packed_status": False,
                "expiry_date": "2026-12-15",
                "notes": "",
            },
        )
        self.assertEqual(second_batch.status_code, 201)
        self.assertNotEqual(second_batch.json()["id"], first.json()["id"])

        items = self.client.get(f"/bags/{bag_id}/items")
        self.assertEqual(items.status_code, 200)
        payload = items.json()
        self.assertEqual(len(payload), 2)
        quantities = sorted(item["quantity"] for item in payload)
        self.assertEqual(quantities, [4, 5])

    def test_parse_item_qr_content_requires_expected_format(self):
        parsed = self.module.parse_item_qr_content("Water/6 bottles/Food/2026-12-31")
        self.assertEqual(parsed.name, "Water")
        self.assertEqual(parsed.unit, "6 bottles")
        self.assertEqual(parsed.category, "Water & Food")
        self.assertEqual(parsed.expiry_date, "2026-12-31")

        with self.assertRaises(self.module.HTTPException):
            self.module.parse_item_qr_content("bad-qr-value")

    def test_resolve_usb_camera_device_prefers_capture_capable_video_node(self):
        capability = self.module.UsbCameraCapability(
            device="/dev/video2",
            palette_sizes={"MJPG": [(640, 480)]},
            technical="capture formats detected",
        )
        with mock.patch.object(self.module, "usb_scan_available", return_value=True), mock.patch.object(
            self.module,
            "available_usb_capture_devices",
            return_value=["/dev/video2", "/dev/video0"],
        ), mock.patch.object(
            self.module,
            "read_usb_camera_capability",
            return_value=capability,
        ):
            device = self.module.resolve_usb_camera_device("CAMERA_PREVIEW_FAILED")
        self.assertEqual(device, "/dev/video2")

    def test_build_usb_capture_attempts_prefers_supported_palette(self):
        capability = self.module.UsbCameraCapability(
            device="/dev/video2",
            palette_sizes={"MJPG": [(640, 480)], "YUYV": [(800, 600)]},
            technical="capture formats detected",
        )
        with mock.patch.object(self.module, "read_usb_camera_capability", return_value=capability):
            attempts = self.module.build_usb_capture_attempts("preview", "/dev/video2", 640, 480)
        self.assertGreaterEqual(len(attempts), 2)
        self.assertEqual(attempts[0], (640, 480, "MJPG"))
        self.assertIn((640, 480, None), attempts)

    def test_available_usb_capture_devices_prefers_real_usb_video_capture_nodes(self):
        capabilities = {
            "/dev/video10": self.module.UsbCameraCapability(
                device="/dev/video10",
                palette_sizes={"H264": [(1920, 1080)]},
                technical="bcm codec",
                device_label="bcm2835-codec-decode",
                bus_info="platform:bcm2835-codec",
                is_video_capture=False,
                is_usb=False,
            ),
            "/dev/video2": self.module.UsbCameraCapability(
                device="/dev/video2",
                palette_sizes={"MJPG": [(640, 480)]},
                technical="usb camera",
                device_label="USB2.0 PC CAMERA",
                bus_info="usb-0000:01:00.0-1.2",
                is_video_capture=True,
                is_usb=True,
            ),
            "/dev/video3": self.module.UsbCameraCapability(
                device="/dev/video3",
                palette_sizes={"MJPG": [(640, 480)]},
                technical="usb camera alt node",
                device_label="USB2.0 PC CAMERA",
                bus_info="usb-0000:01:00.0-1.2",
                is_video_capture=True,
                is_usb=True,
            ),
        }
        with mock.patch.object(self.module, "usb_v4l2ctl_available", return_value=True), mock.patch.object(
            self.module,
            "available_usb_camera_devices",
            return_value=["/dev/video10", "/dev/video2", "/dev/video3"],
        ), mock.patch.object(
            self.module,
            "read_usb_camera_capability",
            side_effect=lambda device: capabilities[device],
        ):
            devices = self.module.available_usb_capture_devices()
        self.assertEqual(devices[:2], ["/dev/video2", "/dev/video3"])

    def test_home_dashboard_is_single_bag_ui_with_polling_and_pair_code_card(self):
        response = self.client.get("/")
        self.assertEqual(response.status_code, 200)
        body = response.text
        self.assertIn("Mission dashboard", body)
        self.assertIn("GO BAG", body)
        self.assertIn("Pair phone", body)
        self.assertIn("Pair code", body)
        self.assertIn("height: 100dvh", body)
        self.assertIn("object-fit: cover", body)
        self.assertIn('id="scan-preview-image"', body)
        self.assertIn('id="scan-preview-placeholder"', body)
        self.assertIn("pollScanSessionStatus", body)
        self.assertIn("stopScanSession", body)
        self.assertIn("withCacheBust", body)
        self.assertIn("/camera/usb/session/start", body)
        self.assertIn("/camera/usb/session/status", body)
        self.assertIn("/camera/usb/session/frame.jpg", body)
        self.assertIn("/camera/usb/scan/decoded", body)
        self.assertIn('fetch("/ui/state"', body)
        self.assertIn('id="touch-keyboard"', body)
        self.assertIn('id="touch-keyboard-dock"', body)
        self.assertIn("gobag-pi-ui-state", body)
        self.assertIn('id="zoom-out"', body)
        self.assertIn('id="zoom-in"', body)
        self.assertIn('id="zoom-indicator"', body)
        self.assertIn('id="zoom-indicator">0%</span>', body)
        self.assertIn('id="power-button"', body)
        self.assertIn('id="theme-toggle"', body)
        self.assertIn('id="wifi-button"', body)
        self.assertIn('id="wifi-modal"', body)
        self.assertIn('id="wifi-network-list"', body)
        self.assertIn('id="wifi-entry-panel-host"', body)
        self.assertIn('id="wifi-entry-panel"', body)
        self.assertIn('id="wifi-password-input"', body)
        self.assertIn('id="wifi-password-toggle"', body)
        self.assertIn('id="wifi-close-button"', body)
        self.assertIn('data-keyboard-submit-target="wifi-connect-submit"', body)
        self.assertIn("--bottom-nav-reveal: 0;", body)
        self.assertIn("syncBottomNavReveal", body)
        self.assertIn("showBottomNavWhileScrolling", body)
        self.assertIn("scheduleBottomNavHide", body)
        self.assertIn('id="settings-preferences-form"', body)
        self.assertIn("Appearance", body)
        self.assertIn("Alerts & reminders", body)
        self.assertIn("Sync behavior", body)
        self.assertIn("Date & time", body)
        self.assertIn("System actions", body)
        self.assertIn('id="settings-manual-sync-button"', body)
        self.assertIn('id="settings-refresh-clock-button"', body)
        self.assertIn('id="settings-restart-button"', body)
        self.assertIn('id="settings-shutdown-button"', body)
        self.assertIn('name="high_contrast"', body)
        self.assertIn('name="large_text"', body)
        self.assertIn('name="low_stock_alerts"', body)
        self.assertIn('name="reminder_interval_minutes"', body)
        self.assertIn("window.scrollTo({ top, left: 0, behavior: \"auto\" })", body)
        self.assertIn("data-keyboard-action=\"done\"", body)
        self.assertIn(".hero-shell {", body)
        self.assertIn("input::placeholder", body)
        self.assertIn("option:checked", body)
        self.assertIn("kiosk-cursor-hidden", body)
        self.assertIn('document.documentElement.dataset.uiScale = "0";', body)
        self.assertNotIn('http-equiv="refresh"', body)
        self.assertNotIn('panel-title">Bags<', body)
        self.assertNotIn('"pair_code"', body)
        self.assertNotIn('data-ui-scale-option=', body)
        self.assertNotIn("GO BAG Command Center", body)
        self.assertNotIn("Quick actions", body)
        self.assertNotIn("Display controls", body)
        self.assertNotIn('id="wifi-cancel-button"', body)
        self.assertNotIn('id="wifi-rescan-button"', body)
        self.assertNotIn('id="topbar-readiness-value"', body)
        self.assertNotIn('id="topbar-sync-pill"', body)
        self.assertNotIn('aria-label="Open settings"', body)
        self.assertIn("scan-close-button", body)
        self.assertNotIn("scan-helper", body)
        self.assertIn("scan-viewfinder", body)
        self.assertNotIn("Live scan active", body)
        self.assertNotIn("Align the QR code inside the camera view.", body)
        self.assertNotIn("Scan now", body)
        self.assertNotIn("Refresh preview", body)

    def test_wifi_status_and_connect_endpoints_return_structured_payloads(self):
        status_payload = {
            "ok": True,
            "available": True,
            "status": "connected",
            "connected": True,
            "radio_enabled": True,
            "ssid": "FieldNet",
            "signal": 78,
            "device": "wlan0",
            "message": "Connected to FieldNet.",
        }
        networks_payload = {
            **status_payload,
            "networks": [
                {
                    "ssid": "FieldNet",
                    "active": True,
                    "signal": 78,
                    "security": "WPA2",
                    "requires_password": True,
                },
                {
                    "ssid": "OpenTent",
                    "active": False,
                    "signal": 52,
                    "security": "Open",
                    "requires_password": False,
                },
            ],
        }
        connect_payload = {
            **networks_payload,
            "message": "Connected to FieldNet.",
        }

        with mock.patch.object(self.module, "wifi_status_payload", return_value=status_payload):
            status = self.client.get("/ui/wifi/status")
        self.assertEqual(status.status_code, 200)
        self.assertTrue(status.json()["connected"])
        self.assertEqual(status.json()["ssid"], "FieldNet")

        with mock.patch.object(self.module, "wifi_networks_payload", return_value=networks_payload):
            networks = self.client.get("/ui/wifi/networks")
        self.assertEqual(networks.status_code, 200)
        self.assertEqual(len(networks.json()["networks"]), 2)
        self.assertTrue(networks.json()["networks"][0]["active"])

        with mock.patch.object(self.module, "connect_wifi_network", return_value=connect_payload):
            connect = self.client.post("/ui/wifi/connect", data={"ssid": "FieldNet", "password": "camp-secret"})
        self.assertEqual(connect.status_code, 200)
        self.assertEqual(connect.json()["message"], "Connected to FieldNet.")
        self.assertEqual(connect.json()["networks"][1]["ssid"], "OpenTent")

    def test_wifi_connect_endpoint_returns_retryable_error_payload(self):
        failure_payload = {
            "ok": False,
            "available": True,
            "status": "disconnected",
            "connected": False,
            "radio_enabled": True,
            "ssid": "",
            "signal": 0,
            "device": "wlan0",
            "message": "Incorrect Wi-Fi password. Try again.",
            "networks": [
                {
                    "ssid": "FieldNet",
                    "active": False,
                    "signal": 78,
                    "security": "WPA2",
                    "requires_password": True,
                }
            ],
        }

        with mock.patch.object(
            self.module,
            "connect_wifi_network",
            side_effect=self.module.HTTPException(status_code=400, detail="Incorrect Wi-Fi password. Try again."),
        ), mock.patch.object(self.module, "wifi_networks_payload", return_value=failure_payload):
            connect = self.client.post("/ui/wifi/connect", data={"ssid": "FieldNet", "password": "bad-pass"})

        self.assertEqual(connect.status_code, 400)
        self.assertFalse(connect.json()["ok"])
        self.assertEqual(connect.json()["message"], "Incorrect Wi-Fi password. Try again.")
        self.assertEqual(connect.json()["networks"][0]["ssid"], "FieldNet")

    def test_connect_wifi_network_normalizes_wrong_password_errors(self):
        network_payload = [
            {
                "ssid": "FieldNet",
                "active": False,
                "signal": 78,
                "security": "WPA2",
                "requires_password": True,
            }
        ]

        with mock.patch.object(self.module, "wifi_command_available", return_value=True), mock.patch.object(
            self.module, "list_wifi_networks", return_value=network_payload
        ), mock.patch.object(self.module, "current_wifi_device_name", return_value="wlan0"), mock.patch.object(
            self.module,
            "run_wifi_command_result",
            return_value=subprocess.CompletedProcess(["nmcli"], 10, "", "Error: unknown connection"),
        ), mock.patch.object(
            self.module,
            "run_wifi_command",
            side_effect=RuntimeError("Error: Connection activation failed: Secrets were required, but not provided."),
        ):
            with self.assertRaises(self.module.HTTPException) as captured:
                self.module.connect_wifi_network("FieldNet", "bad-pass")

        self.assertEqual(captured.exception.status_code, 400)
        self.assertEqual(captured.exception.detail, "Incorrect Wi-Fi password. Try again.")

    def test_connect_wifi_network_creates_secured_profile_with_wpa_psk(self):
        network_payload = [
            {
                "ssid": "FieldNet",
                "active": False,
                "signal": 78,
                "security": "WPA2",
                "requires_password": True,
            }
        ]
        success_payload = {
            "ok": True,
            "available": True,
            "status": "connected",
            "connected": True,
            "radio_enabled": True,
            "ssid": "FieldNet",
            "signal": 78,
            "device": "wlan0",
            "message": "Connected to FieldNet.",
            "networks": network_payload,
        }
        command_calls = []

        def fake_run(args, timeout_s):
            command_calls.append(list(args))
            return subprocess.CompletedProcess(["nmcli", *args], 0, "", "")

        with mock.patch.object(self.module, "wifi_command_available", return_value=True), mock.patch.object(
            self.module, "list_wifi_networks", return_value=network_payload
        ), mock.patch.object(self.module, "current_wifi_device_name", return_value="wlan0"), mock.patch.object(
            self.module,
            "run_wifi_command_result",
            return_value=subprocess.CompletedProcess(["nmcli"], 10, "", "Error: unknown connection"),
        ), mock.patch.object(self.module, "run_wifi_command", side_effect=fake_run), mock.patch.object(
            self.module, "wifi_networks_payload", return_value=success_payload
        ):
            payload = self.module.connect_wifi_network("FieldNet", "camp-secret")

        profile_id = self.module.managed_wifi_connection_id("FieldNet")
        self.assertEqual(payload["message"], "Connected to FieldNet.")
        self.assertEqual(command_calls[0], ["connection", "add", "type", "wifi", "con-name", profile_id, "ssid", "FieldNet", "ifname", "wlan0"])
        self.assertIn("802-11-wireless-security.key-mgmt", command_calls[1])
        self.assertIn("wpa-psk", command_calls[1])
        self.assertIn("802-11-wireless-security.psk", command_calls[1])
        self.assertIn("camp-secret", command_calls[1])
        self.assertEqual(command_calls[2], ["connection", "up", "id", profile_id, "ifname", "wlan0"])

    def test_connect_wifi_network_creates_open_profile_without_security_fields(self):
        network_payload = [
            {
                "ssid": "OpenTent",
                "active": False,
                "signal": 52,
                "security": "Open",
                "requires_password": False,
            }
        ]
        success_payload = {
            "ok": True,
            "available": True,
            "status": "connected",
            "connected": True,
            "radio_enabled": True,
            "ssid": "OpenTent",
            "signal": 52,
            "device": "wlan0",
            "message": "Connected to OpenTent.",
            "networks": network_payload,
        }
        command_calls = []

        def fake_run(args, timeout_s):
            command_calls.append(list(args))
            return subprocess.CompletedProcess(["nmcli", *args], 0, "", "")

        with mock.patch.object(self.module, "wifi_command_available", return_value=True), mock.patch.object(
            self.module, "list_wifi_networks", return_value=network_payload
        ), mock.patch.object(self.module, "current_wifi_device_name", return_value="wlan0"), mock.patch.object(
            self.module,
            "run_wifi_command_result",
            return_value=subprocess.CompletedProcess(["nmcli"], 10, "", "Error: unknown connection"),
        ), mock.patch.object(self.module, "run_wifi_command", side_effect=fake_run), mock.patch.object(
            self.module, "wifi_networks_payload", return_value=success_payload
        ):
            payload = self.module.connect_wifi_network("OpenTent", "")

        self.assertEqual(payload["message"], "Connected to OpenTent.")
        self.assertNotIn("802-11-wireless-security.key-mgmt", command_calls[1])
        self.assertNotIn("802-11-wireless-security.psk", command_calls[1])

    def test_connect_wifi_network_normalizes_missing_security_settings_errors(self):
        network_payload = [
            {
                "ssid": "FieldNet",
                "active": False,
                "signal": 78,
                "security": "WPA2",
                "requires_password": True,
            }
        ]

        def fake_run(args, timeout_s):
            if args[:3] == ["connection", "up", "id"]:
                raise RuntimeError("Error: 802-11-wireless-security.key-mgmt: property is missing")
            return subprocess.CompletedProcess(["nmcli", *args], 0, "", "")

        with mock.patch.object(self.module, "wifi_command_available", return_value=True), mock.patch.object(
            self.module, "list_wifi_networks", return_value=network_payload
        ), mock.patch.object(self.module, "current_wifi_device_name", return_value="wlan0"), mock.patch.object(
            self.module,
            "run_wifi_command_result",
            return_value=subprocess.CompletedProcess(["nmcli"], 10, "", "Error: unknown connection"),
        ), mock.patch.object(self.module, "run_wifi_command", side_effect=fake_run):
            with self.assertRaises(self.module.HTTPException) as captured:
                self.module.connect_wifi_network("FieldNet", "camp-secret")

        self.assertEqual(captured.exception.status_code, 400)
        self.assertEqual(captured.exception.detail, "Security settings missing. Could not connect to Wi-Fi.")

    def test_connect_wifi_network_normalizes_permission_errors(self):
        network_payload = [
            {
                "ssid": "FieldNet",
                "active": False,
                "signal": 78,
                "security": "WPA2",
                "requires_password": True,
            }
        ]

        def fake_run(args, timeout_s):
            if args[:4] == ["connection", "add", "type", "wifi"]:
                raise RuntimeError("Error: Failed to add 'gobag-wifi-a1701003bd2c' connection: insufficient privileges")
            return subprocess.CompletedProcess(["nmcli", *args], 0, "", "")

        with mock.patch.object(self.module, "wifi_command_available", return_value=True), mock.patch.object(
            self.module, "list_wifi_networks", return_value=network_payload
        ), mock.patch.object(self.module, "current_wifi_device_name", return_value="wlan0"), mock.patch.object(
            self.module, "run_wifi_command_result", return_value=subprocess.CompletedProcess(["nmcli"], 10, "", "")
        ), mock.patch.object(self.module, "run_wifi_command", side_effect=fake_run):
            with self.assertRaises(self.module.HTTPException) as captured:
                self.module.connect_wifi_network("FieldNet", "camp-secret")

        self.assertEqual(captured.exception.status_code, 400)
        self.assertEqual(
            captured.exception.detail,
            "Wi-Fi save is blocked by Raspberry Pi permissions. Re-run pi-server/install.sh, then try again.",
        )

    def test_connect_wifi_network_reports_manual_setup_when_helper_is_missing(self):
        network_payload = [
            {
                "ssid": "FieldNet",
                "active": False,
                "signal": 78,
                "security": "WPA2",
                "requires_password": True,
            }
        ]

        with mock.patch.object(self.module, "wifi_command_available", return_value=True), mock.patch.object(
            self.module, "list_wifi_networks", return_value=network_payload
        ), mock.patch.object(self.module, "current_wifi_device_name", return_value="wlan0"), mock.patch.object(
            self.module, "wifi_connect_should_use_helper", return_value=True
        ), mock.patch.object(
            self.module,
            "run_wifi_connect_helper",
            side_effect=PermissionError("Wi-Fi permission is not installed yet. Re-run pi-server/install.sh to enable kiosk Wi-Fi controls."),
        ):
            with self.assertRaises(self.module.HTTPException) as captured:
                self.module.connect_wifi_network("FieldNet", "camp-secret")

        self.assertEqual(captured.exception.status_code, 400)
        self.assertEqual(
            captured.exception.detail,
            "Wi-Fi setup requires Raspberry Pi admin setup. Re-run pi-server/install.sh, then try again.",
        )

    def test_connect_wifi_network_uses_privileged_helper_when_available(self):
        network_payload = [
            {
                "ssid": "FieldNet",
                "active": False,
                "signal": 78,
                "security": "WPA2",
                "requires_password": True,
            }
        ]
        success_payload = {
            "ok": True,
            "available": True,
            "status": "connected",
            "connected": True,
            "radio_enabled": True,
            "ssid": "FieldNet",
            "signal": 78,
            "device": "wlan0",
            "message": "Connected to FieldNet.",
            "networks": network_payload,
        }

        with mock.patch.object(self.module, "wifi_command_available", return_value=True), mock.patch.object(
            self.module, "list_wifi_networks", return_value=network_payload
        ), mock.patch.object(self.module, "current_wifi_device_name", return_value="wlan0"), mock.patch.object(
            self.module, "wifi_connect_should_use_helper", return_value=True
        ), mock.patch.object(self.module, "run_wifi_connect_helper") as helper_mock, mock.patch.object(
            self.module, "wifi_networks_payload", return_value=success_payload
        ):
            payload = self.module.connect_wifi_network("FieldNet", "camp-secret")

        self.assertEqual(payload["message"], "Connected to FieldNet.")
        helper_mock.assert_called_once_with(
            ssid="FieldNet",
            password="camp-secret",
            wifi_device_name="wlan0",
            requires_password=True,
        )

    def test_usb_camera_session_endpoints_return_structured_state(self):
        bag_id = self.client.get("/bags").json()[0]["id"]
        session_payload = {
            "ok": True,
            "session_id": "session-1",
            "device": "/dev/video0",
            "frame_url": "/camera/usb/session/frame.jpg",
            "frame_id": 3,
            "active": True,
            "running": True,
            "started_at": 100,
            "last_frame_at": 120,
            "decoded_content": "",
            "decoded_at": 0,
            "interval_ms": 850,
            "resolution": {"width": 640, "height": 480},
            "technical": "latest frame 640x480",
        }
        with mock.patch.object(self.module.usb_camera_session_manager, "start", return_value=session_payload):
            started = self.client.post("/camera/usb/session/start", data={"bag_id": bag_id})
        self.assertEqual(started.status_code, 200)
        self.assertTrue(started.json()["ok"])
        self.assertEqual(started.json()["session_id"], "session-1")

        with mock.patch.object(self.module.usb_camera_session_manager, "snapshot", return_value=session_payload):
            status = self.client.get("/camera/usb/session/status")
        self.assertEqual(status.status_code, 200)
        self.assertEqual(status.json()["frame_id"], 3)
        self.assertEqual(status.json()["frame_url"], "/camera/usb/session/frame.jpg")

        with mock.patch.object(
            self.module.usb_camera_session_manager,
            "frame_response",
            return_value=self.module.Response(content=b"jpegdata", media_type="image/jpeg"),
        ):
            frame = self.client.get("/camera/usb/session/frame.jpg")
        self.assertEqual(frame.status_code, 200)
        self.assertEqual(frame.content, b"jpegdata")

        with mock.patch.object(self.module.usb_camera_session_manager, "stop") as mocked_stop:
            stopped = self.client.post("/camera/usb/session/stop")
        self.assertEqual(stopped.status_code, 200)
        mocked_stop.assert_called_once()

    def test_ui_form_fallback_parses_urlencoded_without_python_multipart(self):
        class FakeRequest:
            headers = {"content-type": "application/x-www-form-urlencoded"}

            async def form(self):
                raise AssertionError("The `python-multipart` library must be installed to use form parsing.")

            async def body(self):
                return b"bag_type=66l&name=Field+Bag&notes=ready"

        payload = asyncio.run(self.module.read_ui_form(FakeRequest()))
        self.assertEqual(payload["bag_type"], "66l")
        self.assertEqual(payload["name"], "Field Bag")
        self.assertEqual(payload["notes"], "ready")

    def test_ui_bag_settings_save_updates_single_local_bag(self):
        response = self.client.post("/ui/bag/settings", data={"bag_type": "66l"}, follow_redirects=False)
        self.assertEqual(response.status_code, 303)
        self.assertIn("Bag%20size%20set%20to%2066L", response.headers["location"])

        bag = self.client.get("/device/bag")
        self.assertEqual(bag.status_code, 200)
        self.assertEqual(bag.json()["bag_type"], "66l")

    def test_ui_preferences_save_persists_kiosk_preferences(self):
        response = self.client.post(
            "/ui/preferences",
            data={
                "brightness": "high",
                "high_contrast": "1",
                "large_text": "1",
                "large_buttons": "1",
                "expiry_alerts": "1",
                "low_stock_alerts": "1",
                "sync_notifications": "1",
                "reminder_interval_minutes": "30",
                "auto_sync": "1",
                "sync_on_startup": "1",
                "network_time": "1",
            },
            follow_redirects=False,
        )
        self.assertEqual(response.status_code, 303)
        self.assertIn("Settings%20updated", response.headers["location"])
        with self.module.db_conn() as conn:
            preferences = self.module.load_kiosk_preferences(conn)
        self.assertEqual(preferences["brightness"], "high")
        self.assertTrue(preferences["high_contrast"])
        self.assertTrue(preferences["large_text"])
        self.assertTrue(preferences["large_buttons"])
        self.assertTrue(preferences["expiry_alerts"])
        self.assertTrue(preferences["low_stock_alerts"])
        self.assertTrue(preferences["sync_notifications"])
        self.assertEqual(preferences["reminder_interval_minutes"], 30)
        self.assertTrue(preferences["auto_sync"])
        self.assertTrue(preferences["sync_on_startup"])
        self.assertFalse(preferences["sync_wifi_only"])
        self.assertTrue(preferences["network_time"])

    def test_ui_restart_system_returns_structured_status(self):
        with mock.patch.object(self.module, "require_restart_ready", return_value=["systemctl", "reboot"]), mock.patch.object(
            self.module, "trigger_safe_restart"
        ) as mocked_restart:
            response = self.client.post("/ui/system/restart")
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.json()["ok"])
        self.assertIn("Restart in progress", response.json()["message"])
        mocked_restart.assert_called_once()

    def test_ui_manual_add_saves_item_and_reports_validation_errors(self):
        bag_id = self.client.get("/bags").json()[0]["id"]
        category_id = self.client.get("/categories").json()[0]["id"]

        created = self.client.post(
            "/ui/items/save",
            data={
                "category_id": category_id,
                "name": "Water Pouch",
                "quantity": "2",
                "unit": "pcs",
                "notes": "Front pocket",
            },
            follow_redirects=False,
        )
        self.assertEqual(created.status_code, 303)
        self.assertIn("Inventory%20item%20saved", created.headers["location"])

        items = self.client.get(f"/bags/{bag_id}/items")
        self.assertEqual(items.status_code, 200)
        self.assertEqual(len(items.json()), 1)
        self.assertEqual(items.json()[0]["name"], "Water Pouch")

        invalid = self.client.post(
            "/ui/items/save",
            data={
                "category_id": category_id,
                "name": "Broken Quantity",
                "quantity": "zero",
                "unit": "pcs",
            },
            follow_redirects=False,
        )
        self.assertEqual(invalid.status_code, 303)
        self.assertIn("Quantity%20must%20be%20a%20valid%20number", invalid.headers["location"])

    def test_ui_usb_scan_redirects_with_item_or_helpful_error(self):
        bag_id = self.client.get("/bags").json()[0]["id"]

        with mock.patch.object(
            self.module,
            "perform_usb_scan_and_save",
            return_value={
                "ok": True,
                "code": "SCAN_SUCCESS",
                "message": "Scanned Thermal Blanket into inventory.",
                "bag_id": bag_id,
                "parsed": {
                    "name": "Thermal Blanket",
                    "unit": "pcs",
                    "category": "Tools & Protection",
                    "expiry_date": "2027-01-01",
                },
                "item": {
                    "id": "item-1",
                    "bag_id": bag_id,
                    "category_id": "tools_protection",
                    "name": "Thermal Blanket",
                    "quantity": 1,
                    "unit": "pcs",
                    "packed_status": False,
                    "essential": False,
                    "expiry_date": "2027-01-01",
                    "minimum_quantity": 0,
                    "condition_status": "good",
                    "notes": "Added from QR scan",
                    "created_at": 1,
                    "updated_at": 1,
                },
            },
        ):
            created = self.client.post("/ui/items/scan", data={"bag_id": bag_id}, follow_redirects=False)
        self.assertEqual(created.status_code, 303)
        self.assertIn("Scanned%20Thermal%20Blanket%20into%20inventory", created.headers["location"])

        with mock.patch.object(
            self.module,
            "perform_usb_scan_and_save",
            side_effect=self.module.HTTPException(
                status_code=400,
                detail=self.module.structured_error_detail(
                    "NO_QR_DETECTED",
                    "No QR code was detected in the captured image.",
                ),
            ),
        ):
            error = self.client.post("/ui/items/scan", data={"bag_id": bag_id}, follow_redirects=False)
        self.assertEqual(error.status_code, 303)
        self.assertIn("No%20QR%20code%20was%20detected%20in%20the%20captured%20image", error.headers["location"])
        self.assertIn("scan=1", error.headers["location"])

    def test_usb_preview_status_and_json_scan_return_structured_payloads(self):
        bag_id = self.client.get("/bags").json()[0]["id"]

        with mock.patch.object(self.module, "resolve_usb_camera_device", return_value="/dev/video0"), mock.patch.object(
            self.module,
            "usb_stream_available",
            return_value=True,
        ), mock.patch.object(
            self.module,
            "probe_usb_stream_profile",
            return_value=(True, ""),
        ):
            preview = self.client.get("/camera/usb/preview/status")
        self.assertEqual(preview.status_code, 200)
        self.assertTrue(preview.json()["ok"])
        self.assertEqual(preview.json()["device"], "/dev/video0")
        self.assertEqual(preview.json()["preview_mode"], "stream")
        self.assertIn("/camera/usb/stream.mjpg", preview.json()["preview_url"])
        self.assertIn("width=", preview.json()["preview_url"])
        self.assertTrue(preview.json()["auto_scan"])
        self.assertTrue(preview.json()["auto_close_on_success"])
        self.assertGreaterEqual(preview.json()["scan_interval_ms"], 900)
        self.assertGreaterEqual(preview.json()["stream_fps"], 2)

        with mock.patch.object(self.module, "resolve_usb_camera_device", return_value="/dev/video0"), mock.patch.object(
            self.module,
            "usb_stream_available",
            return_value=True,
        ), mock.patch.object(
            self.module,
            "probe_usb_stream_profile",
            return_value=(False, "unsupported profile"),
        ):
            snapshot_preview = self.client.get("/camera/usb/preview/status")
        self.assertEqual(snapshot_preview.status_code, 200)
        self.assertTrue(snapshot_preview.json()["ok"])
        self.assertEqual(snapshot_preview.json()["preview_mode"], "snapshot")
        self.assertEqual(snapshot_preview.json()["preview_url"], "/camera/usb/preview.jpg")
        self.assertEqual(snapshot_preview.json()["snapshot_preview_url"], "/camera/usb/preview.jpg")
        self.assertEqual(snapshot_preview.json()["stream_fps"], 0)

        with mock.patch.object(self.module, "usb_stream_available", return_value=True), mock.patch.object(
            self.module,
            "resolve_usb_camera_device",
            return_value="/dev/video0",
        ), mock.patch.object(
            self.module,
            "generate_usb_camera_stream",
            return_value=iter([b"--ffmpeg\r\nContent-Type: image/jpeg\r\n\r\njpegdata\r\n"]),
        ):
            stream = self.client.get("/camera/usb/stream.mjpg")
        self.assertEqual(stream.status_code, 200)
        self.assertIn("multipart/x-mixed-replace", stream.headers["content-type"])

        with mock.patch.object(
            self.module,
            "perform_usb_scan_and_save",
            return_value={
                "ok": True,
                "code": "SCAN_SUCCESS",
                "message": "Scanned Water Pouch into inventory.",
                "bag_id": bag_id,
                "parsed": {
                    "name": "Water Pouch",
                    "unit": "pcs",
                    "category": "Water & Food",
                    "expiry_date": "2027-06-01",
                },
                "item": {
                    "id": "item-2",
                    "bag_id": bag_id,
                    "category_id": "water_food",
                    "name": "Water Pouch",
                    "quantity": 1,
                    "unit": "pcs",
                    "packed_status": False,
                    "essential": False,
                    "expiry_date": "2027-06-01",
                    "minimum_quantity": 0,
                    "condition_status": "good",
                    "notes": "Added from QR scan",
                    "created_at": 1,
                    "updated_at": 1,
                },
            },
        ):
            scan = self.client.post("/camera/usb/scan", data={"bag_id": bag_id})
        self.assertEqual(scan.status_code, 200)
        self.assertTrue(scan.json()["ok"])
        self.assertEqual(scan.json()["parsed"]["name"], "Water Pouch")

        with mock.patch.object(
            self.module,
            "save_scanned_qr_content",
            return_value={
                "ok": True,
                "code": "SCAN_SUCCESS",
                "message": "Scanned Water Pouch into inventory.",
                "bag_id": bag_id,
                "raw_content": "Water Pouch/pcs/Water & Food/2027-06-01",
                "parsed": {
                    "name": "Water Pouch",
                    "unit": "pcs",
                    "category": "Water & Food",
                    "expiry_date": "2027-06-01",
                },
                "item": {
                    "id": "item-3",
                    "bag_id": bag_id,
                    "category_id": "water_food",
                    "name": "Water Pouch",
                    "quantity": 1,
                    "unit": "pcs",
                    "packed_status": False,
                    "essential": False,
                    "expiry_date": "2027-06-01",
                    "minimum_quantity": 0,
                    "condition_status": "good",
                    "notes": "Added from QR scan",
                    "created_at": 1,
                    "updated_at": 1,
                },
            },
        ):
            decoded_scan = self.client.post(
                "/camera/usb/scan/decoded",
                data={
                    "bag_id": bag_id,
                    "content": "Water Pouch/pcs/Water & Food/2027-06-01",
                },
            )
        self.assertEqual(decoded_scan.status_code, 200)
        self.assertTrue(decoded_scan.json()["ok"])
        self.assertEqual(decoded_scan.json()["parsed"]["name"], "Water Pouch")

        with mock.patch.object(
            self.module,
            "resolve_usb_camera_device",
            side_effect=self.module.HTTPException(
                status_code=503,
                detail=self.module.structured_error_detail(
                    "CAMERA_PREVIEW_FAILED",
                    "USB camera preview failed.",
                ),
            ),
        ):
            preview_error = self.client.get("/camera/usb/preview/status")
        self.assertEqual(preview_error.status_code, 503)
        self.assertEqual(preview_error.json()["code"], "CAMERA_PREVIEW_FAILED")

        with mock.patch.object(
            self.module,
            "perform_usb_scan_and_save",
            side_effect=self.module.HTTPException(
                status_code=400,
                detail=self.module.structured_error_detail(
                    "INVALID_QR_CONTENT",
                    "A QR code was found, but it is not a valid GO BAG item QR.",
                ),
            ),
        ):
            scan_error = self.client.post("/camera/usb/scan", data={"bag_id": bag_id})
        self.assertEqual(scan_error.status_code, 400)
        self.assertEqual(scan_error.json()["code"], "INVALID_QR_CONTENT")
        self.assertFalse(scan_error.json()["ok"])

        with mock.patch.object(
            self.module,
            "save_scanned_qr_content",
            side_effect=self.module.HTTPException(
                status_code=400,
                detail=self.module.structured_error_detail(
                    "INVALID_QR_CONTENT",
                    "A QR code was found, but it is not a valid GO BAG item QR.",
                ),
            ),
        ):
            decoded_scan_error = self.client.post(
                "/camera/usb/scan/decoded",
                data={"bag_id": bag_id, "content": "not-a-gobag-qr"},
            )
        self.assertEqual(decoded_scan_error.status_code, 400)
        self.assertEqual(decoded_scan_error.json()["code"], "INVALID_QR_CONTENT")

    def test_single_bag_endpoints_keep_one_local_bag_and_update_size(self):
        initial = self.client.get("/bags")
        self.assertEqual(initial.status_code, 200)
        initial_payload = initial.json()
        self.assertEqual(len(initial_payload), 1)
        bag_id = initial_payload[0]["id"]

        created = self.client.post("/bags", json={"name": "Field Bag", "bag_type": "25l"})
        self.assertEqual(created.status_code, 201)
        self.assertEqual(created.json()["id"], bag_id)
        self.assertEqual(created.json()["bag_type"], "25l")

        updated = self.client.put("/device/bag", json={"name": "Field Bag", "bag_type": "66l", "last_checked_at": None})
        self.assertEqual(updated.status_code, 200)
        self.assertEqual(updated.json()["id"], bag_id)
        self.assertEqual(updated.json()["bag_type"], "66l")

        bags = self.client.get("/bags")
        self.assertEqual(bags.status_code, 200)
        payload = bags.json()
        self.assertEqual(len(payload), 1)
        self.assertEqual(payload[0]["id"], bag_id)
        self.assertEqual(payload[0]["bag_type"], "66l")

    def test_ui_state_reflects_synced_item_changes_for_kiosk_refresh(self):
        device_status = self.client.get("/device/status")
        self.assertEqual(device_status.status_code, 200)

        initial_bag = self.client.get("/bags").json()[0]
        bag_id = initial_bag["id"]

        pair = self.client.post(
            "/pair",
            json={
                "phone_device_id": "phone-kiosk-refresh",
                "pair_code": device_status.json()["pair_code"],
            },
        )
        self.assertEqual(pair.status_code, 200)
        token = pair.json()["auth_token"]

        sync = self.client.post(
            "/sync",
            headers={"Authorization": f"Bearer {token}"},
            json={
                "phone_device_id": "phone-kiosk-refresh",
                "last_sync_at": 0,
                "changed_bags": [
                    {
                        "bag_id": bag_id,
                        "name": "Field Bag",
                        "size_liters": 44,
                        "template_id": "template_44l",
                        "updated_at": 1000,
                        "updated_by": "phone-kiosk-refresh",
                    }
                ],
                "changed_items": [
                    {
                        "id": str(uuid.uuid4()),
                        "bag_id": bag_id,
                        "name": "Emergency Blanket",
                        "category": "Tools & Protection",
                        "quantity": 1,
                        "unit": "pcs",
                        "packed_status": True,
                        "notes": "",
                        "deleted": False,
                        "updated_at": 1001,
                        "updated_by": "phone-kiosk-refresh",
                    }
                ],
            },
        )
        self.assertEqual(sync.status_code, 200)

        ui_state = self.client.get("/ui/state")
        self.assertEqual(ui_state.status_code, 200)
        payload = ui_state.json()
        self.assertIn("Emergency Blanket", payload["inventory_groups_html"])
        self.assertTrue(payload["state_version"])

    def test_ui_routes_redirect_instead_of_dumping_raw_json_or_500_errors(self):
        bag_id = self.client.get("/bags").json()[0]["id"]

        new_pair = self.client.post("/ui/pair-code/new", follow_redirects=False)
        self.assertEqual(new_pair.status_code, 303)
        self.assertIn("/?notice=", new_pair.headers["location"])

        class FakeRequest:
            async def form(self):
                return {"bag_id": bag_id}

        with mock.patch.object(self.module, "perform_usb_scan_and_save", side_effect=RuntimeError("camera offline")):
            scan = asyncio.run(self.module.ui_scan_item(FakeRequest()))
        self.assertEqual(scan.status_code, 303)
        self.assertIn("USB%20camera%20scan%20failed%20unexpectedly", scan.headers["location"])
        self.assertIn("scan=1", scan.headers["location"])


if __name__ == "__main__":
    unittest.main()
