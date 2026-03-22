import importlib.util
import os
import tempfile
import time
import unittest
import uuid
from pathlib import Path

from fastapi.testclient import TestClient


ROOT = Path(__file__).resolve().parent


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
        self.temp_dir_ctx = tempfile.TemporaryDirectory()
        self.temp_dir = self.temp_dir_ctx.name
        self.module = load_server_module(self.temp_dir)
        self.client = TestClient(self.module.app)

    def tearDown(self):
        self.client.close()
        self.temp_dir_ctx.cleanup()

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

        bag = self.client.post("/bags", json={"name": "Family Bag", "bag_type": "custom"})
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
        bag = self.client.post("/bags", json={"name": "Medic Bag", "bag_type": "custom"})
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


if __name__ == "__main__":
    unittest.main()
