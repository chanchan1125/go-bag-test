import importlib.util
import os
import shutil
import threading
import time
import unittest
import uuid
from pathlib import Path

from fastapi.testclient import TestClient


ROOT = Path(__file__).resolve().parent
TEST_TEMP_ROOT = ROOT.parent / ".tmp-relay-tests"


def load_relay_module(temp_dir: str):
    os.environ["GOBAG_RELAY_DATA_DIR"] = temp_dir
    os.environ["GOBAG_RELAY_BOOTSTRAP_SECRET"] = "bootstrap-secret"
    module_name = f"gobag_relay_main_{uuid.uuid4().hex}"
    spec = importlib.util.spec_from_file_location(module_name, ROOT / "main.py")
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class RelayApiTests(unittest.TestCase):
    def setUp(self):
        TEST_TEMP_ROOT.mkdir(parents=True, exist_ok=True)
        self.temp_dir = str(TEST_TEMP_ROOT / f"case-{uuid.uuid4().hex}")
        os.makedirs(self.temp_dir, exist_ok=True)
        self.module = load_relay_module(self.temp_dir)
        self.module.init_db()
        self.client = TestClient(self.module.app)

    def tearDown(self):
        self.client.close()
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def register_pi(self, pi_device_id: str = "pi-1") -> None:
        response = self.client.post(
            "/v1/pi/poll",
            json={
                "pi_device_id": pi_device_id,
                "device_secret": "device-secret",
                "bootstrap_secret": "bootstrap-secret",
                "device_name": "GO BAG Pi",
                "local_base_url": "http://192.168.1.20:8080",
                "remote_base_url": "https://relay.example.com/r/pi-1",
                "poll_timeout_ms": 0,
            },
        )
        self.assertEqual(response.status_code, 200)

    def test_health_and_presence_report_online_pi(self):
        self.register_pi()
        health = self.client.get("/health")
        self.assertEqual(health.status_code, 200)
        self.assertEqual(health.json()["status"], "ok")
        self.assertEqual(health.json()["online_devices"], 1)

        presence = self.client.get("/v1/presence/pi-1")
        self.assertEqual(presence.status_code, 200)
        self.assertTrue(presence.json()["online"])
        self.assertEqual(presence.json()["device_name"], "GO BAG Pi")

    def test_remote_request_requires_online_pi(self):
        response = self.client.get("/r/pi-missing/device/status", headers={"Authorization": "Bearer token"})
        self.assertEqual(response.status_code, 503)

    def test_remote_device_status_round_trip(self):
        self.register_pi()

        result_holder = {}

        def request_remote_status():
            result_holder["response"] = self.client.get(
                "/r/pi-1/device/status",
                headers={"Authorization": "Bearer paired-token"},
            )

        worker = threading.Thread(target=request_remote_status, daemon=True)
        worker.start()

        deadline = time.time() + 5
        poll_response = None
        while time.time() < deadline:
            poll_response = self.client.post(
                "/v1/pi/poll",
                json={
                    "pi_device_id": "pi-1",
                    "device_secret": "device-secret",
                    "bootstrap_secret": "bootstrap-secret",
                    "device_name": "GO BAG Pi",
                    "local_base_url": "http://192.168.1.20:8080",
                    "remote_base_url": "https://relay.example.com/r/pi-1",
                    "poll_timeout_ms": 0,
                },
            )
            self.assertEqual(poll_response.status_code, 200)
            if poll_response.json()["request"] is not None:
                break
            time.sleep(0.1)

        self.assertIsNotNone(poll_response)
        request_payload = poll_response.json()["request"]
        self.assertEqual(request_payload["kind"], "device_status")
        self.assertEqual(request_payload["authorization"], "Bearer paired-token")

        respond = self.client.post(
            "/v1/pi/respond",
            json={
                "pi_device_id": "pi-1",
                "device_secret": "device-secret",
                "request_id": request_payload["request_id"],
                "status_code": 200,
                "response_body": {
                    "id": "primary",
                    "device_name": "GO BAG Raspberry Pi",
                    "last_sync_at": 1000,
                    "connection_status": "paired",
                    "pending_changes_count": 0,
                    "local_ip": "192.168.1.20",
                    "local_base_url": "http://192.168.1.20:8080",
                    "remote_base_url": "https://relay.example.com/r/pi-1",
                    "updated_at": 1000,
                    "pi_device_id": "pi-1",
                    "pair_code": "123456",
                    "paired_devices": 1,
                    "database_path": "/opt/gobag/data/gobag.db",
                },
            },
        )
        self.assertEqual(respond.status_code, 200)
        worker.join(timeout=5)

        remote_response = result_holder["response"]
        self.assertEqual(remote_response.status_code, 200)
        self.assertEqual(remote_response.json()["pi_device_id"], "pi-1")


if __name__ == "__main__":
    unittest.main()
