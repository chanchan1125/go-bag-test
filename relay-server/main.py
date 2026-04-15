import json
import logging
import os
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from hashlib import sha256
from typing import Any, Iterator, Literal, Optional

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.getenv("GOBAG_RELAY_DATA_DIR", os.path.join(BASE_DIR, "data"))
DB_PATH = os.path.join(DATA_DIR, "relay.db")
BOOTSTRAP_SECRET = os.getenv("GOBAG_RELAY_BOOTSTRAP_SECRET", "").strip()
REQUEST_TIMEOUT_MS = max(int(os.getenv("GOBAG_RELAY_REQUEST_TIMEOUT_MS", "25000")), 1000)
REQUEST_LEASE_MS = max(int(os.getenv("GOBAG_RELAY_REQUEST_LEASE_MS", "30000")), 1000)
PI_ONLINE_WINDOW_MS = max(int(os.getenv("GOBAG_RELAY_PI_ONLINE_WINDOW_MS", "45000")), 5000)
DEFAULT_POLL_TIMEOUT_MS = max(int(os.getenv("GOBAG_RELAY_DEFAULT_POLL_TIMEOUT_MS", "20000")), 1000)
REQUEST_RETENTION_MS = max(int(os.getenv("GOBAG_RELAY_REQUEST_RETENTION_MS", "3600000")), 60000)

app = FastAPI(title="Go-Bag Relay", version="0.1.0")
logger = logging.getLogger("gobag.relay")
if not logging.getLogger().handlers:
    logging.basicConfig(level=os.getenv("GOBAG_RELAY_LOG_LEVEL", "INFO").upper())

_db_lock = threading.Lock()


class PiPollRequest(BaseModel):
    pi_device_id: str
    device_secret: str
    bootstrap_secret: str = ""
    device_name: str = ""
    local_base_url: str = ""
    remote_base_url: str = ""
    poll_timeout_ms: int = Field(default=DEFAULT_POLL_TIMEOUT_MS, ge=0, le=60000)


class RelayWorkItem(BaseModel):
    request_id: str
    kind: Literal["device_status", "sync_status", "sync"]
    authorization: str
    payload: dict[str, Any] = Field(default_factory=dict)


class PiPollResponse(BaseModel):
    request: Optional[RelayWorkItem] = None


class PiRespondRequest(BaseModel):
    pi_device_id: str
    device_secret: str
    request_id: str
    status_code: int = Field(ge=100, le=599)
    response_body: Any = None


class PresenceResponse(BaseModel):
    pi_device_id: str
    online: bool
    last_seen_at: int
    device_name: str
    local_base_url: str
    remote_base_url: str


@contextmanager
def db_conn() -> Iterator[sqlite3.Connection]:
    with _db_lock:
        os.makedirs(DATA_DIR, exist_ok=True)
        conn = sqlite3.connect(DB_PATH, timeout=30, isolation_level=None)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
        finally:
            conn.close()


def now_ms() -> int:
    return int(time.time() * 1000)


def hash_secret(raw_secret: str) -> str:
    return sha256(raw_secret.encode("utf-8")).hexdigest()


