import asyncio
import importlib.util
import os
import shutil
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

    def test_home_dashboard_is_single_bag_ui_with_polling_and_pair_code_card(self):
        response = self.client.get("/")
        self.assertEqual(response.status_code, 200)
        body = response.text
        self.assertIn("Bag Settings", body)
        self.assertIn("Pairing QR", body)
        self.assertIn("Pair code", body)
        self.assertIn("USB Camera Scanner", body)
        self.assertIn("Auto-detect active while this screen is open", body)
        self.assertIn("height: 100vh", body)
        self.assertIn("startAutoScanLoop", body)
        self.assertIn("USB camera stream is ready at about", body)
        self.assertIn("/camera/usb/preview/status", body)
        self.assertIn("/camera/usb/scan", body)
        self.assertIn('fetch("/ui/state"', body)
        self.assertIn(".hero .panel {", body)
        self.assertIn("input::placeholder", body)
        self.assertIn("option:checked", body)
        self.assertNotIn('panel-title">Bags<', body)
        self.assertNotIn('"pair_code"', body)

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
        ):
            preview = self.client.get("/camera/usb/preview/status")
        self.assertEqual(preview.status_code, 200)
        self.assertTrue(preview.json()["ok"])
        self.assertEqual(preview.json()["device"], "/dev/video0")
        self.assertEqual(preview.json()["preview_mode"], "stream")
        self.assertEqual(preview.json()["preview_url"], "/camera/usb/stream.mjpg")
        self.assertTrue(preview.json()["auto_scan"])
        self.assertTrue(preview.json()["auto_close_on_success"])
        self.assertGreaterEqual(preview.json()["scan_interval_ms"], 1500)
        self.assertGreaterEqual(preview.json()["stream_fps"], 2)

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