def init_db() -> None:
    with db_conn() as conn:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS devices (
              pi_device_id TEXT PRIMARY KEY,
              device_secret_hash TEXT NOT NULL,
              device_name TEXT NOT NULL,
              local_base_url TEXT NOT NULL,
              remote_base_url TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              last_seen_at INTEGER NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS relay_requests (
              request_id TEXT PRIMARY KEY,
              pi_device_id TEXT NOT NULL,
              kind TEXT NOT NULL,
              authorization TEXT NOT NULL,
              payload_json TEXT NOT NULL,
              status TEXT NOT NULL,
              status_code INTEGER NULL,
              response_json TEXT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              lease_expires_at INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        conn.commit()


def cleanup_old_requests(conn: sqlite3.Connection) -> None:
    cutoff = now_ms() - REQUEST_RETENTION_MS
    conn.execute("DELETE FROM relay_requests WHERE updated_at < ? AND status != 'pending'", (cutoff,))


def get_device_row(conn: sqlite3.Connection, pi_device_id: str) -> Optional[sqlite3.Row]:
    return conn.execute("SELECT * FROM devices WHERE pi_device_id = ?", (pi_device_id,)).fetchone()


def device_is_online(row: Optional[sqlite3.Row]) -> bool:
    return bool(row and (now_ms() - int(row["last_seen_at"])) <= PI_ONLINE_WINDOW_MS)


def authenticate_pi(conn: sqlite3.Connection, req: PiPollRequest | PiRespondRequest) -> sqlite3.Row:
    pi_device_id = req.pi_device_id.strip()
    if not pi_device_id or not req.device_secret.strip():
        raise HTTPException(status_code=400, detail="Pi identity is incomplete.")

    existing = get_device_row(conn, pi_device_id)
    hashed_secret = hash_secret(req.device_secret.strip())
    current_time = now_ms()

    if existing:
        if existing["device_secret_hash"] != hashed_secret:
            raise HTTPException(status_code=403, detail="Pi authentication failed.")
        conn.execute(
            """
            UPDATE devices
            SET updated_at = ?, last_seen_at = ?
            WHERE pi_device_id = ?
            """,
            (current_time, current_time, pi_device_id),
        )
    else:
        bootstrap_secret = getattr(req, "bootstrap_secret", "")
        if not BOOTSTRAP_SECRET or bootstrap_secret != BOOTSTRAP_SECRET:
            raise HTTPException(status_code=403, detail="Pi registration is not allowed.")
        conn.execute(
            """
            INSERT INTO devices(
              pi_device_id, device_secret_hash, device_name, local_base_url, remote_base_url,
              created_at, updated_at, last_seen_at
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                pi_device_id,
                hashed_secret,
                getattr(req, "device_name", "").strip(),
                getattr(req, "local_base_url", "").strip(),
                getattr(req, "remote_base_url", "").strip(),
                current_time,
                current_time,
                current_time,
            ),
        )

    if isinstance(req, PiPollRequest):
        conn.execute(
            """
            UPDATE devices
            SET device_name = ?, local_base_url = ?, remote_base_url = ?, updated_at = ?, last_seen_at = ?
            WHERE pi_device_id = ?
            """,
            (
                req.device_name.strip(),
                req.local_base_url.strip(),
                req.remote_base_url.strip(),
                current_time,
                current_time,
                pi_device_id,
            ),
        )
    conn.commit()
    row = get_device_row(conn, pi_device_id)
    if not row:
        raise HTTPException(status_code=500, detail="Relay could not load the Pi session.")
    return row


def claim_pending_request(conn: sqlite3.Connection, pi_device_id: str) -> Optional[RelayWorkItem]:
    current_time = now_ms()
    row = conn.execute(
        """
        SELECT * FROM relay_requests
        WHERE pi_device_id = ?
          AND status = 'pending'
          AND lease_expires_at <= ?
        ORDER BY created_at ASC
        LIMIT 1
        """,
        (pi_device_id, current_time),
    ).fetchone()
    if not row:
        return None

    conn.execute(
        """
        UPDATE relay_requests
        SET lease_expires_at = ?, updated_at = ?
        WHERE request_id = ?
        """,
        (current_time + REQUEST_LEASE_MS, current_time, row["request_id"]),
    )
    conn.commit()
    payload = json.loads(row["payload_json"] or "{}")
    if not isinstance(payload, dict):
        payload = {}
    return RelayWorkItem(
        request_id=row["request_id"],
        kind=row["kind"],
        authorization=row["authorization"],
        payload=payload,
    )


def enqueue_request(pi_device_id: str, kind: str, authorization: str, payload: dict[str, Any]) -> str:
    request_id = uuid.uuid4().hex
    current_time = now_ms()
    with db_conn() as conn:
        cleanup_old_requests(conn)
        row = get_device_row(conn, pi_device_id)
        if not device_is_online(row):
            raise HTTPException(status_code=503, detail="That Raspberry Pi is offline right now.")
        conn.execute(
            """
            INSERT INTO relay_requests(
              request_id, pi_device_id, kind, authorization, payload_json,
              status, status_code, response_json, created_at, updated_at, lease_expires_at
            ) VALUES(?, ?, ?, ?, ?, 'pending', NULL, NULL, ?, ?, 0)
            """,
            (request_id, pi_device_id, kind, authorization, json.dumps(payload), current_time, current_time),
        )
        conn.commit()
    return request_id


def wait_for_response(pi_device_id: str, request_id: str) -> JSONResponse:
    deadline = now_ms() + REQUEST_TIMEOUT_MS
    while now_ms() < deadline:
        with db_conn() as conn:
            row = conn.execute("SELECT * FROM relay_requests WHERE request_id = ? AND pi_device_id = ?", (request_id, pi_device_id)).fetchone()
            device = get_device_row(conn, pi_device_id)
            if not row:
                raise HTTPException(status_code=404, detail="Relay request was not found.")
            if row["status"] == "responded":
                body = json.loads(row["response_json"] or "null")
                return JSONResponse(status_code=int(row["status_code"] or 200), content=body)
            if row["status"] == "expired":
                raise HTTPException(status_code=504, detail="Remote request timed out.")
            if not device_is_online(device):
                conn.execute(
                    "UPDATE relay_requests SET status = 'expired', updated_at = ? WHERE request_id = ?",
                    (now_ms(), request_id),
                )
                conn.commit()
                raise HTTPException(status_code=503, detail="That Raspberry Pi went offline before it could respond.")
        time.sleep(0.15)

    with db_conn() as conn:
        conn.execute(
            "UPDATE relay_requests SET status = 'expired', updated_at = ? WHERE request_id = ?",
            (now_ms(), request_id),
        )
        conn.commit()
    raise HTTPException(status_code=504, detail="The Raspberry Pi took too long to respond.")


def require_phone_authorization(authorization: Optional[str]) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing bearer token.")
    return authorization


def proxy_request(pi_device_id: str, kind: Literal["device_status", "sync_status", "sync"], authorization: Optional[str], payload: dict[str, Any]) -> JSONResponse:
    request_id = enqueue_request(
        pi_device_id=pi_device_id.strip(),
        kind=kind,
        authorization=require_phone_authorization(authorization),
        payload=payload,
    )
    return wait_for_response(pi_device_id.strip(), request_id)


@app.on_event("startup")
def startup() -> None:
    init_db()


@app.get("/health")
def health() -> dict:
    with db_conn() as conn:
        online_count = conn.execute("SELECT COUNT(*) AS c FROM devices WHERE last_seen_at >= ?", (now_ms() - PI_ONLINE_WINDOW_MS,)).fetchone()["c"]
    return {
        "status": "ok",
        "online_devices": int(online_count),
        "request_timeout_ms": REQUEST_TIMEOUT_MS,
    }


@app.get("/v1/presence/{pi_device_id}", response_model=PresenceResponse)
def presence(pi_device_id: str) -> PresenceResponse:
    with db_conn() as conn:
        row = get_device_row(conn, pi_device_id.strip())
    if not row:
        raise HTTPException(status_code=404, detail="That Raspberry Pi is not registered with the relay.")
    return PresenceResponse(
        pi_device_id=row["pi_device_id"],
        online=device_is_online(row),
        last_seen_at=int(row["last_seen_at"]),
        device_name=row["device_name"],
        local_base_url=row["local_base_url"],
        remote_base_url=row["remote_base_url"],
    )


@app.post("/v1/pi/poll", response_model=PiPollResponse)
def pi_poll(req: PiPollRequest) -> PiPollResponse:
    timeout_ms = max(req.poll_timeout_ms, 0)
    deadline = now_ms() + timeout_ms
    with db_conn() as conn:
        authenticate_pi(conn, req)
        cleanup_old_requests(conn)

    while True:
        with db_conn() as conn:
            authenticate_pi(conn, req)
            work_item = claim_pending_request(conn, req.pi_device_id.strip())
        if work_item:
            return PiPollResponse(request=work_item)
        if timeout_ms <= 0 or now_ms() >= deadline:
            return PiPollResponse(request=None)
        time.sleep(0.4)


@app.post("/v1/pi/respond")
def pi_respond(req: PiRespondRequest) -> dict:
    with db_conn() as conn:
        authenticate_pi(conn, req)
        row = conn.execute(
            "SELECT request_id FROM relay_requests WHERE request_id = ? AND pi_device_id = ?",
            (req.request_id.strip(), req.pi_device_id.strip()),
        ).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Relay request was not found.")
        conn.execute(
            """
            UPDATE relay_requests
            SET status = 'responded', status_code = ?, response_json = ?, updated_at = ?, lease_expires_at = 0
            WHERE request_id = ?
            """,
            (req.status_code, json.dumps(req.response_body), now_ms(), req.request_id.strip()),
        )
        conn.commit()
    return {"ok": True}


@app.get("/r/{pi_device_id}/device/status")
def remote_device_status(pi_device_id: str, authorization: Optional[str] = Header(default=None)) -> JSONResponse:
    return proxy_request(pi_device_id, "device_status", authorization, {})


@app.get("/r/{pi_device_id}/sync/status")
def remote_sync_status(pi_device_id: str, authorization: Optional[str] = Header(default=None)) -> JSONResponse:
    return proxy_request(pi_device_id, "sync_status", authorization, {})


@app.post("/r/{pi_device_id}/sync")
def remote_sync(pi_device_id: str, payload: dict[str, Any], authorization: Optional[str] = Header(default=None)) -> JSONResponse:
    return proxy_request(pi_device_id, "sync", authorization, payload)
