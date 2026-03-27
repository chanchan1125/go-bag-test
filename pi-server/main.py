import base64
import calendar
import glob
import io
import json
import logging
import os
import platform
import re
import secrets
import shlex
import shutil
import socket
import sqlite3
import subprocess
import tempfile
import threading
import time
import uuid
from contextlib import contextmanager
from dataclasses import dataclass, field
from html import escape
from typing import Dict, Iterator, List, Literal, Optional, Union
from urllib.parse import parse_qs, quote

import qrcode
from fastapi import BackgroundTasks, Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, Response, StreamingResponse
from PIL import Image, ImageEnhance, ImageFilter, ImageOps
from pydantic import BaseModel, Field

try:
    from pyzbar.pyzbar import ZBarSymbol, decode as pyzbar_decode
except Exception:
    ZBarSymbol = None
    pyzbar_decode = None

DEFAULT_DATA_DIR = "/var/lib/gobag"
DEVICE_NAME = os.getenv("GOBAG_DEVICE_NAME", "GO BAG Raspberry Pi")
HOST = os.getenv("GOBAG_HOST", "0.0.0.0")
PORT = int(os.getenv("GOBAG_PORT", "8080"))
ADMIN_TOKEN = os.getenv("GOBAG_ADMIN_TOKEN", "").strip()
PAIR_CODE_TTL_MS = 5 * 60 * 1000
DAY_MS = 24 * 60 * 60 * 1000
EXPIRING_SOON_DAYS = 7
CAMERA_CMD = os.getenv("GOBAG_CAMERA_CMD", "libcamera-still")
CAMERA_ENABLED = os.getenv("GOBAG_ENABLE_CAMERA", "1") == "1"
CAMERA_WIDTH = int(os.getenv("GOBAG_CAMERA_WIDTH", "1280"))
CAMERA_HEIGHT = int(os.getenv("GOBAG_CAMERA_HEIGHT", "720"))
CAMERA_WARMUP_MS = int(os.getenv("GOBAG_CAMERA_WARMUP_MS", "900"))
CAMERA_TIMEOUT_S = float(os.getenv("GOBAG_CAMERA_TIMEOUT_S", "12"))
USB_SCAN_CMD = os.getenv("GOBAG_USB_SCAN_CMD", "fswebcam")
USB_SCAN_DEVICE = os.getenv("GOBAG_USB_CAMERA_DEVICE", "").strip()
USB_V4L2CTL_CMD = os.getenv("GOBAG_USB_V4L2CTL_CMD", "v4l2-ctl")
USB_SCAN_WIDTH = int(os.getenv("GOBAG_USB_SCAN_WIDTH", "1280"))
USB_SCAN_HEIGHT = int(os.getenv("GOBAG_USB_SCAN_HEIGHT", "720"))
USB_SCAN_TIMEOUT_S = float(os.getenv("GOBAG_USB_SCAN_TIMEOUT_S", "12"))
USB_DEVICE_QUERY_TIMEOUT_S = float(os.getenv("GOBAG_USB_DEVICE_QUERY_TIMEOUT_S", "4"))
USB_PREVIEW_WIDTH = int(os.getenv("GOBAG_USB_PREVIEW_WIDTH", "960"))
USB_PREVIEW_HEIGHT = int(os.getenv("GOBAG_USB_PREVIEW_HEIGHT", "720"))
USB_PREVIEW_INTERVAL_MS = max(int(os.getenv("GOBAG_USB_PREVIEW_INTERVAL_MS", "1200")), 800)
USB_AUTO_SCAN_INTERVAL_MS = max(int(os.getenv("GOBAG_USB_AUTO_SCAN_INTERVAL_MS", "1200")), 900)
USB_SESSION_INTERVAL_MS = max(int(os.getenv("GOBAG_USB_SESSION_INTERVAL_MS", "850")), 300)
USB_PREVIEW_MODE = os.getenv("GOBAG_USB_PREVIEW_MODE", "auto").strip().lower()
USB_STREAM_CMD = os.getenv("GOBAG_USB_STREAM_CMD", "ffmpeg")
USB_STREAM_WIDTH = int(os.getenv("GOBAG_USB_STREAM_WIDTH", "960"))
USB_STREAM_HEIGHT = int(os.getenv("GOBAG_USB_STREAM_HEIGHT", "720"))
USB_STREAM_FPS = max(int(os.getenv("GOBAG_USB_STREAM_FPS", "15")), 2)
USB_STREAM_TIMEOUT_S = float(os.getenv("GOBAG_USB_STREAM_TIMEOUT_S", "10"))
USB_STREAM_PROBE_TIMEOUT_S = float(os.getenv("GOBAG_USB_STREAM_PROBE_TIMEOUT_S", "3"))
USB_SCAN_SKIP_FRAMES = max(int(os.getenv("GOBAG_USB_SCAN_SKIP_FRAMES", "12")), 0)
QR_DECODE_CMD = os.getenv("GOBAG_QR_DECODE_CMD", "zbarimg")
UI_REFRESH_INTERVAL_MS = max(int(os.getenv("GOBAG_UI_REFRESH_INTERVAL_MS", "5000")), 2000)
SHUTDOWN_SUDOERS_FILE = os.getenv("GOBAG_SHUTDOWN_SUDOERS_FILE", "/etc/sudoers.d/gobag-poweroff")
SHUTDOWN_DELAY_S = max(float(os.getenv("GOBAG_SHUTDOWN_DELAY_S", "1.2")), 0.2)
SINGLE_BAG_META_KEY = "single_bag_id"
CHECKLIST_CATEGORIES = [
    "Water & Food",
    "Medical & Health",
    "Light & Communication",
    "Tools & Protection",
    "Hygiene",
    "Other",
]
DAY_MS = 24 * 60 * 60 * 1000

app = FastAPI(title="Go-Bag Pi Server", version="2.0.0")
logger = logging.getLogger("gobag.pi")
if not logging.getLogger().handlers:
    logging.basicConfig(level=os.getenv("GOBAG_LOG_LEVEL", "INFO").upper())
IMAGE_RESAMPLING = Image.Resampling.LANCZOS if hasattr(Image, "Resampling") else Image.LANCZOS
USB_CAPABILITY_CACHE_TTL_S = 60.0
_usb_camera_capability_cache: Dict[str, tuple[float, "UsbCameraCapability"]] = {}


class Bag(BaseModel):
    bag_id: str
    name: str
    size_liters: int
    template_id: str
    updated_at: int
    updated_by: str


class Item(BaseModel):
    id: str
    bag_id: str
    name: str
    category: str
    quantity: float
    unit: str
    packed_status: bool
    notes: str
    expiry_date_ms: Optional[int] = None
    expiry_date: Optional[str] = None
    deleted: bool
    updated_at: int
    updated_by: str


class RecommendedItem(BaseModel):
    template_id: str
    category: str
    name: str
    recommended_qty: float
    unit: str
    priority: Literal["critical", "important", "optional"]
    tips: str


class PairRequest(BaseModel):
    phone_device_id: str
    pair_code: str


class PairResponse(BaseModel):
    auth_token: str
    pi_device_id: str
    server_time_ms: int


class SyncRequest(BaseModel):
    phone_device_id: str
    last_sync_at: int
    changed_bags: List[Bag] = Field(default_factory=list)
    changed_items: List[Item] = Field(default_factory=list)


class ConflictItem(BaseModel):
    item_id: str
    server_version: Item
    reason: Literal["both_modified", "delete_vs_edit"]


class AutoResolvedItem(BaseModel):
    item_id: str
    rule: Literal["packed_latest_wins"]


class AlertItem(BaseModel):
    bag_id: str
    bag_name: str
    item_id: str
    item_name: str
    type: Literal["expiring_soon", "expired"]
    days_left: int
    expiry_date_ms: int


class ChecklistCategoryStatus(BaseModel):
    name: str
    checked: bool


class SyncResponse(BaseModel):
    server_time_ms: int
    server_bag_changes: List[Bag] = Field(default_factory=list)
    server_item_changes: List[Item] = Field(default_factory=list)
    conflicts: List[ConflictItem] = Field(default_factory=list)
    auto_resolved: List[AutoResolvedItem] = Field(default_factory=list)
    alerts: List[AlertItem] = Field(default_factory=list)


class CategoryRecord(BaseModel):
    id: str
    name: str
    icon_or_label: Optional[str] = None


class BagRecord(BaseModel):
    id: str
    name: str
    bag_type: str
    readiness_status: str
    last_checked_at: Optional[int] = None
    created_at: int
    updated_at: int


class BagCreateRequest(BaseModel):
    name: str
    bag_type: str = "44l"


class BagUpdateRequest(BaseModel):
    name: str
    bag_type: str
    last_checked_at: Optional[int] = None


class ItemRecord(BaseModel):
    id: str
    bag_id: str
    category_id: str
    name: str
    quantity: float
    unit: str
    packed_status: bool
    essential: bool
    expiry_date: Optional[str] = None
    minimum_quantity: float
    condition_status: str
    notes: str
    created_at: int
    updated_at: int


class ItemWriteRequest(BaseModel):
    category_id: str
    name: str
    quantity: float = Field(gt=0)
    unit: str
    packed_status: bool = False
    essential: bool = False
    expiry_date: Optional[str] = None
    minimum_quantity: float = Field(default=0, ge=0)
    condition_status: str = "good"
    notes: str = ""


class DeviceStatusResponse(BaseModel):
    id: str
    device_name: str
    last_sync_at: int
    connection_status: str
    pending_changes_count: int
    local_ip: str
    updated_at: int
    pi_device_id: str
    pair_code: str
    paired_devices: int
    database_path: str


class SyncStatusResponse(BaseModel):
    id: str
    device_name: str
    last_sync_at: int
    connection_status: str
    pending_changes_count: int
    local_ip: str
    updated_at: int


class SettingsRecord(BaseModel):
    id: str
    default_bag_id: Optional[str] = None
    language: str
    notifications_enabled: bool
    last_connected_device: Optional[str] = None


@dataclass
class DiffResult:
    is_identical: bool
    only_packed_status_diff: bool
    delete_vs_edit: bool


@dataclass
class ParsedItemQr:
    name: str
    unit: str
    category: str
    expiry_date: str


@dataclass
class InventoryBatchView:
    item: ItemRecord
    expiry_label: str


@dataclass
class InventoryGroupView:
    bag_id: str
    name: str
    category: str
    unit: str
    total_quantity: float
    batches: List[InventoryBatchView]


@dataclass
class UsbCaptureResult:
    purpose: Literal["preview", "scan"]
    device: str
    file_path: str
    width: int
    height: int
    image_bytes: bytes


@dataclass
class UsbPreviewConfig:
    mode: Literal["stream", "snapshot"]
    device: str
    width: int
    height: int
    fps: int
    preview_url: str
    interval_ms: int
    technical: str = ""


@dataclass
class UsbCameraCapability:
    device: str
    palette_sizes: Dict[str, List[tuple[int, int]]]
    technical: str = ""
    device_label: str = ""
    bus_info: str = ""
    is_video_capture: bool = False
    is_usb: bool = False


@dataclass
class UsbCameraSessionState:
    session_id: str = ""
    bag_id: str = ""
    device: str = ""
    width: int = 0
    height: int = 0
    interval_ms: int = USB_SESSION_INTERVAL_MS
    active: bool = False
    running: bool = False
    started_at: int = 0
    last_frame_at: int = 0
    frame_id: int = 0
    image_bytes: bytes = b""
    decoded_content: str = ""
    decoded_at: int = 0
    error_payload: Optional[dict] = None
    technical: str = ""


class UsbCameraSessionManager:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._state = UsbCameraSessionState()

    def start(self, bag_id: str = "") -> dict:
        device = resolve_usb_camera_device("CAMERA_PREVIEW_FAILED")
        width = max(USB_PREVIEW_WIDTH, min(USB_SCAN_WIDTH, 1280))
        height = max(USB_PREVIEW_HEIGHT, min(USB_SCAN_HEIGHT, 720))
        interval_ms = max(min(USB_SESSION_INTERVAL_MS, USB_AUTO_SCAN_INTERVAL_MS), 300)
        session_id = uuid.uuid4().hex
        self.stop(clear_state=True)
        state = UsbCameraSessionState(
            session_id=session_id,
            bag_id=bag_id,
            device=device,
            width=width,
            height=height,
            interval_ms=interval_ms,
            active=True,
            running=True,
            started_at=now_ms(),
        )
        stop_event = threading.Event()
        worker = threading.Thread(
            target=self._capture_loop,
            args=(session_id, stop_event),
            daemon=True,
            name=f"gobag-usb-session-{session_id[:8]}",
        )
        with self._lock:
            self._state = state
            self._stop_event = stop_event
            self._thread = worker
        logger.info("USB camera session starting: %s on %s for bag %s", session_id, device, bag_id or "(default)")
        worker.start()
        return self.snapshot()

    def stop(self, clear_state: bool = True) -> None:
        with self._lock:
            stop_event = self._stop_event
            worker = self._thread
            session_id = self._state.session_id
            if clear_state:
                self._state = UsbCameraSessionState()
            else:
                self._state.active = False
                self._state.running = False
            self._thread = None
            self._stop_event = threading.Event()
        if session_id:
            logger.info("USB camera session stopping: %s", session_id)
        stop_event.set()
        if worker and worker.is_alive():
            worker.join(timeout=2)

    def snapshot(self) -> dict:
        with self._lock:
            state = UsbCameraSessionState(
                session_id=self._state.session_id,
                bag_id=self._state.bag_id,
                device=self._state.device,
                width=self._state.width,
                height=self._state.height,
                interval_ms=self._state.interval_ms,
                active=self._state.active,
                running=self._state.running,
                started_at=self._state.started_at,
                last_frame_at=self._state.last_frame_at,
                frame_id=self._state.frame_id,
                image_bytes=self._state.image_bytes,
                decoded_content=self._state.decoded_content,
                decoded_at=self._state.decoded_at,
                error_payload=dict(self._state.error_payload) if self._state.error_payload else None,
                technical=self._state.technical,
            )
        if state.error_payload:
            payload = dict(state.error_payload)
            payload.update(
                {
                    "session_id": state.session_id,
                    "device": state.device,
                    "frame_id": state.frame_id,
                    "frame_url": "/camera/usb/session/frame.jpg",
                    "active": state.active,
                    "running": state.running,
                    "started_at": state.started_at,
                    "last_frame_at": state.last_frame_at,
                    "decoded_content": state.decoded_content,
                    "decoded_at": state.decoded_at,
                    "interval_ms": state.interval_ms,
                    "resolution": {"width": state.width, "height": state.height},
                }
            )
            return payload
        return {
            "ok": True,
            "session_id": state.session_id,
            "device": state.device,
            "frame_url": "/camera/usb/session/frame.jpg",
            "frame_id": state.frame_id,
            "active": state.active,
            "running": state.running,
            "started_at": state.started_at,
            "last_frame_at": state.last_frame_at,
            "decoded_content": state.decoded_content,
            "decoded_at": state.decoded_at,
            "interval_ms": state.interval_ms,
            "resolution": {"width": state.width, "height": state.height},
            "technical": state.technical,
        }

    def frame_response(self) -> Response:
        with self._lock:
            state = self._state
            if not state.session_id or not state.image_bytes:
                raise structured_http_exception(
                    503,
                    "CAMERA_PREVIEW_FAILED",
                    "USB camera preview has not captured a frame yet.",
                    technical=state.technical or "session has no frame",
                )
            image_bytes = state.image_bytes
        return Response(content=image_bytes, media_type="image/jpeg", headers={"Cache-Control": "no-store, max-age=0"})

    def _capture_loop(self, session_id: str, stop_event: threading.Event) -> None:
        logger.info("USB camera session loop started: %s", session_id)
        while not stop_event.is_set():
            with self._lock:
                if self._state.session_id != session_id or not self._state.active:
                    return
                device = self._state.device
                width = self._state.width
                height = self._state.height
                interval_ms = self._state.interval_ms
            try:
                capture = capture_usb_camera_frame_with_device(device, "session", width, height, error_code="CAMERA_PREVIEW_FAILED")
                frame_time = now_ms()
                with self._lock:
                    if self._state.session_id != session_id:
                        return
                    self._state.image_bytes = capture.image_bytes
                    self._state.frame_id += 1
                    self._state.last_frame_at = frame_time
                    self._state.width = capture.width
                    self._state.height = capture.height
                    self._state.technical = f"latest frame {capture.width}x{capture.height}"
                    self._state.error_payload = None
                try:
                    decoded_content = decode_qr_from_image_bytes(capture.image_bytes)
                except HTTPException as exc:
                    payload = error_payload_from_detail(exc.detail, default_code="NO_QR_DETECTED")
                    if payload.get("code") == "NO_QR_DETECTED":
                        decoded_content = ""
                    else:
                        with self._lock:
                            if self._state.session_id != session_id:
                                return
                            self._state.error_payload = payload
                            self._state.running = False
                        logger.warning("USB camera session decode failed: %s", payload.get("technical") or payload.get("message"))
                        return
                if decoded_content:
                    with self._lock:
                        if self._state.session_id != session_id:
                            return
                        self._state.decoded_content = decoded_content
                        self._state.decoded_at = now_ms()
                        self._state.running = False
                        self._state.technical = f"decoded QR ({len(decoded_content)} chars)"
                    logger.info("USB camera session decoded QR for session %s", session_id)
                    return
            except HTTPException as exc:
                payload = error_payload_from_detail(exc.detail, default_code="CAMERA_PREVIEW_FAILED")
                with self._lock:
                    if self._state.session_id != session_id:
                        return
                    self._state.error_payload = payload
                    self._state.running = False
                logger.warning("USB camera session capture failed: %s", payload.get("technical") or payload.get("message"))
                return
            except Exception as exc:
                payload = structured_error_detail("CAMERA_PREVIEW_FAILED", "USB camera preview failed.", technical=str(exc)[:240])
                with self._lock:
                    if self._state.session_id != session_id:
                        return
                    self._state.error_payload = payload
                    self._state.running = False
                logger.exception("USB camera session failed unexpectedly")
                return
            stop_event.wait(interval_ms / 1000)
        logger.info("USB camera session loop stopped: %s", session_id)


usb_camera_session_manager = UsbCameraSessionManager()


def now_ms() -> int:
    return int(time.time() * 1000)


def format_time_ms(value: int) -> str:
    if not value:
        return "Never"
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(value / 1000))


def bag_size_label(size_liters: int) -> str:
    return f"{size_liters}L"


def default_bag_name_for_size(size_liters: int) -> str:
    return f"{bag_size_label(size_liters)} Bag"


def is_default_bag_name(name: str) -> bool:
    normalized = " ".join((name or "").strip().lower().split())
    return normalized in {
        "25l bag",
        "44l bag",
        "66l bag",
        "go bag",
        "go-bag",
    }


@contextmanager
def db_conn() -> sqlite3.Connection:
    data_dir = os.getenv("GOBAG_DATA_DIR", DEFAULT_DATA_DIR)
    db_path = os.path.join(data_dir, "gobag.db")
    os.makedirs(data_dir, exist_ok=True)
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
    finally:
        conn.close()


def get_meta(conn: sqlite3.Connection, key: str) -> Optional[str]:
    row = conn.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
    return row["value"] if row else None


def set_meta(conn: sqlite3.Connection, key: str, value: str) -> None:
    conn.execute(
        "INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        (key, value),
    )


def ensure_column(conn: sqlite3.Connection, table: str, column: str, definition: str) -> None:
    cols = {row["name"] for row in conn.execute(f"PRAGMA table_info({table})").fetchall()}
    if column not in cols:
        conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")


def slugify(value: str) -> str:
    cleaned = "".join(ch.lower() if ch.isalnum() else "_" for ch in value.strip())
    while "__" in cleaned:
        cleaned = cleaned.replace("__", "_")
    return cleaned.strip("_") or "unknown"


def category_id_for_name(name: str) -> str:
    return slugify(normalize_category(name))


def iso_date_from_epoch_ms(value: Optional[int]) -> Optional[str]:
    if value is None:
        return None
    return time.strftime("%Y-%m-%d", time.gmtime(value / 1000))


def bag_type_for_template(template_id: str, size_liters: int) -> str:
    if template_id == "template_25l":
        return "25l"
    if template_id == "template_44l":
        return "44l"
    if template_id == "template_66l":
        return "66l"
    return f"{size_liters}l" if size_liters in {25, 44, 66} else "44l"


def row_to_bag(row: sqlite3.Row) -> Bag:
    return Bag(**dict(row))


def row_to_item(row: sqlite3.Row) -> Item:
    data = dict(row)
    data["packed_status"] = bool(data["packed_status"])
    data["deleted"] = bool(data["deleted"])
    data["category"] = normalize_category(data["category"])
    return Item(**data)


def row_to_bag_record(row: sqlite3.Row) -> BagRecord:
    data = dict(row)
    return BagRecord(
        id=data["bag_id"],
        name=data["name"],
        bag_type=data.get("bag_type") or bag_type_for_template(data["template_id"], int(data["size_liters"])),
        readiness_status=data.get("readiness_status") or "incomplete",
        last_checked_at=data.get("last_checked_at"),
        created_at=data.get("created_at") or data["updated_at"],
        updated_at=data["updated_at"],
    )


def row_to_item_record(row: sqlite3.Row) -> ItemRecord:
    data = dict(row)
    return ItemRecord(
        id=data["id"],
        bag_id=data["bag_id"],
        category_id=data.get("category_id") or category_id_for_name(data["category"]),
        name=data["name"],
        quantity=float(data["quantity"]),
        unit=data["unit"],
        packed_status=bool(data["packed_status"]),
        essential=bool(data.get("essential") or 0),
        expiry_date=iso_date_from_epoch_ms(data.get("expiry_date_ms")),
        minimum_quantity=float(data.get("minimum_quantity") or 0),
        condition_status=data.get("condition_status") or "good",
        notes=data["notes"],
        created_at=data.get("created_at") or data["updated_at"],
        updated_at=data["updated_at"],
    )


def row_to_category_record(row: sqlite3.Row) -> CategoryRecord:
    return CategoryRecord(**dict(row))


def normalize_category(raw: str) -> str:
    value = (raw or "").strip().lower()
    if value in {"water & food", "water_food", "water-food"}:
        return "Water & Food"
    if value in {"medical & health", "medical_health", "medical-health"}:
        return "Medical & Health"
    if value in {"light & communication", "light_communication", "light-communication"}:
        return "Light & Communication"
    if value in {"tools & protection", "tools_protection", "tools-protection"}:
        return "Tools & Protection"
    if value == "hygiene":
        return "Hygiene"
    if value == "other":
        return "Other"
    if any(k in value for k in ["food", "water", "hydration", "drink"]):
        return "Water & Food"
    if any(k in value for k in ["med", "health", "first aid", "first-aid", "sanit"]):
        return "Medical & Health"
    if any(k in value for k in ["light", "radio", "whistle", "communication"]):
        return "Light & Communication"
    if any(k in value for k in ["tool", "protection", "map", "duct tape", "blanket", "poncho"]):
        return "Tools & Protection"
    if any(k in value for k in ["hygiene", "toilet", "soap"]):
        return "Hygiene"
    return "Other"


def parse_yyyy_mm_dd_to_epoch_ms(value: str) -> Optional[int]:
    try:
        return int(calendar.timegm(time.strptime(value, "%Y-%m-%d")) * 1000)
    except Exception:
        return None


def utc_day_number(value_ms: int) -> int:
    return value_ms // DAY_MS


def days_until_expiry(expiry_date_ms: int, current_time_ms: int) -> int:
    return utc_day_number(expiry_date_ms) - utc_day_number(current_time_ms)


def format_expiry_date_ms(value: Optional[int]) -> str:
    if value is None:
        return "No date"
    return time.strftime("%Y-%m-%d", time.gmtime(value / 1000))


def expiration_state_for_expiry(expiry_date_ms: Optional[int], current_time_ms: int) -> Literal["NO_EXPIRATION", "OK", "NEAR_EXPIRY", "EXPIRED"]:
    if expiry_date_ms is None:
        return "NO_EXPIRATION"
    days_left = days_until_expiry(expiry_date_ms, current_time_ms)
    if days_left < 0:
        return "EXPIRED"
    if days_left <= EXPIRING_SOON_DAYS:
        return "NEAR_EXPIRY"
    return "OK"


def expiration_state(item: Item, current_time_ms: int) -> Literal["NO_EXPIRATION", "OK", "NEAR_EXPIRY", "EXPIRED"]:
    return expiration_state_for_expiry(item.expiry_date_ms, current_time_ms)


def build_checklist(items: List[Item]) -> List[ChecklistCategoryStatus]:
    present = {normalize_category(i.category) for i in items if not i.deleted}
    return [ChecklistCategoryStatus(name=c, checked=c in present) for c in CHECKLIST_CATEGORIES]


def build_readiness_summary(items: List[Item], paired: bool, current_time_ms: int) -> dict:
    active = [i for i in items if not i.deleted]
    checklist = build_checklist(active)
    missing = [c.name for c in checklist if not c.checked]
    expired_count = sum(1 for i in active if expiration_state(i, current_time_ms) == "EXPIRED")
    near_expiry_count = sum(1 for i in active if expiration_state(i, current_time_ms) == "NEAR_EXPIRY")
    critical_expired_count = sum(
        1
        for i in active
        if expiration_state(i, current_time_ms) == "EXPIRED" and normalize_category(i.category) in {"Water & Food", "Medical & Health"}
    )
    bag_readiness = "Incomplete"
    if paired and critical_expired_count > 0:
        bag_readiness = "Attention Needed"
    elif paired and not missing:
        bag_readiness = "Ready"
    alerts: List[str] = []
    if missing:
        alerts.append(f"Missing categories: {', '.join(missing)}")
    if expired_count:
        alerts.append(f"Expired items: {expired_count}")
    if near_expiry_count:
        alerts.append(f"Near-expiry items: {near_expiry_count}")
    return {
        "device_status": "Paired" if paired else "Not Paired",
        "bag_readiness": bag_readiness,
        "checklist": [c.model_dump() for c in checklist],
        "checklist_covered": sum(1 for c in checklist if c.checked),
        "checklist_total": len(CHECKLIST_CATEGORIES),
        "expired_count": expired_count,
        "near_expiry_count": near_expiry_count,
        "critical_expired_count": critical_expired_count,
        "alerts": alerts,
    }


def upsert_bag(conn: sqlite3.Connection, bag: Bag) -> None:
    conn.execute(
        """
        INSERT INTO bags(bag_id, name, size_liters, template_id, updated_at, updated_by)
        VALUES(?, ?, ?, ?, ?, ?)
        ON CONFLICT(bag_id) DO UPDATE SET
          name=excluded.name, size_liters=excluded.size_liters, template_id=excluded.template_id,
          updated_at=excluded.updated_at, updated_by=excluded.updated_by
        """,
        (bag.bag_id, bag.name, bag.size_liters, bag.template_id, bag.updated_at, bag.updated_by),
    )
    conn.execute(
        """
        UPDATE bags
        SET
          bag_type = COALESCE(bag_type, ?),
          created_at = COALESCE(created_at, ?),
          readiness_status = COALESCE(readiness_status, 'incomplete')
        WHERE bag_id = ?
        """,
        (bag_type_for_template(bag.template_id, bag.size_liters), bag.updated_at, bag.bag_id),
    )


def upsert_item(conn: sqlite3.Connection, item: Item) -> None:
    category = normalize_category(item.category)
    category_id = category_id_for_name(category)
    expiry_date_ms = item.expiry_date_ms
    if expiry_date_ms is None and item.expiry_date:
        expiry_date_ms = parse_yyyy_mm_dd_to_epoch_ms(item.expiry_date)
    conn.execute(
        """
        INSERT INTO items(id, bag_id, name, category, quantity, unit, packed_status, notes, expiry_date_ms, deleted, updated_at, updated_by)
        VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
          bag_id=excluded.bag_id, name=excluded.name, category=excluded.category, quantity=excluded.quantity, unit=excluded.unit,
          packed_status=excluded.packed_status, notes=excluded.notes, expiry_date_ms=excluded.expiry_date_ms,
          deleted=excluded.deleted, updated_at=excluded.updated_at, updated_by=excluded.updated_by
        """,
        (
            item.id,
            item.bag_id,
            item.name,
            category,
            item.quantity,
            item.unit,
            int(item.packed_status),
            item.notes,
            expiry_date_ms,
            int(item.deleted),
            item.updated_at,
            item.updated_by,
        ),
    )
    conn.execute(
        """
        UPDATE items
        SET
          category_id = COALESCE(category_id, ?),
          created_at = COALESCE(created_at, ?),
          essential = COALESCE(essential, 0),
          minimum_quantity = COALESCE(minimum_quantity, 0),
          condition_status = COALESCE(condition_status, 'good')
        WHERE id = ?
        """,
        (category_id, item.updated_at, item.id),
    )


def preferred_single_bag_row(conn: sqlite3.Connection, preferred_bag_id: Optional[str] = None) -> Optional[sqlite3.Row]:
    rows = conn.execute(
        """
        SELECT bags.*, COALESCE(item_counts.item_count, 0) AS item_count
        FROM bags
        LEFT JOIN (
          SELECT bag_id, COUNT(*) AS item_count
          FROM items
          WHERE deleted = 0
          GROUP BY bag_id
        ) item_counts ON item_counts.bag_id = bags.bag_id
        """
    ).fetchall()
    if not rows:
        return None

    def match_bag_id(target_bag_id: Optional[str]) -> Optional[sqlite3.Row]:
        if not target_bag_id:
            return None
        return next((row for row in rows if row["bag_id"] == target_bag_id), None)

    preferred = match_bag_id(preferred_bag_id)
    if preferred is not None:
        return preferred

    preferred = match_bag_id(get_meta(conn, SINGLE_BAG_META_KEY))
    if preferred is not None:
        return preferred

    settings_row = conn.execute("SELECT default_bag_id FROM settings WHERE id = 'primary'").fetchone()
    preferred = match_bag_id(settings_row["default_bag_id"] if settings_row else None)
    if preferred is not None:
        return preferred

    ordered = sorted(
        rows,
        key=lambda row: (
            -int(row["item_count"] or 0),
            0 if int(row["size_liters"] or 44) == 44 else 1,
            int(row["created_at"] or row["updated_at"] or 0),
            (row["name"] or "").lower(),
        ),
    )
    return ordered[0]


def rename_bag_id(conn: sqlite3.Connection, current_bag_id: str, target_bag_id: str) -> None:
    if not current_bag_id or not target_bag_id or current_bag_id == target_bag_id:
        return

    current_row = conn.execute("SELECT * FROM bags WHERE bag_id = ?", (current_bag_id,)).fetchone()
    if not current_row:
        return

    changed_at = now_ms()
    updated_by = get_meta(conn, "pi_device_id") or "pi"
    existing_target = conn.execute("SELECT * FROM bags WHERE bag_id = ?", (target_bag_id,)).fetchone()
    conn.execute(
        "UPDATE items SET bag_id = ?, updated_at = ?, updated_by = ? WHERE bag_id = ?",
        (target_bag_id, changed_at, updated_by, current_bag_id),
    )
    if existing_target:
        conn.execute("DELETE FROM bags WHERE bag_id = ?", (current_bag_id,))
        return
    conn.execute(
        "UPDATE bags SET bag_id = ?, updated_at = ?, updated_by = ? WHERE bag_id = ?",
        (target_bag_id, changed_at, updated_by, current_bag_id),
    )


def ensure_single_bag(
    conn: sqlite3.Connection,
    preferred_bag_id: Optional[str] = None,
    preferred_size_liters: Optional[int] = None,
    preferred_name: Optional[str] = None,
) -> sqlite3.Row:
    canonical = preferred_single_bag_row(conn, preferred_bag_id=preferred_bag_id)
    if canonical is None:
        current_time = now_ms()
        size_liters = preferred_size_liters if preferred_size_liters in {25, 44, 66} else 44
        upsert_bag(
            conn,
            Bag(
                bag_id=preferred_bag_id or str(uuid.uuid4()),
                name=(preferred_name or "").strip() or default_bag_name_for_size(size_liters),
                size_liters=size_liters,
                template_id=template_id_for_size(size_liters),
                updated_at=current_time,
                updated_by=get_meta(conn, "pi_device_id") or "pi",
            ),
        )
        canonical = preferred_single_bag_row(conn, preferred_bag_id=preferred_bag_id)
        if canonical is None:
            raise HTTPException(status_code=500, detail="Unable to initialize the Raspberry Pi bag")

    if preferred_bag_id and canonical["bag_id"] != preferred_bag_id:
        rename_bag_id(conn, canonical["bag_id"], preferred_bag_id)
        canonical = conn.execute("SELECT * FROM bags WHERE bag_id = ?", (preferred_bag_id,)).fetchone()
        if canonical is None:
            raise HTTPException(status_code=500, detail="Unable to align the Raspberry Pi bag identity")

    extras = conn.execute("SELECT bag_id FROM bags WHERE bag_id != ?", (canonical["bag_id"],)).fetchall()
    if extras:
        changed_at = now_ms()
        updated_by = get_meta(conn, "pi_device_id") or "pi"
        for row in extras:
            conn.execute(
                "UPDATE items SET bag_id = ?, updated_at = ?, updated_by = ? WHERE bag_id = ?",
                (canonical["bag_id"], changed_at, updated_by, row["bag_id"]),
            )
        conn.executemany("DELETE FROM bags WHERE bag_id = ?", [(row["bag_id"],) for row in extras])
        canonical = conn.execute("SELECT * FROM bags WHERE bag_id = ?", (canonical["bag_id"],)).fetchone()

    target_size = preferred_size_liters if preferred_size_liters in {25, 44, 66} else int(canonical["size_liters"] or 44)
    target_name = (preferred_name or "").strip()
    if not target_name:
        current_name = (canonical["name"] or "").strip()
        if not current_name:
            target_name = default_bag_name_for_size(target_size)
        elif is_default_bag_name(current_name) and int(canonical["size_liters"] or target_size) != target_size:
            target_name = default_bag_name_for_size(target_size)
        else:
            target_name = current_name

    target_template_id = template_id_for_size(target_size)
    target_bag_type = bag_type_for_template(target_template_id, target_size)
    if (
        canonical["name"] != target_name
        or int(canonical["size_liters"] or 44) != target_size
        or canonical["template_id"] != target_template_id
        or (canonical["bag_type"] or "") != target_bag_type
        or canonical["created_at"] is None
        or not canonical["readiness_status"]
    ):
        changed_at = max(now_ms(), int(canonical["updated_at"] or 0))
        conn.execute(
            """
            UPDATE bags
            SET
              name = ?,
              size_liters = ?,
              template_id = ?,
              updated_at = ?,
              updated_by = ?,
              bag_type = ?,
              readiness_status = COALESCE(readiness_status, 'incomplete'),
              created_at = COALESCE(created_at, ?)
            WHERE bag_id = ?
            """,
            (
                target_name,
                target_size,
                target_template_id,
                changed_at,
                get_meta(conn, "pi_device_id") or "pi",
                target_bag_type,
                canonical["updated_at"] or changed_at,
                canonical["bag_id"],
            ),
        )

    conn.execute(
        """
        INSERT INTO settings(id, default_bag_id, language, notifications_enabled, last_connected_device)
        VALUES('primary', ?, 'en', 1, NULL)
        ON CONFLICT(id) DO UPDATE SET default_bag_id = excluded.default_bag_id
        """,
        (canonical["bag_id"],),
    )
    set_meta(conn, SINGLE_BAG_META_KEY, canonical["bag_id"])
    return conn.execute("SELECT * FROM bags WHERE bag_id = ?", (canonical["bag_id"],)).fetchone()


def create_pair_code(conn: sqlite3.Connection) -> sqlite3.Row:
    code = f"{secrets.randbelow(900000) + 100000}"
    created = now_ms()
    expires = created + PAIR_CODE_TTL_MS
    conn.execute("UPDATE pair_codes SET active = 0")
    conn.execute("INSERT INTO pair_codes(code, created_at, expires_at, active) VALUES(?, ?, ?, 1)", (code, created, expires))
    return conn.execute("SELECT * FROM pair_codes WHERE code = ?", (code,)).fetchone()


def active_pair_code(conn: sqlite3.Connection) -> Optional[sqlite3.Row]:
    conn.execute("UPDATE pair_codes SET active = 0 WHERE expires_at < ?", (now_ms(),))
    return conn.execute("SELECT * FROM pair_codes WHERE active = 1 ORDER BY created_at DESC LIMIT 1").fetchone()


def preferred_non_loopback_ip() -> str:
    try:
        output = subprocess.run(
            ["hostname", "-I"],
            check=False,
            capture_output=True,
            text=True,
            timeout=2,
        )
        if output.returncode == 0:
            for candidate in output.stdout.split():
                if candidate and not candidate.startswith("127."):
                    return candidate
    except Exception:
        pass

    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.connect(("10.255.255.255", 1))
        ip = sock.getsockname()[0]
        sock.close()
        if ip and not ip.startswith("127."):
            return ip
    except OSError:
        pass

    try:
        for result in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET, socket.SOCK_DGRAM):
            candidate = result[4][0]
            if candidate and not candidate.startswith("127."):
                return candidate
    except OSError:
        pass

    return ""


def compute_local_ip() -> str:
    base_url = os.getenv("GOBAG_BASE_URL", "").strip()
    if "://" in base_url:
        host_port = base_url.split("://", 1)[1].split("/", 1)[0]
        if host_port and "127.0.0.1" not in host_port and "localhost" not in host_port:
            return host_port

    detected_ip = preferred_non_loopback_ip()
    if detected_ip:
        return f"{detected_ip}:{PORT}"

    if HOST not in {"127.0.0.1", "localhost"} and HOST != "0.0.0.0":
        return f"{HOST}:{PORT}"

    return ""


def compute_base_url(request: Optional[Request] = None) -> str:
    configured = os.getenv("GOBAG_BASE_URL", "").strip()
    if configured:
        return configured

    detected = compute_local_ip()
    if detected:
        return f"http://{detected}"

    if request is not None:
        host = request.headers.get("host", "").strip()
        if host and "127.0.0.1" not in host and "localhost" not in host:
            return f"http://{host}"

    return f"http://127.0.0.1:{PORT}"


def update_device_state(conn: sqlite3.Connection, connection_status: Optional[str] = None) -> None:
    now = now_ms()
    pending = conn.execute("SELECT COUNT(*) AS c FROM items WHERE deleted = 0").fetchone()["c"]
    last_sync = int(get_meta(conn, "last_sync_time_ms") or "0")
    paired_devices = conn.execute("SELECT COUNT(*) AS c FROM tokens WHERE revoked = 0").fetchone()["c"]
    status = connection_status or ("paired" if paired_devices > 0 else "waiting_for_pair")
    conn.execute(
        """
        INSERT INTO device_state(id, device_name, last_sync_at, connection_status, pending_changes_count, local_ip, updated_at)
        VALUES('primary', ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
          device_name=excluded.device_name,
          last_sync_at=excluded.last_sync_at,
          connection_status=excluded.connection_status,
          pending_changes_count=excluded.pending_changes_count,
          local_ip=excluded.local_ip,
          updated_at=excluded.updated_at
        """,
        (DEVICE_NAME, last_sync, status, pending, compute_local_ip(), now),
    )


def update_bag_readiness(conn: sqlite3.Connection, bag_id: Optional[str] = None) -> None:
    bag_rows = (
        conn.execute("SELECT * FROM bags WHERE bag_id = ?", (bag_id,)).fetchall()
        if bag_id
        else conn.execute("SELECT * FROM bags").fetchall()
    )
    paired = conn.execute("SELECT COUNT(*) AS c FROM tokens WHERE revoked = 0").fetchone()["c"] > 0
    current_time = now_ms()
    for bag_row in bag_rows:
        item_rows = conn.execute("SELECT * FROM items WHERE bag_id = ?", (bag_row["bag_id"],)).fetchall()
        summary = build_readiness_summary([row_to_item(r) for r in item_rows], paired, current_time)
        status = summary["bag_readiness"].lower().replace(" ", "_")
        conn.execute(
            "UPDATE bags SET readiness_status = ?, last_checked_at = ?, updated_at = updated_at WHERE bag_id = ?",
            (status, current_time, bag_row["bag_id"]),
        )


def init_db() -> None:
    with db_conn() as conn:
        conn.executescript(
            """
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS bags (
              bag_id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              size_liters INTEGER NOT NULL,
              template_id TEXT NOT NULL,
              updated_at INTEGER NOT NULL,
              updated_by TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS items (
              id TEXT PRIMARY KEY,
              bag_id TEXT NOT NULL,
              name TEXT NOT NULL,
              category TEXT NOT NULL,
              quantity REAL NOT NULL,
              unit TEXT NOT NULL,
              packed_status INTEGER NOT NULL,
              notes TEXT NOT NULL,
              expiry_date_ms INTEGER NULL,
              deleted INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              updated_by TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS templates (
              template_id TEXT NOT NULL,
              category TEXT NOT NULL,
              name TEXT NOT NULL,
              recommended_qty REAL NOT NULL,
              unit TEXT NOT NULL,
              priority TEXT NOT NULL,
              tips TEXT NOT NULL,
              PRIMARY KEY(template_id, category, name)
            );
            CREATE TABLE IF NOT EXISTS tokens (
              token TEXT PRIMARY KEY,
              phone_device_id TEXT NOT NULL,
              issued_at INTEGER NOT NULL,
              revoked INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS pair_codes (
              code TEXT PRIMARY KEY,
              created_at INTEGER NOT NULL,
              expires_at INTEGER NOT NULL,
              active INTEGER NOT NULL DEFAULT 1
            );
            CREATE TABLE IF NOT EXISTS meta (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS categories (
              id TEXT PRIMARY KEY,
              name TEXT NOT NULL UNIQUE,
              icon_or_label TEXT NULL
            );
            CREATE TABLE IF NOT EXISTS device_state (
              id TEXT PRIMARY KEY,
              device_name TEXT NOT NULL,
              last_sync_at INTEGER NOT NULL,
              connection_status TEXT NOT NULL,
              pending_changes_count INTEGER NOT NULL,
              local_ip TEXT NOT NULL,
              updated_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS settings (
              id TEXT PRIMARY KEY,
              default_bag_id TEXT NULL,
              language TEXT NOT NULL,
              notifications_enabled INTEGER NOT NULL,
              last_connected_device TEXT NULL
            );
            """
        )
        ensure_column(conn, "bags", "bag_type", "TEXT")
        ensure_column(conn, "bags", "readiness_status", "TEXT")
        ensure_column(conn, "bags", "last_checked_at", "INTEGER")
        ensure_column(conn, "bags", "created_at", "INTEGER")
        ensure_column(conn, "items", "category_id", "TEXT")
        ensure_column(conn, "items", "essential", "INTEGER NOT NULL DEFAULT 0")
        ensure_column(conn, "items", "minimum_quantity", "REAL NOT NULL DEFAULT 0")
        ensure_column(conn, "items", "condition_status", "TEXT NOT NULL DEFAULT 'good'")
        ensure_column(conn, "items", "created_at", "INTEGER")
        if not get_meta(conn, "pi_device_id"):
            set_meta(conn, "pi_device_id", str(uuid.uuid4()))
        conn.executemany(
            """
            INSERT INTO categories(id, name, icon_or_label)
            VALUES(?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              name=excluded.name,
              icon_or_label=excluded.icon_or_label
            """,
            [
                (category_id_for_name("Water & Food"), "Water & Food", "WF"),
                (category_id_for_name("Medical & Health"), "Medical & Health", "MH"),
                (category_id_for_name("Light & Communication"), "Light & Communication", "LC"),
                (category_id_for_name("Tools & Protection"), "Tools & Protection", "TP"),
                (category_id_for_name("Hygiene"), "Hygiene", "HY"),
                (category_id_for_name("Other"), "Other", "OT"),
            ],
        )
        conn.execute("DELETE FROM templates")
        conn.executemany(
            "INSERT INTO templates(template_id, category, name, recommended_qty, unit, priority, tips) VALUES(?, ?, ?, ?, ?, ?, ?)",
            [
                ("template_25l", "Water & Food", "Drinking Water", 2, "L", "critical", "Rotate every 6 months."),
                ("template_25l", "Medical & Health", "First Aid Kit", 1, "set", "important", "Keep medications current."),
                ("template_44l", "Water & Food", "Drinking Water", 3, "L", "critical", "Rotate every 6 months."),
                ("template_44l", "Water & Food", "Energy Bars", 6, "pcs", "critical", "Heat stable bars."),
                ("template_66l", "Water & Food", "Drinking Water", 6, "L", "critical", "Use sealed bottles."),
                ("template_66l", "Medical & Health", "First Aid Kit", 1, "set", "critical", "Include meds."),
                ("template_44l", "Tools & Protection", "Multi Tool", 1, "pcs", "important", "Rust resistant."),
            ],
        )
        if conn.execute("SELECT COUNT(*) AS c FROM bags").fetchone()["c"] == 0:
            t = now_ms()
            size_liters = 44
            upsert_bag(
                conn,
                Bag(
                    bag_id=str(uuid.uuid4()),
                    name=default_bag_name_for_size(size_liters),
                    size_liters=size_liters,
                    template_id=template_id_for_size(size_liters),
                    updated_at=t,
                    updated_by=get_meta(conn, "pi_device_id") or "pi",
                ),
            )
        conn.execute(
            """
            UPDATE bags
            SET
              bag_type = COALESCE(bag_type, CASE
                WHEN template_id = 'template_25l' THEN '25l'
                WHEN template_id = 'template_44l' THEN '44l'
                WHEN template_id = 'template_66l' THEN '66l'
                ELSE '44l'
              END),
              readiness_status = COALESCE(readiness_status, 'incomplete'),
              created_at = COALESCE(created_at, updated_at)
            """
        )
        conn.execute(
            """
            UPDATE items
            SET
              category_id = COALESCE(category_id, LOWER(REPLACE(REPLACE(category, ' & ', '_'), ' ', '_'))),
              created_at = COALESCE(created_at, updated_at),
              essential = COALESCE(essential, 0),
              minimum_quantity = COALESCE(minimum_quantity, 0),
              condition_status = COALESCE(condition_status, 'good')
            """
        )
        if not active_pair_code(conn):
            create_pair_code(conn)
        conn.execute(
            """
            INSERT INTO settings(id, default_bag_id, language, notifications_enabled, last_connected_device)
            VALUES('primary', NULL, 'en', 1, NULL)
            ON CONFLICT(id) DO NOTHING
            """
        )
        ensure_single_bag(conn)
        update_bag_readiness(conn)
        update_device_state(conn)
        conn.commit()


def parse_token(authorization: Optional[str]) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing bearer token")
    return authorization.replace("Bearer ", "", 1)


def require_token(authorization: Optional[str] = Header(default=None)) -> str:
    token = parse_token(authorization)
    with db_conn() as conn:
        row = conn.execute("SELECT token FROM tokens WHERE token = ? AND revoked = 0", (token,)).fetchone()
        if not row:
            raise HTTPException(status_code=401, detail="Invalid token")
    return token


def require_admin_access(request: Request) -> None:
    if not ADMIN_TOKEN:
        return
    provided = request.headers.get("x-gobag-admin-token") or request.query_params.get("token", "")
    if provided != ADMIN_TOKEN:
        raise HTTPException(status_code=403, detail="Admin token required")


def resolve_shutdown_command() -> List[str]:
    systemctl_path = shutil.which("systemctl") or "/usr/bin/systemctl"
    if not systemctl_path or not os.path.exists(systemctl_path):
        raise RuntimeError("systemctl is not available on this Raspberry Pi.")
    if hasattr(os, "geteuid") and os.geteuid() == 0:
        return [systemctl_path, "poweroff"]
    sudo_path = shutil.which("sudo") or "/usr/bin/sudo"
    if not sudo_path or not os.path.exists(sudo_path):
        raise PermissionError("sudo is not available for backend-triggered shutdown.")
    if not os.path.exists(SHUTDOWN_SUDOERS_FILE):
        raise PermissionError(
            "Shutdown permission is not installed yet. Re-run pi-server/install.sh to enable the Power button."
        )
    return [sudo_path, "-n", systemctl_path, "poweroff"]


def require_shutdown_ready() -> List[str]:
    try:
        return resolve_shutdown_command()
    except PermissionError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


def trigger_safe_shutdown(command: List[str]) -> None:
    try:
        time.sleep(SHUTDOWN_DELAY_S)
        logger.warning("Shutdown requested from GO BAG dashboard via %s", shlex.join(command))
        subprocess.Popen(
            command,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except Exception:
        logger.exception("Failed to trigger Raspberry Pi shutdown")


def diff_item(server: Item, phone: Item) -> DiffResult:
    fields = ["bag_id", "name", "category", "quantity", "unit", "packed_status", "notes", "expiry_date_ms", "deleted"]
    diffs = [f for f in fields if getattr(server, f) != getattr(phone, f)]
    delete_vs_edit = False
    if server.deleted != phone.deleted:
        for f in ["bag_id", "name", "category", "quantity", "unit", "packed_status", "notes", "expiry_date_ms"]:
            if getattr(server, f) != getattr(phone, f):
                delete_vs_edit = True
                break
    return DiffResult(
        is_identical=len(diffs) == 0,
        only_packed_status_diff=diffs == ["packed_status"],
        delete_vs_edit=delete_vs_edit,
    )


def compute_alerts(conn: sqlite3.Connection, current_time_ms: int) -> List[AlertItem]:
    rows = conn.execute(
        """
        SELECT
          items.id,
          items.bag_id,
          items.name AS item_name,
          bags.name AS bag_name,
          items.expiry_date_ms
        FROM items
        LEFT JOIN bags ON bags.bag_id = items.bag_id
        WHERE items.deleted = 0 AND items.expiry_date_ms IS NOT NULL
        ORDER BY items.expiry_date_ms ASC, items.name ASC
        """
    ).fetchall()
    alerts: List[AlertItem] = []
    for r in rows:
        expiry = int(r["expiry_date_ms"])
        days_left = days_until_expiry(expiry, current_time_ms)
        state = expiration_state_for_expiry(expiry, current_time_ms)
        if state == "EXPIRED":
            alerts.append(
                AlertItem(
                    bag_id=r["bag_id"],
                    bag_name=r["bag_name"] or r["bag_id"],
                    item_id=r["id"],
                    item_name=r["item_name"],
                    type="expired",
                    days_left=days_left,
                    expiry_date_ms=expiry,
                )
            )
            continue
        if state == "NEAR_EXPIRY":
            alerts.append(
                AlertItem(
                    bag_id=r["bag_id"],
                    bag_name=r["bag_name"] or r["bag_id"],
                    item_id=r["id"],
                    item_name=r["item_name"],
                    type="expiring_soon",
                    days_left=days_left,
                    expiry_date_ms=expiry,
                )
            )
    return alerts


def size_liters_for_bag_type(bag_type: str) -> int:
    normalized = bag_type.strip().lower()
    if normalized == "25l":
        return 25
    if normalized == "44l":
        return 44
    if normalized == "66l":
        return 66
    raise HTTPException(status_code=400, detail="Bag size must be 25L, 44L, or 66L")


def template_id_for_size(size_liters: int) -> str:
    if size_liters == 25:
        return "template_25l"
    if size_liters == 44:
        return "template_44l"
    if size_liters == 66:
        return "template_66l"
    raise HTTPException(status_code=400, detail="Bag size must be 25L, 44L, or 66L")


def normalized_identity_key(name: str, unit: str, category: str) -> str:
    return "|".join(
        [
            " ".join((name or "").strip().lower().split()),
            " ".join((unit or "").strip().lower().split()),
            normalize_category(category),
        ]
    )


def merged_notes(existing: str, incoming: str) -> str:
    if not incoming.strip():
        return existing.strip()
    if not existing.strip():
        return incoming.strip()
    if incoming.strip().lower() in existing.strip().lower():
        return existing.strip()
    return f"{existing.strip()} | {incoming.strip()}"


def find_matching_item_rows(
    conn: sqlite3.Connection,
    bag_id: str,
    name: str,
    unit: str,
    category_name: str,
    exclude_item_id: Optional[str] = None,
) -> List[sqlite3.Row]:
    rows = conn.execute(
        "SELECT * FROM items WHERE bag_id = ? AND deleted = 0 ORDER BY updated_at DESC",
        (bag_id,),
    ).fetchall()
    key = normalized_identity_key(name, unit, category_name)
    matches = [
        row
        for row in rows
        if row["id"] != exclude_item_id and normalized_identity_key(row["name"], row["unit"], row["category"]) == key
    ]
    return matches


def merge_or_write_item_record(
    conn: sqlite3.Connection,
    bag_id: str,
    payload: ItemWriteRequest,
    item_id: Optional[str] = None,
) -> ItemRecord:
    get_bag_row_or_404(conn, bag_id)
    category_name = get_category_name(conn, payload.category_id)
    expiry_date_ms = parse_yyyy_mm_dd_to_epoch_ms(payload.expiry_date) if payload.expiry_date else None
    if payload.expiry_date and expiry_date_ms is None:
        raise HTTPException(status_code=400, detail="Expiry date must use YYYY-MM-DD")

    matches = find_matching_item_rows(
        conn,
        bag_id=bag_id,
        name=payload.name.strip(),
        unit=payload.unit.strip(),
        category_name=category_name,
        exclude_item_id=item_id,
    )
    exact_match = next(
        (
            row
            for row in matches
            if (row["expiry_date_ms"] if row["expiry_date_ms"] is not None else None) == expiry_date_ms
        ),
        None,
    )
    target_item_id = item_id or str(uuid.uuid4())
    if exact_match is not None:
        target_item_id = exact_match["id"]
        payload = ItemWriteRequest(
            category_id=payload.category_id,
            name=payload.name.strip(),
            quantity=float(exact_match["quantity"]) + payload.quantity,
            unit=payload.unit.strip(),
            packed_status=bool(exact_match["packed_status"]) or payload.packed_status,
            essential=bool(exact_match["essential"]) or payload.essential,
            expiry_date=payload.expiry_date,
            minimum_quantity=max(float(exact_match["minimum_quantity"] or 0), payload.minimum_quantity),
            condition_status=payload.condition_status.strip() or exact_match["condition_status"] or "good",
            notes=merged_notes(exact_match["notes"] or "", payload.notes or ""),
        )

    written = write_item_record(conn, target_item_id, bag_id, payload)
    if item_id and exact_match is not None and item_id != exact_match["id"]:
        conn.execute(
            """
            UPDATE items
            SET deleted = 1, updated_at = ?, updated_by = ?
            WHERE id = ?
            """,
            (now_ms(), get_meta(conn, "pi_device_id") or "pi", item_id),
        )
    return written


def parse_item_qr_content(raw: str) -> ParsedItemQr:
    value = raw.strip()
    if not value:
        raise HTTPException(status_code=400, detail="Scanned QR code was empty")
    parts = [part.strip() for part in value.split("/")]
    if len(parts) != 4 or any(not part for part in parts):
        raise HTTPException(status_code=400, detail="QR format must be Item/Unit/Category/YYYY-MM-DD")
    name, unit, category, expiry_date = parts
    if parse_yyyy_mm_dd_to_epoch_ms(expiry_date) is None:
        raise HTTPException(status_code=400, detail="QR expiration date must use YYYY-MM-DD")
    return ParsedItemQr(
        name=name,
        unit=unit,
        category=normalize_category(category),
        expiry_date=expiry_date,
    )


def usb_scan_available() -> bool:
    return shutil.which(USB_SCAN_CMD) is not None


def usb_stream_available() -> bool:
    return shutil.which(USB_STREAM_CMD) is not None


def qr_decode_available() -> bool:
    return shutil.which(QR_DECODE_CMD) is not None


def usb_v4l2ctl_available() -> bool:
    return shutil.which(USB_V4L2CTL_CMD) is not None


def decode_process_output(raw: Optional[bytes]) -> str:
    if not raw:
        return ""
    return raw.decode("utf-8", errors="replace").strip()


def available_usb_camera_devices() -> List[str]:
    return sorted(path for path in glob.glob("/dev/video*") if os.path.exists(path))


def parse_v4l2_device_details(device: str, output: str) -> UsbCameraCapability:
    device_label = ""
    bus_info = ""
    for raw_line in output.splitlines():
        if ":" not in raw_line:
            continue
        key, value = raw_line.split(":", 1)
        normalized_key = key.strip().lower()
        normalized_value = value.strip()
        if normalized_key == "card type":
            device_label = normalized_value
        elif normalized_key == "bus info":
            bus_info = normalized_value
    is_video_capture = bool(re.search(r"(?mi)^\s*Video Capture(?: Multiplanar)?\s*$", output))
    is_usb = "usb" in bus_info.lower() or "usb" in device_label.lower()
    technical_parts = []
    if device_label:
        technical_parts.append(device_label)
    if bus_info:
        technical_parts.append(bus_info)
    if is_video_capture:
        technical_parts.append("video capture")
    if is_usb:
        technical_parts.append("usb")
    return UsbCameraCapability(
        device=device,
        palette_sizes={},
        technical=", ".join(technical_parts) or "device details unavailable",
        device_label=device_label,
        bus_info=bus_info,
        is_video_capture=is_video_capture,
        is_usb=is_usb,
    )


def parse_v4l2_format_listing(base: UsbCameraCapability, output: str) -> UsbCameraCapability:
    palette_sizes: Dict[str, List[tuple[int, int]]] = {}
    current_palette = ""
    for raw_line in output.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        format_match = re.search(r"\[\d+\]:\s+'([^']+)'", line)
        if format_match:
            current_palette = format_match.group(1).strip().upper()
            palette_sizes.setdefault(current_palette, [])
            continue
        size_match = re.search(r"Size:\s+Discrete\s+(\d+)x(\d+)", line)
        if size_match and current_palette:
            size = (int(size_match.group(1)), int(size_match.group(2)))
            if size not in palette_sizes[current_palette]:
                palette_sizes[current_palette].append(size)
    technical_parts = [part for part in [base.device_label, base.bus_info] if part]
    if base.is_video_capture:
        technical_parts.append("video capture")
    if base.is_usb:
        technical_parts.append("usb")
    technical_parts.append("capture formats detected" if palette_sizes else "no capture formats listed")
    return UsbCameraCapability(
        device=base.device,
        palette_sizes=palette_sizes,
        technical=", ".join(technical_parts),
        device_label=base.device_label,
        bus_info=base.bus_info,
        is_video_capture=base.is_video_capture,
        is_usb=base.is_usb,
    )


def video_device_sort_key(device: str) -> tuple[int, str]:
    match = re.search(r"(\d+)$", device)
    return (int(match.group(1)) if match else 10**6, device)


def read_usb_camera_capability(device: str, force_refresh: bool = False) -> UsbCameraCapability:
    cached = _usb_camera_capability_cache.get(device)
    now = time.time()
    if cached and not force_refresh and (now - cached[0]) < USB_CAPABILITY_CACHE_TTL_S:
        return cached[1]

    if not usb_v4l2ctl_available():
        capability = UsbCameraCapability(device=device, palette_sizes={}, technical=f"missing command: {USB_V4L2CTL_CMD}")
        _usb_camera_capability_cache[device] = (now, capability)
        return capability

    details_command = [USB_V4L2CTL_CMD, "--device", device, "--all"]
    logger.info("USB camera details command: %s", shlex.join(details_command))
    try:
        details_result = subprocess.run(
            details_command,
            check=False,
            capture_output=True,
            timeout=USB_DEVICE_QUERY_TIMEOUT_S,
        )
    except subprocess.TimeoutExpired:
        capability = UsbCameraCapability(device=device, palette_sizes={}, technical="device capability query timed out")
        _usb_camera_capability_cache[device] = (now, capability)
        return capability
    except OSError as exc:
        capability = UsbCameraCapability(device=device, palette_sizes={}, technical=exc.strerror or str(exc))
        _usb_camera_capability_cache[device] = (now, capability)
        return capability

    details_output = "\n".join(
        part for part in [decode_process_output(details_result.stdout), decode_process_output(details_result.stderr)] if part
    ).strip()
    capability = parse_v4l2_device_details(device, details_output)
    if details_result.returncode != 0 and not capability.is_video_capture:
        capability.technical = details_output[:240] or f"returncode={details_result.returncode}"
        _usb_camera_capability_cache[device] = (now, capability)
        return capability
    if not capability.is_video_capture:
        _usb_camera_capability_cache[device] = (now, capability)
        return capability

    formats_command = [USB_V4L2CTL_CMD, "--device", device, "--list-formats-ext"]
    logger.info("USB camera capability command: %s", shlex.join(formats_command))
    try:
        formats_result = subprocess.run(
            formats_command,
            check=False,
            capture_output=True,
            timeout=USB_DEVICE_QUERY_TIMEOUT_S,
        )
    except subprocess.TimeoutExpired:
        capability.technical = f"{capability.technical}, format query timed out"
        _usb_camera_capability_cache[device] = (now, capability)
        return capability
    except OSError as exc:
        capability.technical = f"{capability.technical}, {exc.strerror or str(exc)}"
        _usb_camera_capability_cache[device] = (now, capability)
        return capability

    formats_output = "\n".join(
        part for part in [decode_process_output(formats_result.stdout), decode_process_output(formats_result.stderr)] if part
    ).strip()
    capability = parse_v4l2_format_listing(capability, formats_output)
    if formats_result.returncode != 0 and not capability.palette_sizes:
        capability.technical = f"{capability.technical}, {formats_output[:240] or f'returncode={formats_result.returncode}'}"
    _usb_camera_capability_cache[device] = (now, capability)
    return capability


def available_usb_capture_devices() -> List[str]:
    devices = available_usb_camera_devices()
    if not devices:
        return []
    if not usb_v4l2ctl_available():
        return devices
    capabilities = [read_usb_camera_capability(device) for device in devices]
    capture_devices = [cap for cap in capabilities if cap.is_video_capture]
    if not capture_devices:
        return devices
    capture_devices.sort(key=lambda cap: (0 if cap.is_usb else 1, *video_device_sort_key(cap.device)))
    return [cap.device for cap in capture_devices]


async def read_ui_form(request: Request) -> Dict[str, str]:
    try:
        form = await request.form()
        return {key: str(form.get(key) or "") for key in form.keys()}
    except AssertionError as exc:
        if "python-multipart" not in str(exc).lower():
            raise
        content_type = request.headers.get("content-type", "").lower()
        if "application/x-www-form-urlencoded" not in content_type:
            raise HTTPException(
                status_code=500,
                detail="Form support is unavailable for this request. Install python-multipart for multipart forms.",
            )
        raw = await request.body()
        if not raw:
            return {}
        parsed = parse_qs(raw.decode("utf-8", errors="replace"), keep_blank_values=True)
        return {key: values[-1] if values else "" for key, values in parsed.items()}


def canonical_ui_bag_row(conn: sqlite3.Connection) -> sqlite3.Row:
    bag_row = ensure_single_bag(conn)
    return conn.execute("SELECT * FROM bags WHERE bag_id = ?", (bag_row["bag_id"],)).fetchone() or bag_row


def parse_ui_bag_type(raw_bag_type: str) -> tuple[str, int]:
    bag_type = (raw_bag_type or "").strip().lower()
    if not bag_type:
        raise HTTPException(status_code=400, detail="Choose a bag size.")
    return bag_type, size_liters_for_bag_type(bag_type)


def build_ui_item_payload(form: Dict[str, str]) -> ItemWriteRequest:
    category_id = str(form.get("category_id") or "").strip()
    name = str(form.get("name") or "").strip()
    unit = str(form.get("unit") or "").strip()
    if not category_id:
        raise HTTPException(status_code=400, detail="Choose a category.")
    if not name:
        raise HTTPException(status_code=400, detail="Item name is required.")
    if not unit:
        raise HTTPException(status_code=400, detail="Unit is required.")

    quantity_raw = str(form.get("quantity") or "1").strip()
    try:
        quantity = float(quantity_raw)
    except ValueError:
        raise HTTPException(status_code=400, detail="Quantity must be a valid number.")
    if quantity <= 0:
        raise HTTPException(status_code=400, detail="Quantity must be greater than 0.")

    expiry_date = str(form.get("expiry_date") or "").strip() or None
    if expiry_date and parse_yyyy_mm_dd_to_epoch_ms(expiry_date) is None:
        raise HTTPException(status_code=400, detail="Expiry date must use YYYY-MM-DD.")

    return ItemWriteRequest(
        category_id=category_id,
        name=name,
        quantity=quantity,
        unit=unit,
        packed_status=str(form.get("packed_status") or "").lower() in {"on", "true", "1"},
        essential=False,
        expiry_date=expiry_date,
        minimum_quantity=0,
        condition_status="good",
        notes=str(form.get("notes") or "").strip(),
    )


def unexpected_ui_error_message(action: str, exc: Exception) -> str:
    detail = " ".join((str(exc) or exc.__class__.__name__).split())
    clipped = detail[:200] if detail else exc.__class__.__name__
    return f"{action} failed unexpectedly: {clipped}"


def scan_error_guidance(code: str) -> List[str]:
    tips = {
        "CAMERA_PREVIEW_FAILED": [
            "Check that the USB camera is plugged in and not used by another app.",
            "Confirm the Raspberry Pi user has camera and video access.",
        ],
        "CAMERA_CAPTURE_FAILED": [
            "Hold the QR steady and make sure the USB camera is connected.",
            "Try better lighting and make sure the camera lens is clean.",
        ],
        "NO_QR_DETECTED": [
            "Move the QR closer so it fills more of the frame.",
            "Hold the QR steady and reduce glare or shadows.",
            "Try brighter, even lighting and keep the camera focused.",
        ],
        "INVALID_QR_CONTENT": [
            "Use a GO BAG item QR in the format Item/Unit/Category/YYYY-MM-DD.",
            "If the QR is for a different feature, add the item manually instead.",
        ],
        "ITEM_CREATE_FAILED": [
            "Review the QR content and try the scan again.",
            "If the problem continues, add the item manually and inspect the QR format.",
        ],
        "DATABASE_SAVE_FAILED": [
            "Check that the Raspberry Pi storage is writable and has free space.",
            "Retry the scan after restarting the GO BAG backend if needed.",
        ],
        "QR_DECODE_FAILED": [
            "Retry the scan with the QR larger in the frame and in better lighting.",
            "If the issue continues, verify the Pi has its QR decoder tools installed.",
        ],
    }
    return tips.get(code, ["Retry the action and check the Raspberry Pi logs if the problem continues."])


def structured_error_detail(code: str, message: str, technical: str = "", guidance: Optional[List[str]] = None) -> dict:
    return {
        "ok": False,
        "code": code,
        "message": message,
        "guidance": guidance or scan_error_guidance(code),
        "technical": technical,
    }


def structured_http_exception(
    status_code: int,
    code: str,
    message: str,
    technical: str = "",
    guidance: Optional[List[str]] = None,
) -> HTTPException:
    return HTTPException(status_code=status_code, detail=structured_error_detail(code, message, technical=technical, guidance=guidance))


def error_payload_from_detail(detail: object, default_code: str = "SCAN_FAILED") -> dict:
    if isinstance(detail, dict):
        code = str(detail.get("code") or default_code)
        return {
            "ok": False,
            "code": code,
            "message": str(detail.get("message") or "The request failed."),
            "guidance": list(detail.get("guidance") or scan_error_guidance(code)),
            "technical": str(detail.get("technical") or ""),
        }
    message = str(detail or "The request failed.")
    return structured_error_detail(default_code, message)


def ui_message_for_error_payload(payload: dict) -> str:
    message = str(payload.get("message") or "The request failed.").strip()
    guidance = list(payload.get("guidance") or [])
    if guidance:
        return f"{message} Tip: {guidance[0]}"
    return message


def resolve_usb_camera_device(error_code: str) -> str:
    if not usb_scan_available():
        raise structured_http_exception(
            503,
            error_code,
            f"USB camera command is not available on this Pi ({USB_SCAN_CMD}).",
            technical=f"missing command: {USB_SCAN_CMD}",
        )
    scan_device = USB_SCAN_DEVICE
    if scan_device and not os.path.exists(scan_device):
        raise structured_http_exception(
            503,
            error_code,
            "The configured USB camera device was not found.",
            technical=scan_device,
        )
    if not scan_device:
        available_devices = available_usb_capture_devices()
        if available_devices:
            scan_device = available_devices[0]
        else:
            raise structured_http_exception(
                503,
                error_code,
                "No USB camera device was detected.",
                technical="no /dev/video* device found",
            )
    capability = read_usb_camera_capability(scan_device)
    logger.info(
        "USB camera device selected for %s: %s (%s)",
        error_code,
        scan_device,
        capability.technical or "capability unknown",
    )
    return scan_device


def configured_usb_preview_mode() -> str:
    mode = USB_PREVIEW_MODE if USB_PREVIEW_MODE in {"auto", "stream", "snapshot"} else "auto"
    if mode != USB_PREVIEW_MODE:
        logger.warning("Unknown USB preview mode %r, using auto", USB_PREVIEW_MODE)
    return mode


def candidate_usb_stream_profiles() -> List[tuple[int, int, int]]:
    profiles: List[tuple[int, int, int]] = []
    requested_profiles = [
        (USB_STREAM_WIDTH, USB_STREAM_HEIGHT, USB_STREAM_FPS),
        (USB_PREVIEW_WIDTH, USB_PREVIEW_HEIGHT, min(USB_STREAM_FPS, 12)),
        (640, 480, min(USB_STREAM_FPS, 15)),
        (640, 480, 10),
    ]
    for width, height, fps in requested_profiles:
        profile = (max(int(width), 1), max(int(height), 1), max(int(fps), 2))
        if profile not in profiles:
            profiles.append(profile)
    return profiles


def build_usb_stream_command(
    device: str,
    width: int = USB_STREAM_WIDTH,
    height: int = USB_STREAM_HEIGHT,
    fps: int = USB_STREAM_FPS,
) -> List[str]:
    return [
        USB_STREAM_CMD,
        "-hide_banner",
        "-loglevel",
        "error",
        "-fflags",
        "nobuffer",
        "-flags",
        "low_delay",
        "-f",
        "video4linux2",
        "-framerate",
        str(max(int(fps), 2)),
        "-video_size",
        f"{max(int(width), 1)}x{max(int(height), 1)}",
        "-i",
        device,
        "-an",
        "-vf",
        f"fps={max(int(fps), 2)}",
        "-q:v",
        "6",
        "-f",
        "mpjpeg",
        "pipe:1",
    ]


def build_usb_stream_probe_command(device: str, width: int, height: int, fps: int) -> List[str]:
    return [
        USB_STREAM_CMD,
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "video4linux2",
        "-framerate",
        str(max(int(fps), 2)),
        "-video_size",
        f"{max(int(width), 1)}x{max(int(height), 1)}",
        "-i",
        device,
        "-frames:v",
        "1",
        "-an",
        "-q:v",
        "6",
        "-vcodec",
        "mjpeg",
        "-f",
        "image2pipe",
        "pipe:1",
    ]


def probe_usb_stream_profile(device: str, width: int, height: int, fps: int) -> tuple[bool, str]:
    if not usb_stream_available():
        return False, f"missing command: {USB_STREAM_CMD}"
    command = build_usb_stream_probe_command(device, width, height, fps)
    logger.info("USB preview stream probe command: %s", shlex.join(command))
    try:
        result = subprocess.run(
            command,
            check=False,
            capture_output=True,
            timeout=USB_STREAM_PROBE_TIMEOUT_S,
        )
    except subprocess.TimeoutExpired:
        technical = "stream probe timeout"
        logger.warning("USB preview stream probe timed out on %s with %sx%s@%sfps", device, width, height, fps)
        return False, technical
    except OSError as exc:
        technical = exc.strerror or str(exc)
        logger.warning("USB preview stream probe could not start on %s: %s", device, technical)
        return False, technical

    stdout_bytes = result.stdout or b""
    stderr_text = decode_process_output(result.stderr)
    if result.returncode == 0 and stdout_bytes:
        logger.info(
            "USB preview stream probe succeeded on %s with %sx%s@%sfps (%s bytes)",
            device,
            width,
            height,
            fps,
            len(stdout_bytes),
        )
        return True, ""

    technical = f"returncode={result.returncode}; bytes={len(stdout_bytes)}"
    if stderr_text:
        technical = f"{technical}; {stderr_text[:240]}"
    logger.warning("USB preview stream probe failed on %s with %sx%s@%sfps: %s", device, width, height, fps, technical)
    return False, technical


def select_usb_preview_config(device: str) -> UsbPreviewConfig:
    requested_mode = configured_usb_preview_mode()
    snapshot_config = UsbPreviewConfig(
        mode="snapshot",
        device=device,
        width=USB_PREVIEW_WIDTH,
        height=USB_PREVIEW_HEIGHT,
        fps=0,
        preview_url="/camera/usb/preview.jpg",
        interval_ms=USB_PREVIEW_INTERVAL_MS,
    )
    if requested_mode == "snapshot":
        logger.info("USB preview mode forced to snapshot for %s", device)
        return snapshot_config

    if usb_stream_available():
        probe_notes: List[str] = []
        for width, height, fps in candidate_usb_stream_profiles():
            ok, technical = probe_usb_stream_profile(device, width, height, fps)
            probe_notes.append(f"{width}x{height}@{fps}: {'ok' if ok else technical or 'failed'}")
            if ok:
                return UsbPreviewConfig(
                    mode="stream",
                    device=device,
                    width=width,
                    height=height,
                    fps=fps,
                    preview_url=f"/camera/usb/stream.mjpg?width={width}&height={height}&fps={fps}",
                    interval_ms=USB_PREVIEW_INTERVAL_MS,
                    technical="; ".join(probe_notes[-3:]),
                )
        technical = "; ".join(probe_notes)[-500:]
        logger.warning("USB preview stream unavailable on %s, falling back to snapshot: %s", device, technical)
        snapshot_config.technical = technical
        return snapshot_config

    logger.info("USB preview streaming command is unavailable on this Pi; using snapshot preview")
    return snapshot_config


def image_dimensions_for_bytes(image_bytes: bytes) -> tuple[int, int]:
    try:
        with Image.open(io.BytesIO(image_bytes)) as image:
            return int(image.width), int(image.height)
    except Exception:
        return 0, 0


def candidate_usb_capture_profiles(purpose: Literal["preview", "scan", "session"], width: int, height: int) -> List[tuple[int, int]]:
    candidates: List[tuple[int, int]] = []
    requested = (max(int(width), 1), max(int(height), 1))
    for candidate in [
        requested,
        (USB_PREVIEW_WIDTH, USB_PREVIEW_HEIGHT),
        (640, 480),
    ]:
        normalized = (max(int(candidate[0]), 1), max(int(candidate[1]), 1))
        if normalized not in candidates:
            candidates.append(normalized)
    if purpose == "scan" and (USB_SCAN_WIDTH, USB_SCAN_HEIGHT) not in candidates:
        candidates.insert(0, (USB_SCAN_WIDTH, USB_SCAN_HEIGHT))
    return candidates


def preferred_usb_capture_palettes() -> List[str]:
    preferred = ["MJPG", "YUYV", "YUY2", "YU12", "RGB3", "BGR3"]
    return preferred


def select_supported_size(sizes: List[tuple[int, int]], target_width: int, target_height: int) -> Optional[tuple[int, int]]:
    if not sizes:
        return None
    target = (target_width, target_height)
    if target in sizes:
        return target
    target_area = target_width * target_height
    return min(
        sizes,
        key=lambda size: (
            abs(size[0] - target_width) + abs(size[1] - target_height),
            abs((size[0] * size[1]) - target_area),
        ),
    )


def build_usb_capture_attempts(
    purpose: Literal["preview", "scan", "session"],
    device: str,
    width: int,
    height: int,
) -> List[tuple[int, int, Optional[str]]]:
    capability = read_usb_camera_capability(device)
    attempts: List[tuple[int, int, Optional[str]]] = []
    for target_width, target_height in candidate_usb_capture_profiles(purpose, width, height):
        for palette in preferred_usb_capture_palettes():
            supported_sizes = capability.palette_sizes.get(palette, [])
            selected_size = select_supported_size(supported_sizes, target_width, target_height)
            if selected_size:
                attempt = (selected_size[0], selected_size[1], palette)
                if attempt not in attempts:
                    attempts.append(attempt)
        plain_attempt = (target_width, target_height, None)
        if plain_attempt not in attempts:
            attempts.append(plain_attempt)
    return attempts


def capture_usb_camera_frame_with_device(
    device: str,
    purpose: Literal["preview", "scan", "session"],
    width: int,
    height: int,
    error_code: str,
) -> UsbCaptureResult:
    with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as tmp:
        out_path = tmp.name

    try:
        attempt_notes: List[str] = []
        skip_frames = USB_SCAN_SKIP_FRAMES if purpose == "scan" else min(max(USB_SCAN_SKIP_FRAMES, 6), 8)
        for attempt_index, (attempt_width, attempt_height, palette) in enumerate(
            build_usb_capture_attempts(purpose, device, width, height),
            start=1,
        ):
            with open(out_path, "wb"):
                pass
            capture_cmd = [
                USB_SCAN_CMD,
                "-r",
                f"{attempt_width}x{attempt_height}",
                "--no-banner",
                "-d",
                device,
            ]
            if palette:
                capture_cmd.extend(["-p", palette])
            if skip_frames > 0:
                capture_cmd.extend(["-S", str(skip_frames)])
            capture_cmd.append(out_path)
            logger.info("USB %s command attempt %s: %s", purpose, attempt_index, shlex.join(capture_cmd))
            capture = subprocess.run(
                capture_cmd,
                check=False,
                capture_output=True,
                timeout=USB_SCAN_TIMEOUT_S,
            )
            if capture.returncode != 0:
                capture_error = decode_process_output(capture.stderr) or decode_process_output(capture.stdout)
                attempt_notes.append(
                    f"{attempt_width}x{attempt_height}/{palette or 'auto'}: returncode={capture.returncode}; {(capture_error or 'capture failed')[:200]}"
                )
                logger.warning("USB %s failed on attempt %s (%s)", purpose, attempt_index, attempt_notes[-1])
                continue
            if not os.path.exists(out_path) or os.path.getsize(out_path) == 0:
                attempt_notes.append(f"{attempt_width}x{attempt_height}/{palette or 'auto'}: empty capture file")
                logger.warning("USB %s produced no image on attempt %s", purpose, attempt_index)
                continue
            with open(out_path, "rb") as captured_file:
                image_bytes = captured_file.read()
            detected_width, detected_height = image_dimensions_for_bytes(image_bytes)
            if detected_width <= 0 or detected_height <= 0:
                attempt_notes.append(f"{attempt_width}x{attempt_height}/{palette or 'auto'}: invalid image bytes")
                logger.warning("USB %s returned unreadable image bytes on attempt %s", purpose, attempt_index)
                continue
            logger.info(
                "USB %s image saved to %s (%sx%s, %s bytes) on attempt %s",
                purpose,
                out_path,
                detected_width,
                detected_height,
                len(image_bytes),
                attempt_index,
            )
            return UsbCaptureResult(
                purpose="preview" if purpose == "session" else purpose,
                device=device,
                file_path=out_path,
                width=detected_width,
                height=detected_height,
                image_bytes=image_bytes,
            )

        technical = "; ".join(attempt_notes)[-500:] or "capture returned no valid image"
        raise structured_http_exception(
            503,
            error_code,
            "The USB camera could not capture a usable image.",
            technical=technical,
        )
    except subprocess.TimeoutExpired:
        raise structured_http_exception(504, error_code, "The USB camera timed out while capturing.", technical="capture timeout")
    except OSError as exc:
        raise structured_http_exception(
            503,
            error_code,
            "The USB camera could not start.",
            technical=exc.strerror or str(exc),
        )
    finally:
        try:
            os.remove(out_path)
        except OSError:
            pass


def capture_usb_camera_frame(purpose: Literal["preview", "scan"]) -> UsbCaptureResult:
    error_code = "CAMERA_PREVIEW_FAILED" if purpose == "preview" else "CAMERA_CAPTURE_FAILED"
    device = resolve_usb_camera_device(error_code)
    width = USB_PREVIEW_WIDTH if purpose == "preview" else USB_SCAN_WIDTH
    height = USB_PREVIEW_HEIGHT if purpose == "preview" else USB_SCAN_HEIGHT
    return capture_usb_camera_frame_with_device(device, purpose, width, height, error_code)


def build_decode_variants(image_bytes: bytes) -> List[tuple[str, Image.Image]]:
    with Image.open(io.BytesIO(image_bytes)) as source_image:
        base = ImageOps.exif_transpose(source_image).convert("RGB")
        gray = ImageOps.autocontrast(ImageOps.grayscale(base))
        upscale_width = min(max(gray.width * 2, gray.width), 2560)
        upscale_height = min(max(gray.height * 2, gray.height), 2560)
        upscaled = gray.resize((upscale_width, upscale_height), IMAGE_RESAMPLING)
        sharpened = ImageEnhance.Sharpness(upscaled).enhance(2.4)
        high_contrast = ImageEnhance.Contrast(sharpened).enhance(1.8)
        threshold_160 = high_contrast.point(lambda value: 255 if value > 160 else 0).convert("L")
        threshold_190 = high_contrast.point(lambda value: 255 if value > 190 else 0).convert("L")
        return [
            ("original", base.copy()),
            ("grayscale_autocontrast", gray.copy()),
            ("upscaled", upscaled.copy()),
            ("upscaled_sharpened", sharpened.copy()),
            ("high_contrast", high_contrast.copy()),
            ("threshold_160", threshold_160.copy()),
            ("threshold_190", threshold_190.copy()),
        ]


def decode_qr_with_pyzbar(variant_name: str, image: Image.Image) -> tuple[Optional[str], str]:
    if pyzbar_decode is None or ZBarSymbol is None:
        return None, "pyzbar unavailable"
    try:
        decoded_rows = pyzbar_decode(image, symbols=[ZBarSymbol.QRCODE])
        if decoded_rows:
            content = decoded_rows[0].data.decode("utf-8", errors="replace").strip()
            return content, f"pyzbar found {len(decoded_rows)} QR symbol(s)"
        return None, "pyzbar found no QR symbols"
    except Exception as exc:
        logger.warning("pyzbar decode failed for %s: %s", variant_name, exc)
        return None, str(exc)


def decode_qr_with_zbarimg(variant_name: str, image: Image.Image) -> tuple[Optional[str], str]:
    if not qr_decode_available():
        return None, f"{QR_DECODE_CMD} unavailable"
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tmp:
        variant_path = tmp.name
    try:
        image.save(variant_path, format="PNG")
        decode_attempts = [
            [QR_DECODE_CMD, "--quiet", "--raw", variant_path],
            [QR_DECODE_CMD, "--raw", variant_path],
        ]
        last_error = ""
        for command in decode_attempts:
            logger.info("USB scan decode command (%s): %s", variant_name, shlex.join(command))
            decode = subprocess.run(
                command,
                check=False,
                capture_output=True,
                timeout=USB_SCAN_TIMEOUT_S,
            )
            decode_output = decode_process_output(decode.stdout)
            decode_error = decode_process_output(decode.stderr) or decode_output
            if decode.returncode == 0 and decode_output:
                return decode_output.splitlines()[0].strip(), f"zbarimg succeeded with {variant_name}"
            if "--quiet" in command and "option" in decode_error.lower() and "quiet" in decode_error.lower():
                continue
            if decode_error:
                last_error = decode_error
        return None, last_error or "zbarimg returned no QR content"
    except subprocess.TimeoutExpired:
        return None, "zbarimg timed out"
    except OSError as exc:
        return None, exc.strerror or str(exc)
    finally:
        try:
            os.remove(variant_path)
        except OSError:
            pass


def decode_qr_from_image_bytes(image_bytes: bytes) -> str:
    if pyzbar_decode is None and not qr_decode_available():
        raise structured_http_exception(
            503,
            "QR_DECODE_FAILED",
            "No QR decoder is available on this Raspberry Pi.",
            technical=f"pyzbar unavailable; {QR_DECODE_CMD} unavailable",
        )

    attempt_notes: List[str] = []
    for variant_name, image in build_decode_variants(image_bytes):
        logger.info("USB scan decode attempt using variant: %s", variant_name)
        content, note = decode_qr_with_pyzbar(variant_name, image)
        attempt_notes.append(f"{variant_name}: {note}")
        if content:
            logger.info("USB scan decoded QR with pyzbar variant %s", variant_name)
            return content
        content, note = decode_qr_with_zbarimg(variant_name, image)
        attempt_notes.append(f"{variant_name}: {note}")
        if content:
            logger.info("USB scan decoded QR with zbarimg variant %s", variant_name)
            return content

    technical = "; ".join(attempt_notes)[-900:]
    logger.info("USB scan decode attempts exhausted: %s", technical)
    raise structured_http_exception(
        400,
        "NO_QR_DETECTED",
        "No QR code was detected in the captured image.",
        technical=technical,
    )


def scan_qr_from_usb_camera() -> str:
    capture = capture_usb_camera_frame("scan")
    return decode_qr_from_image_bytes(capture.image_bytes)


def save_scanned_qr_content(conn: sqlite3.Connection, raw_content: str, requested_bag_id: str = "") -> dict:
    bag_row = canonical_ui_bag_row(conn)
    bag_id = bag_row["bag_id"]
    if requested_bag_id and requested_bag_id != bag_id:
        logger.info("Ignoring requested bag id %s and using canonical bag %s", requested_bag_id, bag_id)

    normalized_content = str(raw_content or "").strip()
    if not normalized_content:
        raise structured_http_exception(
            400,
            "INVALID_QR_CONTENT",
            "A QR code was found, but it did not contain any readable GO BAG item data.",
            technical="empty qr content",
        )
    logger.info("USB scan decoded raw content: %s", normalized_content[:240])

    try:
        parsed = parse_item_qr_content(normalized_content)
    except HTTPException as exc:
        raise structured_http_exception(
            400,
            "INVALID_QR_CONTENT",
            "A QR code was found, but it is not a valid GO BAG item QR.",
            technical=normalized_content[:240],
        ) from exc

    payload = ItemWriteRequest(
        category_id=category_id_for_name(parsed.category),
        name=parsed.name,
        quantity=1,
        unit=parsed.unit,
        packed_status=False,
        essential=False,
        expiry_date=parsed.expiry_date,
        minimum_quantity=0,
        condition_status="good",
        notes="Added from QR scan",
    )

    try:
        written = merge_or_write_item_record(conn, bag_id, payload)
    except HTTPException as exc:
        payload_detail = error_payload_from_detail(exc.detail, default_code="ITEM_CREATE_FAILED")
        raise structured_http_exception(
            400,
            "ITEM_CREATE_FAILED",
            "The QR code was read, but the item could not be added to inventory.",
            technical=payload_detail.get("message", ""),
        ) from exc
    except sqlite3.Error as exc:
        logger.exception("USB scan database save failed")
        raise structured_http_exception(
            500,
            "DATABASE_SAVE_FAILED",
            "The item was scanned, but it could not be saved to the GO BAG.",
            technical=str(exc),
        ) from exc

    logger.info(
        "USB scan parsed item: name=%s unit=%s category=%s expiry=%s",
        parsed.name,
        parsed.unit,
        parsed.category,
        parsed.expiry_date,
    )
    return {
        "ok": True,
        "code": "SCAN_SUCCESS",
        "message": f"Scanned {parsed.name} into inventory.",
        "raw_content": normalized_content,
        "parsed": {
            "name": parsed.name,
            "unit": parsed.unit,
            "category": parsed.category,
            "expiry_date": parsed.expiry_date,
        },
        "item": written.model_dump(),
        "bag_id": bag_id,
    }


def perform_usb_scan_and_save(conn: sqlite3.Connection, requested_bag_id: str = "") -> dict:
    capture = capture_usb_camera_frame("scan")
    raw_content = decode_qr_from_image_bytes(capture.image_bytes)
    return save_scanned_qr_content(conn, raw_content, requested_bag_id=requested_bag_id)


def qr_data_uri_for_payload(payload: dict) -> str:
    image = qrcode.make(json.dumps(payload))
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return "data:image/png;base64," + base64.b64encode(buffer.getvalue()).decode("ascii")


def build_inventory_groups(item_rows: List[sqlite3.Row]) -> List[InventoryGroupView]:
    grouped: Dict[str, List[sqlite3.Row]] = {}
    for row in item_rows:
        grouped.setdefault(normalized_identity_key(row["name"], row["unit"], row["category"]), []).append(row)

    groups: List[InventoryGroupView] = []
    for rows in grouped.values():
        ordered = [row_to_item_record(row) for row in sorted(rows, key=lambda item: (item["expiry_date_ms"] or 2**62, item["name"].lower()))]
        first = ordered[0]
        groups.append(
            InventoryGroupView(
                bag_id=first.bag_id,
                name=first.name,
                category=normalize_category(rows[0]["category"]),
                unit=first.unit,
                total_quantity=sum(item.quantity for item in ordered),
                batches=[
                    InventoryBatchView(
                        item=item,
                        expiry_label=item.expiry_date or "No expiration",
                    )
                    for item in ordered
                ],
            )
        )
    return sorted(groups, key=lambda group: (group.category, group.name.lower()))


def get_bag_row_or_404(conn: sqlite3.Connection, bag_id: str) -> sqlite3.Row:
    row = conn.execute("SELECT * FROM bags WHERE bag_id = ?", (bag_id,)).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="Bag not found")
    return row


def get_item_row_or_404(conn: sqlite3.Connection, item_id: str) -> sqlite3.Row:
    row = conn.execute("SELECT * FROM items WHERE id = ?", (item_id,)).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="Item not found")
    return row


def get_category_name(conn: sqlite3.Connection, category_id: str) -> str:
    row = conn.execute("SELECT name FROM categories WHERE id = ?", (category_id,)).fetchone()
    if not row:
        raise HTTPException(status_code=400, detail="Category not found")
    return row["name"]


def write_bag_record(conn: sqlite3.Connection, bag_id: str, payload: Union[BagCreateRequest, BagUpdateRequest]) -> BagRecord:
    current_time = now_ms()
    bag_type = payload.bag_type.strip().lower() or "44l"
    size_liters = size_liters_for_bag_type(bag_type)
    template_id = template_id_for_size(size_liters)
    existing = conn.execute("SELECT created_at FROM bags WHERE bag_id = ?", (bag_id,)).fetchone()
    created_at = existing["created_at"] if existing and existing["created_at"] else current_time
    conn.execute(
        """
        INSERT INTO bags(bag_id, name, size_liters, template_id, updated_at, updated_by, bag_type, readiness_status, last_checked_at, created_at)
        VALUES(?, ?, ?, ?, ?, ?, ?, 'incomplete', ?, ?)
        ON CONFLICT(bag_id) DO UPDATE SET
          name=excluded.name,
          size_liters=excluded.size_liters,
          template_id=excluded.template_id,
          updated_at=excluded.updated_at,
          updated_by=excluded.updated_by,
          bag_type=excluded.bag_type,
          last_checked_at=excluded.last_checked_at
        """,
        (
            bag_id,
            payload.name.strip(),
            size_liters,
            template_id,
            current_time,
            get_meta(conn, "pi_device_id") or "pi",
            bag_type,
            getattr(payload, "last_checked_at", None),
            created_at,
        ),
    )
    update_bag_readiness(conn, bag_id)
    update_device_state(conn)
    return row_to_bag_record(get_bag_row_or_404(conn, bag_id))


def write_item_record(conn: sqlite3.Connection, item_id: str, bag_id: str, payload: ItemWriteRequest) -> ItemRecord:
    get_bag_row_or_404(conn, bag_id)
    category_name = get_category_name(conn, payload.category_id)
    current_time = now_ms()
    existing = conn.execute("SELECT created_at FROM items WHERE id = ?", (item_id,)).fetchone()
    created_at = existing["created_at"] if existing and existing["created_at"] else current_time
    expiry_date_ms = parse_yyyy_mm_dd_to_epoch_ms(payload.expiry_date) if payload.expiry_date else None
    if payload.expiry_date and expiry_date_ms is None:
        raise HTTPException(status_code=400, detail="Expiry date must use YYYY-MM-DD")
    conn.execute(
        """
        INSERT INTO items(
          id, bag_id, name, category, quantity, unit, packed_status, notes, expiry_date_ms,
          deleted, updated_at, updated_by, category_id, essential, minimum_quantity, condition_status, created_at
        )
        VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
          bag_id=excluded.bag_id,
          name=excluded.name,
          category=excluded.category,
          quantity=excluded.quantity,
          unit=excluded.unit,
          packed_status=excluded.packed_status,
          notes=excluded.notes,
          expiry_date_ms=excluded.expiry_date_ms,
          deleted=0,
          updated_at=excluded.updated_at,
          updated_by=excluded.updated_by,
          category_id=excluded.category_id,
          essential=excluded.essential,
          minimum_quantity=excluded.minimum_quantity,
          condition_status=excluded.condition_status
        """,
        (
            item_id,
            bag_id,
            payload.name.strip(),
            category_name,
            payload.quantity,
            payload.unit.strip(),
            int(payload.packed_status),
            payload.notes.strip(),
            expiry_date_ms,
            current_time,
            get_meta(conn, "pi_device_id") or "pi",
            payload.category_id,
            int(payload.essential),
            payload.minimum_quantity,
            payload.condition_status.strip() or "good",
            created_at,
        ),
    )
    update_bag_readiness(conn, bag_id)
    update_device_state(conn)
    return row_to_item_record(get_item_row_or_404(conn, item_id))


def get_device_status_payload(conn: sqlite3.Connection) -> DeviceStatusResponse:
    update_device_state(conn)
    row = conn.execute("SELECT * FROM device_state WHERE id = 'primary'").fetchone()
    pair = active_pair_code(conn) or create_pair_code(conn)
    conn.commit()
    return DeviceStatusResponse(
        id=row["id"],
        device_name=row["device_name"],
        last_sync_at=row["last_sync_at"],
        connection_status=row["connection_status"],
        pending_changes_count=row["pending_changes_count"],
        local_ip=row["local_ip"],
        updated_at=row["updated_at"],
        pi_device_id=get_meta(conn, "pi_device_id") or "",
        pair_code=pair["code"],
        paired_devices=conn.execute("SELECT COUNT(*) AS c FROM tokens WHERE revoked = 0").fetchone()["c"],
        database_path=os.path.join(os.getenv("GOBAG_DATA_DIR", DEFAULT_DATA_DIR), "gobag.db"),
    )


def pi_model() -> str:
    model_path = "/proc/device-tree/model"
    try:
        with open(model_path, "r", encoding="utf-8") as f:
            return f.read().strip("\x00").strip()
    except OSError:
        return "unknown"


def camera_command_available() -> bool:
    return shutil.which(CAMERA_CMD) is not None


def capture_camera_jpeg() -> bytes:
    if not CAMERA_ENABLED:
        raise HTTPException(status_code=503, detail="Camera is disabled by configuration")
    if not camera_command_available():
        raise HTTPException(status_code=503, detail=f"Camera command not found: {CAMERA_CMD}")

    with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as tmp:
        out_path = tmp.name

    cmd = [
        CAMERA_CMD,
        "--output",
        out_path,
        "--nopreview",
        "--width",
        str(CAMERA_WIDTH),
        "--height",
        str(CAMERA_HEIGHT),
        "--timeout",
        str(CAMERA_WARMUP_MS),
    ]
    try:
        proc = subprocess.run(
            cmd,
            check=False,
            capture_output=True,
            timeout=CAMERA_TIMEOUT_S,
        )
        if proc.returncode != 0:
            error_text = decode_process_output(proc.stderr) or decode_process_output(proc.stdout)
            raise HTTPException(
                status_code=503,
                detail=f"Camera capture failed ({proc.returncode}): {error_text[:200] or 'camera command returned an error'}",
            )
        if not os.path.exists(out_path) or os.path.getsize(out_path) == 0:
            raise HTTPException(status_code=503, detail="Camera did not produce an image. Check camera access and file permissions.")

        with open(out_path, "rb") as f:
            return f.read()
    except subprocess.TimeoutExpired:
        raise HTTPException(status_code=504, detail="Camera capture timed out")
    except OSError as exc:
        raise HTTPException(status_code=503, detail=f"Camera capture could not start: {exc.strerror or str(exc)}")
    finally:
        try:
            os.remove(out_path)
        except OSError:
            pass


@app.on_event("startup")
def startup() -> None:
    init_db()


@app.on_event("shutdown")
def shutdown() -> None:
    usb_camera_session_manager.stop(clear_state=True)


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "camera_enabled": CAMERA_ENABLED,
        "camera_cmd_available": camera_command_available(),
        "usb_scan_cmd_available": usb_scan_available(),
        "usb_stream_cmd_available": usb_stream_available(),
        "qr_decode_cmd_available": qr_decode_available(),
    }


@app.get("/system/info")
def system_info() -> dict:
    return {
        "platform": platform.platform(),
        "machine": platform.machine(),
        "python_version": platform.python_version(),
        "pi_model": pi_model(),
        "camera_enabled": CAMERA_ENABLED,
        "camera_cmd": CAMERA_CMD,
        "camera_cmd_available": camera_command_available(),
        "usb_scan_cmd": USB_SCAN_CMD,
        "usb_scan_cmd_available": usb_scan_available(),
        "usb_stream_cmd": USB_STREAM_CMD,
        "usb_stream_cmd_available": usb_stream_available(),
        "qr_decode_cmd": QR_DECODE_CMD,
        "qr_decode_cmd_available": qr_decode_available(),
    }


@app.get("/camera/status")
def camera_status() -> dict:
    available_devices = available_usb_camera_devices()
    capture_devices = available_usb_capture_devices()
    preview_mode_preference = configured_usb_preview_mode()
    preview_config: Optional[UsbPreviewConfig] = None
    preview_error = ""
    session_status = usb_camera_session_manager.snapshot()
    if available_devices:
        try:
            preview_config = select_usb_preview_config(available_devices[0])
        except HTTPException as exc:
            preview_error = error_payload_from_detail(exc.detail, default_code="CAMERA_PREVIEW_FAILED").get("message", "")
    return {
        "camera_enabled": CAMERA_ENABLED,
        "camera_cmd": CAMERA_CMD,
        "camera_cmd_available": camera_command_available(),
        "usb_scan_cmd": USB_SCAN_CMD,
        "usb_scan_cmd_available": usb_scan_available(),
        "usb_v4l2ctl_cmd": USB_V4L2CTL_CMD,
        "usb_v4l2ctl_cmd_available": usb_v4l2ctl_available(),
        "usb_stream_cmd": USB_STREAM_CMD,
        "usb_stream_cmd_available": usb_stream_available(),
        "qr_decode_cmd": QR_DECODE_CMD,
        "qr_decode_cmd_available": qr_decode_available(),
        "pyzbar_available": pyzbar_decode is not None,
        "resolution": {"width": CAMERA_WIDTH, "height": CAMERA_HEIGHT},
        "usb_preview_resolution": {"width": USB_PREVIEW_WIDTH, "height": USB_PREVIEW_HEIGHT},
        "usb_stream_resolution": {"width": USB_STREAM_WIDTH, "height": USB_STREAM_HEIGHT},
        "usb_stream_fps": USB_STREAM_FPS,
        "usb_preview_mode_preference": preview_mode_preference,
        "usb_preview_mode_effective": preview_config.mode if preview_config else "unavailable",
        "usb_preview_device": preview_config.device if preview_config else (available_devices[0] if available_devices else ""),
        "usb_preview_url": preview_config.preview_url if preview_config else "",
        "usb_preview_probe_timeout_s": USB_STREAM_PROBE_TIMEOUT_S,
        "usb_session_interval_ms": USB_SESSION_INTERVAL_MS,
        "usb_scan_skip_frames": USB_SCAN_SKIP_FRAMES,
        "usb_camera_devices": available_devices,
        "usb_capture_devices": capture_devices,
        "usb_session_active": bool(session_status.get("active")),
        "usb_session_running": bool(session_status.get("running")),
        "usb_session_device": str(session_status.get("device") or ""),
        "usb_session_frame_id": int(session_status.get("frame_id") or 0),
        "usb_preview_error": preview_error,
        "usb_scan_resolution": {"width": USB_SCAN_WIDTH, "height": USB_SCAN_HEIGHT},
    }


@app.get("/camera/capture.jpg")
def camera_capture() -> Response:
    image = capture_camera_jpeg()
    return Response(content=image, media_type="image/jpeg")


@app.get("/camera/usb/preview/status")
def usb_camera_preview_status() -> JSONResponse:
    try:
        device = resolve_usb_camera_device("CAMERA_PREVIEW_FAILED")
        preview_config = select_usb_preview_config(device)
        payload = {
            "ok": True,
            "message": "USB camera preview is ready.",
            "device": device,
            "preview_url": preview_config.preview_url,
            "snapshot_preview_url": "/camera/usb/preview.jpg",
            "preview_mode": preview_config.mode,
            "resolution": {
                "width": preview_config.width,
                "height": preview_config.height,
            },
            "interval_ms": preview_config.interval_ms,
            "scan_interval_ms": USB_AUTO_SCAN_INTERVAL_MS,
            "auto_scan": True,
            "auto_close_on_success": True,
            "stream_fps": preview_config.fps if preview_config.mode == "stream" else 0,
            "preview_mode_preference": configured_usb_preview_mode(),
            "technical": preview_config.technical,
        }
        logger.info(
            "USB preview start result: ready on %s using %s mode (%sx%s @ %sfps)",
            device,
            preview_config.mode,
            preview_config.width,
            preview_config.height,
            preview_config.fps,
        )
        return JSONResponse(payload)
    except HTTPException as exc:
        payload = error_payload_from_detail(exc.detail, default_code="CAMERA_PREVIEW_FAILED")
        logger.warning("USB preview start failed: %s", payload.get("technical") or payload.get("message"))
        return JSONResponse(payload, status_code=exc.status_code)


@app.post("/camera/usb/session/start")
async def usb_camera_session_start(request: Request) -> JSONResponse:
    form = await read_ui_form(request)
    requested_bag_id = str(form.get("bag_id") or "").strip()
    with db_conn() as conn:
        bag_id = canonical_ui_bag_row(conn)["bag_id"]
    if requested_bag_id and requested_bag_id != bag_id:
        logger.info("Ignoring requested bag id %s and starting USB session for canonical bag %s", requested_bag_id, bag_id)
    try:
        payload = usb_camera_session_manager.start(bag_id=bag_id)
        payload["message"] = "USB camera session started."
        return JSONResponse(payload)
    except HTTPException as exc:
        payload = error_payload_from_detail(exc.detail, default_code="CAMERA_PREVIEW_FAILED")
        logger.warning("USB camera session start failed: %s", payload.get("technical") or payload.get("message"))
        return JSONResponse(payload, status_code=exc.status_code)


@app.post("/camera/usb/session/stop")
def usb_camera_session_stop() -> JSONResponse:
    usb_camera_session_manager.stop(clear_state=True)
    return JSONResponse({"ok": True, "message": "USB camera session stopped."})


@app.get("/camera/usb/session/status")
def usb_camera_session_status() -> JSONResponse:
    payload = usb_camera_session_manager.snapshot()
    status_code = 200 if payload.get("ok", True) else 503
    return JSONResponse(payload, status_code=status_code)


@app.get("/camera/usb/session/frame.jpg")
def usb_camera_session_frame() -> Response:
    return usb_camera_session_manager.frame_response()


def generate_usb_camera_stream(command: List[str]) -> Iterator[bytes]:
    logger.info("USB preview stream command: %s", shlex.join(command))
    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=0,
    )
    try:
        if process.stdout is None:
            raise structured_http_exception(
                503,
                "CAMERA_PREVIEW_FAILED",
                "USB camera streaming could not start.",
                technical="stdout pipe unavailable",
            )
        while True:
            chunk = process.stdout.read(16384)
            if not chunk:
                break
            yield chunk
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
        stderr_text = decode_process_output(process.stderr.read() if process.stderr else b"")
        if stderr_text:
            logger.warning("USB preview stream closed: %s", stderr_text[:300])


@app.get("/camera/usb/stream.mjpg")
def usb_camera_preview_stream(width: int = USB_STREAM_WIDTH, height: int = USB_STREAM_HEIGHT, fps: int = USB_STREAM_FPS) -> StreamingResponse:
    if not usb_stream_available():
        raise structured_http_exception(
            503,
            "CAMERA_PREVIEW_FAILED",
            "USB camera streaming is not available on this Pi.",
            technical=f"missing command: {USB_STREAM_CMD}",
        )
    device = resolve_usb_camera_device("CAMERA_PREVIEW_FAILED")
    width = max(min(int(width), 1920), 160)
    height = max(min(int(height), 1080), 120)
    fps = max(min(int(fps), 30), 2)
    command = build_usb_stream_command(device, width=width, height=height, fps=fps)
    return StreamingResponse(
        generate_usb_camera_stream(command),
        media_type="multipart/x-mixed-replace; boundary=ffmpeg",
        headers={"Cache-Control": "no-store, max-age=0"},
    )


@app.get("/camera/usb/preview.jpg")
def usb_camera_preview_image() -> Response:
    capture = capture_usb_camera_frame("preview")
    return Response(content=capture.image_bytes, media_type="image/jpeg", headers={"Cache-Control": "no-store, max-age=0"})


@app.get("/time")
def get_time() -> dict:
    return {"server_time_ms": now_ms()}


@app.get("/templates")
def get_templates() -> dict:
    with db_conn() as conn:
        rows = conn.execute("SELECT * FROM templates ORDER BY template_id, category, name").fetchall()
    return {"templates": [RecommendedItem(**dict(r)).model_dump() for r in rows]}


@app.get("/device/status", response_model=DeviceStatusResponse)
def device_status() -> DeviceStatusResponse:
    with db_conn() as conn:
        return get_device_status_payload(conn)


@app.get("/categories", response_model=List[CategoryRecord])
def get_categories() -> List[CategoryRecord]:
    with db_conn() as conn:
        rows = conn.execute("SELECT * FROM categories ORDER BY name").fetchall()
    return [row_to_category_record(row) for row in rows]


@app.get("/bags", response_model=List[BagRecord])
def get_bags() -> List[BagRecord]:
    with db_conn() as conn:
        bag_row = ensure_single_bag(conn)
        update_bag_readiness(conn, bag_row["bag_id"])
        update_device_state(conn)
        rows = conn.execute("SELECT * FROM bags ORDER BY name").fetchall()
        conn.commit()
    return [row_to_bag_record(row) for row in rows]


@app.post("/bags", response_model=BagRecord, status_code=201)
def create_bag(payload: BagCreateRequest) -> BagRecord:
    if not payload.name.strip():
        raise HTTPException(status_code=400, detail="Bag name is required")
    with db_conn() as conn:
        bag_row = ensure_single_bag(conn)
        bag = write_bag_record(
            conn,
            bag_row["bag_id"],
            BagUpdateRequest(
                name=payload.name.strip(),
                bag_type=payload.bag_type,
                last_checked_at=bag_row["last_checked_at"],
            ),
        )
        conn.commit()
        return bag


@app.put("/bags/{bag_id}", response_model=BagRecord)
def update_bag(bag_id: str, payload: BagUpdateRequest) -> BagRecord:
    if not payload.name.strip():
        raise HTTPException(status_code=400, detail="Bag name is required")
    with db_conn() as conn:
        bag_row = ensure_single_bag(conn)
        if bag_id != bag_row["bag_id"]:
            raise HTTPException(status_code=404, detail="Bag not found")
        bag = write_bag_record(conn, bag_id, payload)
        conn.commit()
        return bag


@app.delete("/bags/{bag_id}")
def delete_bag(bag_id: str) -> dict:
    with db_conn() as conn:
        bag_row = ensure_single_bag(conn)
        if bag_id != bag_row["bag_id"]:
            raise HTTPException(status_code=404, detail="Bag not found")
    raise HTTPException(status_code=400, detail="This Raspberry Pi keeps one local GO BAG. Update its size instead of deleting it.")


@app.get("/bags/{bag_id}/items", response_model=List[ItemRecord])
def get_bag_items(bag_id: str) -> List[ItemRecord]:
    with db_conn() as conn:
        get_bag_row_or_404(conn, bag_id)
        rows = conn.execute("SELECT * FROM items WHERE bag_id = ? AND deleted = 0 ORDER BY category, name", (bag_id,)).fetchall()
    return [row_to_item_record(row) for row in rows]


@app.post("/bags/{bag_id}/items", response_model=ItemRecord, status_code=201)
def create_item(bag_id: str, payload: ItemWriteRequest) -> ItemRecord:
    if not payload.name.strip():
        raise HTTPException(status_code=400, detail="Item name is required")
    if not payload.unit.strip():
        raise HTTPException(status_code=400, detail="Unit is required")
    with db_conn() as conn:
        item = merge_or_write_item_record(conn, bag_id, payload)
        conn.commit()
        return item


@app.put("/items/{item_id}", response_model=ItemRecord)
def update_item(item_id: str, payload: ItemWriteRequest) -> ItemRecord:
    if not payload.name.strip():
        raise HTTPException(status_code=400, detail="Item name is required")
    if not payload.unit.strip():
        raise HTTPException(status_code=400, detail="Unit is required")
    with db_conn() as conn:
        current = get_item_row_or_404(conn, item_id)
        item = merge_or_write_item_record(conn, current["bag_id"], payload, item_id=item_id)
        conn.commit()
        return item


@app.delete("/items/{item_id}")
def delete_item(item_id: str) -> dict:
    with db_conn() as conn:
        current = get_item_row_or_404(conn, item_id)
        conn.execute(
            """
            UPDATE items
            SET deleted = 1, updated_at = ?, updated_by = ?
            WHERE id = ?
            """,
            (now_ms(), get_meta(conn, "pi_device_id") or "pi", item_id),
        )
        update_bag_readiness(conn, current["bag_id"])
        update_device_state(conn)
        conn.commit()
    return {"deleted": True, "item_id": item_id}


@app.get("/alerts", response_model=List[AlertItem])
def get_alerts() -> List[AlertItem]:
    with db_conn() as conn:
        return compute_alerts(conn, now_ms())


@app.get("/sync/status", response_model=SyncStatusResponse)
def sync_status() -> SyncStatusResponse:
    with db_conn() as conn:
        update_device_state(conn)
        row = conn.execute("SELECT * FROM device_state WHERE id = 'primary'").fetchone()
        conn.commit()
    return SyncStatusResponse(**dict(row))


@app.get("/settings", response_model=SettingsRecord)
def get_settings() -> SettingsRecord:
    with db_conn() as conn:
        ensure_single_bag(conn)
        row = conn.execute("SELECT * FROM settings WHERE id = 'primary'").fetchone()
    return SettingsRecord(
        id=row["id"],
        default_bag_id=row["default_bag_id"],
        language=row["language"],
        notifications_enabled=bool(row["notifications_enabled"]),
        last_connected_device=row["last_connected_device"],
    )


@app.get("/device/bag", response_model=BagRecord)
def get_device_bag() -> BagRecord:
    with db_conn() as conn:
        bag_row = ensure_single_bag(conn)
        update_bag_readiness(conn, bag_row["bag_id"])
        update_device_state(conn)
        bag_row = conn.execute("SELECT * FROM bags WHERE bag_id = ?", (bag_row["bag_id"],)).fetchone()
        conn.commit()
    return row_to_bag_record(bag_row)


@app.put("/device/bag", response_model=BagRecord)
def update_device_bag(payload: BagUpdateRequest) -> BagRecord:
    if not payload.name.strip():
        raise HTTPException(status_code=400, detail="Bag name is required")
    with db_conn() as conn:
        bag_row = ensure_single_bag(conn)
        bag = write_bag_record(conn, bag_row["bag_id"], payload)
        conn.commit()
        return bag


@app.post("/pair", response_model=PairResponse)
def pair(req: PairRequest) -> PairResponse:
    with db_conn() as conn:
        code_row = active_pair_code(conn)
        if not code_row or code_row["code"] != req.pair_code or code_row["expires_at"] < now_ms():
            raise HTTPException(status_code=400, detail="Pair code invalid or expired")
        token = secrets.token_urlsafe(32)
        conn.execute("INSERT INTO tokens(token, phone_device_id, issued_at, revoked) VALUES(?, ?, ?, 0)", (token, req.phone_device_id, now_ms()))
        conn.execute("UPDATE settings SET last_connected_device = ? WHERE id = 'primary'", (req.phone_device_id,))
        update_device_state(conn, "paired")
        conn.commit()
        return PairResponse(auth_token=token, pi_device_id=get_meta(conn, "pi_device_id") or "", server_time_ms=now_ms())


@app.post("/sync", response_model=SyncResponse)
def sync(req: SyncRequest, _: str = Depends(require_token)) -> SyncResponse:
    with db_conn() as conn:
        conn.execute("BEGIN IMMEDIATE")
        try:
            requested_bag_ids = {
                bag.bag_id.strip()
                for bag in req.changed_bags
                if bag.bag_id.strip()
            } | {
                item.bag_id.strip()
                for item in req.changed_items
                if item.bag_id.strip()
            }
            preferred_bag_id = next(iter(requested_bag_ids)) if len(requested_bag_ids) == 1 else None
            preferred_bag = next((bag for bag in req.changed_bags if bag.bag_id == preferred_bag_id), None)
            canonical_bag = ensure_single_bag(
                conn,
                preferred_bag_id=preferred_bag_id,
                preferred_size_liters=preferred_bag.size_liters if preferred_bag else None,
                preferred_name=preferred_bag.name if preferred_bag else None,
            )
            canonical_bag_id = canonical_bag["bag_id"]
            ls = req.last_sync_at
            phone_bags = {
                canonical_bag_id: bag.model_copy(update={"bag_id": canonical_bag_id})
                for bag in req.changed_bags
                if bag.bag_id == (preferred_bag_id or canonical_bag_id)
            }
            phone_items = {
                item.id: item.model_copy(update={"bag_id": canonical_bag_id})
                for item in req.changed_items
                if item.bag_id == (preferred_bag_id or canonical_bag_id)
            }
            server_bags = {r["bag_id"]: row_to_bag(r) for r in conn.execute("SELECT * FROM bags").fetchall()}
            server_items = {r["id"]: row_to_item(r) for r in conn.execute("SELECT * FROM items").fetchall()}

            server_bag_changes: List[Bag] = []
            server_item_changes: List[Item] = []
            conflicts: List[ConflictItem] = []
            auto_resolved: List[AutoResolvedItem] = []

            for bag_id in set(phone_bags.keys()) | set(r["bag_id"] for r in conn.execute("SELECT bag_id FROM bags WHERE updated_at > ?", (ls,)).fetchall()):
                p = phone_bags.get(bag_id)
                s = server_bags.get(bag_id)
                if p and not s:
                    upsert_bag(conn, p)
                elif s and not p and s.updated_at > ls:
                    server_bag_changes.append(s)
                elif p and s:
                    pc = p.updated_at > ls
                    sc = s.updated_at > ls
                    if pc and not sc:
                        upsert_bag(conn, p)
                    elif sc and not pc:
                        server_bag_changes.append(s)
                    elif pc and sc and p.updated_at < s.updated_at:
                        server_bag_changes.append(s)
                    elif pc and sc and p.updated_at >= s.updated_at:
                        upsert_bag(conn, p)

            for item_id in set(phone_items.keys()) | set(r["id"] for r in conn.execute("SELECT id FROM items WHERE updated_at > ?", (ls,)).fetchall()):
                p = phone_items.get(item_id)
                s = server_items.get(item_id)
                if p and not s:
                    upsert_item(conn, p)
                    continue
                if s and not p:
                    if s.updated_at > ls:
                        server_item_changes.append(s)
                    continue
                if not p or not s:
                    continue
                pc = p.updated_at > ls
                sc = s.updated_at > ls
                if pc and not sc:
                    upsert_item(conn, p)
                    continue
                if sc and not pc:
                    server_item_changes.append(s)
                    continue
                if not pc and not sc:
                    continue
                diff = diff_item(s, p)
                if diff.is_identical:
                    if p.updated_at > s.updated_at:
                        upsert_item(conn, p)
                    continue
                if diff.only_packed_status_diff:
                    if p.updated_at > s.updated_at:
                        upsert_item(conn, p)
                    else:
                        server_item_changes.append(s)
                    auto_resolved.append(AutoResolvedItem(item_id=item_id, rule="packed_latest_wins"))
                    continue
                conflicts.append(
                    ConflictItem(
                        item_id=item_id,
                        server_version=s,
                        reason="delete_vs_edit" if diff.delete_vs_edit else "both_modified",
                    )
                )

            server_time = now_ms()
            set_meta(conn, "last_sync_time_ms", str(server_time))
            update_bag_readiness(conn)
            update_device_state(conn, "synced")
            alerts = compute_alerts(conn, server_time)
            conn.commit()
            return SyncResponse(
                server_time_ms=server_time,
                server_bag_changes=server_bag_changes,
                server_item_changes=server_item_changes,
                conflicts=conflicts,
                auto_resolved=auto_resolved,
                alerts=alerts,
            )
        except Exception:
            conn.rollback()
            raise


@app.get("/kiosk/state")
def kiosk_state(request: Request) -> dict:
    with db_conn() as conn:
        pair = active_pair_code(conn) or create_pair_code(conn)
        bag_row = ensure_single_bag(conn)
        update_bag_readiness(conn, bag_row["bag_id"])
        update_device_state(conn)
        conn.commit()
        bags = [row_to_bag(r).model_dump() for r in conn.execute("SELECT * FROM bags ORDER BY name").fetchall()]
        items = [
            row_to_item(r).model_dump()
            for r in conn.execute("SELECT * FROM items WHERE deleted = 0 ORDER BY category, name").fetchall()
        ]
        current_time = now_ms()
        alerts = [a.model_dump() for a in compute_alerts(conn, current_time)]
        templates = [dict(r) for r in conn.execute("SELECT * FROM templates ORDER BY template_id, category, name").fetchall()]
        base_url = compute_base_url(request)
        paired = conn.execute("SELECT COUNT(*) AS c FROM tokens WHERE revoked = 0").fetchone()["c"] > 0
        pi_device_id = get_meta(conn, "pi_device_id") or ""
        readiness = build_readiness_summary([Item(**item) for item in items], paired, current_time)
        last_sync_time_ms = int(get_meta(conn, "last_sync_time_ms") or "0")
    return {
        "base_url": base_url,
        "pair_code": pair["code"],
        "pi_device_id": pi_device_id,
        "paired": paired,
        "device_status": readiness["device_status"],
        "bag_readiness": readiness["bag_readiness"],
        "checklist": readiness["checklist"],
        "checklist_covered": readiness["checklist_covered"],
        "checklist_total": readiness["checklist_total"],
        "expired_count": readiness["expired_count"],
        "near_expiry_count": readiness["near_expiry_count"],
        "readiness_alerts": readiness["alerts"],
        "last_sync_time_ms": last_sync_time_ms,
        "bags": bags,
        "items": items,
        "templates": templates,
        "alerts": alerts,
    }


@app.post("/admin/new_pair_code")
def admin_new_pair_code(request: Request) -> dict:
    require_admin_access(request)
    with db_conn() as conn:
        pair = create_pair_code(conn)
        conn.commit()
    return {"pair_code": pair["code"], "expires_at": pair["expires_at"]}


@app.post("/admin/revoke_tokens")
def admin_revoke_tokens(request: Request) -> dict:
    require_admin_access(request)
    with db_conn() as conn:
        conn.execute("UPDATE tokens SET revoked = 1")
        update_device_state(conn, "waiting_for_pair")
        conn.commit()
    return {"revoked": True}


def pair_qr_payload(base_url: str, pair_code: str, pi_device_id: str, bag: Bag) -> dict:
    return {
        "base_url": base_url,
        "pair_code": pair_code,
        "pi_device_id": pi_device_id,
        "bag_id": bag.bag_id,
        "bag_name": bag.name,
        "size_liters": bag.size_liters,
        "template_id": bag.template_id,
    }


def render_summary_html(summary_cards: List[tuple[str, str, str]]) -> str:
    tone_map = {
        "status": "accent",
        "readiness": "warn",
        "last sync": "success",
    }
    return "".join(
        [
            f"""
            <div class="stat-card stat-card-{tone_map.get(label.strip().lower(), "neutral")}">
              <div class="stat-label">{escape(label)}</div>
              <div class="stat-value">{escape(value)}</div>
              <div class="stat-note">{escape(note)}</div>
            </div>
            """
            for label, value, note in summary_cards
        ]
    )


def render_phone_rows_html(paired_rows: List[sqlite3.Row]) -> str:
    return "".join(
        [
            f"""
            <div class="list-row phone-row">
              <div>
                <div class="row-title">{escape(row['phone_device_id'])}</div>
                <div class="row-subtitle">Paired {escape(format_time_ms(int(row['issued_at'])))}</div>
              </div>
              <div class="pill ok">Active</div>
            </div>
            """
            for row in paired_rows
        ]
    ) or '<div class="empty-state">No paired phones yet.</div>'


def inventory_category_code(category: str) -> str:
    return {
        "Water & Food": "WF",
        "Medical & Health": "MH",
        "Light & Communication": "LC",
        "Tools & Protection": "TP",
        "Hygiene": "HY",
        "Other": "OT",
    }.get(normalize_category(category), "OT")


def inventory_category_tone(category: str) -> str:
    return {
        "Water & Food": "accent",
        "Medical & Health": "danger",
        "Light & Communication": "success",
        "Tools & Protection": "warn",
        "Hygiene": "success",
        "Other": "neutral",
    }.get(normalize_category(category), "neutral")


def render_inventory_groups_html(inventory_groups: List[InventoryGroupView]) -> str:
    return "".join(
        [
            f"""
            <div class="inventory-group inventory-tone-{inventory_category_tone(group.category)}">
              <div class="inventory-group-head">
                <div class="inventory-group-mark">{escape(inventory_category_code(group.category))}</div>
                <div class="inventory-group-body">
                  <div class="inventory-group-topline">
                    <div class="inventory-group-meta">
                      <div class="inventory-group-name">{escape(group.name)}</div>
                      <div class="inventory-group-tags">
                        <span class="micro-chip">{escape(group.category)}</span>
                        <span class="micro-chip">{len(group.batches)} batch{'es' if len(group.batches) != 1 else ''}</span>
                        <span class="micro-chip ok">{sum(1 for batch in group.batches if batch.item.packed_status)} packed</span>
                        {f'<span class="micro-chip warn">{sum(1 for batch in group.batches if not batch.item.packed_status)} unpacked</span>' if any(not batch.item.packed_status for batch in group.batches) else ''}
                      </div>
                    </div>
                    <div class="inventory-group-total">
                      <div class="inventory-group-total-value">{group.total_quantity:g}</div>
                      <div class="inventory-group-total-label">{escape(group.unit)} total</div>
                    </div>
                  </div>
                  <div class="inventory-batch-grid">
              {''.join(
                  [
                      f'''
                      <div class="batch-card {'packed' if batch.item.packed_status else 'needs-pack'}">
                        <div class="batch-card-head">
                          <div>
                            <div class="batch-card-quantity">{batch.item.quantity:g} {escape(batch.item.unit)}</div>
                            <div class="batch-card-subtitle">{escape("No expiration" if batch.expiry_label == "No expiration" else f"Expiry {batch.expiry_label}")}</div>
                          </div>
                          <div class="pill {'ok' if batch.item.packed_status else 'warn'}">{'Packed' if batch.item.packed_status else 'Needs pack'}</div>
                        </div>
                        {f'<div class="batch-card-notes">{escape(batch.item.notes)}</div>' if batch.item.notes else ''}
                        <div class="inventory-actions">
                          <form method="get" action="/">
                            <input type="hidden" name="edit_item_id" value="{escape(batch.item.id)}">
                            <button type="submit" class="secondary inventory-action">Edit batch</button>
                          </form>
                          <form method="post" action="/ui/items/{escape(batch.item.id)}/delete">
                            <input type="hidden" name="bag_id" value="{escape(group.bag_id)}">
                            <button type="submit" class="secondary danger inventory-action">Delete batch</button>
                          </form>
                        </div>
                      </div>
                      '''
                      for batch in group.batches
                  ]
              )}
                  </div>
                </div>
              </div>
            </div>
            """
            for group in inventory_groups
        ]
    ) or '<div class="empty-state">No inventory for this GO BAG yet.</div>'


def render_pairing_card_html(base_url: str, pair: sqlite3.Row, pi_device_id: str, bag: Bag, paired: bool) -> str:
    return f"""
    <div class="pair-card">
      <div class="row-title">{escape(bag.name)}</div>
      <div class="row-subtitle">{escape(bag_size_label(bag.size_liters))} physical GO BAG on this Raspberry Pi</div>
      <div class="qr-wrap" style="margin-top: 12px;">
        <img src="{qr_data_uri_for_payload(pair_qr_payload(base_url, pair['code'], pi_device_id, bag))}" alt="Pair {escape(bag.name)} QR">
        <div class="pair-code-card">
          <div class="pair-code-label">Pair code</div>
          <div class="pair-code-value">{escape(pair['code'])}</div>
          <div class="panel-note">Scan this QR code from the phone app Pair screen or enter the code manually.</div>
          <div class="panel-note">{escape('A phone is already paired and can sync now.' if paired else 'Waiting for a phone to pair with this GO BAG.')}</div>
        </div>
      </div>
    </div>
    """


def render_touch_keyboard_html() -> str:
    return """
    <div class="touch-keyboard hidden" id="touch-keyboard" aria-hidden="true" aria-label="Touch keyboard">
      <div class="touch-keyboard-inner">
        <div class="touch-keyboard-row ten">
          <button type="button" data-key="1">1</button>
          <button type="button" data-key="2">2</button>
          <button type="button" data-key="3">3</button>
          <button type="button" data-key="4">4</button>
          <button type="button" data-key="5">5</button>
          <button type="button" data-key="6">6</button>
          <button type="button" data-key="7">7</button>
          <button type="button" data-key="8">8</button>
          <button type="button" data-key="9">9</button>
          <button type="button" data-key="0">0</button>
        </div>
        <div class="touch-keyboard-row ten">
          <button type="button" data-key="q">q</button>
          <button type="button" data-key="w">w</button>
          <button type="button" data-key="e">e</button>
          <button type="button" data-key="r">r</button>
          <button type="button" data-key="t">t</button>
          <button type="button" data-key="y">y</button>
          <button type="button" data-key="u">u</button>
          <button type="button" data-key="i">i</button>
          <button type="button" data-key="o">o</button>
          <button type="button" data-key="p">p</button>
        </div>
        <div class="touch-keyboard-row ten">
          <button type="button" data-key="a">a</button>
          <button type="button" data-key="s">s</button>
          <button type="button" data-key="d">d</button>
          <button type="button" data-key="f">f</button>
          <button type="button" data-key="g">g</button>
          <button type="button" data-key="h">h</button>
          <button type="button" data-key="j">j</button>
          <button type="button" data-key="k">k</button>
          <button type="button" data-key="l">l</button>
          <button type="button" data-key="@">@</button>
        </div>
        <div class="touch-keyboard-row ten">
          <button type="button" class="action wide" data-keyboard-action="shift">Shift</button>
          <button type="button" data-key="z">z</button>
          <button type="button" data-key="x">x</button>
          <button type="button" data-key="c">c</button>
          <button type="button" data-key="v">v</button>
          <button type="button" data-key="b">b</button>
          <button type="button" data-key="n">n</button>
          <button type="button" data-key="m">m</button>
          <button type="button" data-key=".">.</button>
          <button type="button" class="action wide" data-keyboard-action="backspace">Bksp</button>
        </div>
        <div class="touch-keyboard-row six">
          <button type="button" data-key="/">/</button>
          <button type="button" data-key="-">-</button>
          <button type="button" data-key=":">:</button>
          <button type="button" class="action space" data-keyboard-action="space">Space</button>
          <button type="button" class="action wide" data-keyboard-action="clear">Clear</button>
          <button type="button" class="action wide done" data-keyboard-action="done">Done</button>
        </div>
      </div>
    </div>
    """


def render_hero_tags_html(paired: bool, readiness: dict, bag: Bag, item_count: int) -> str:
    return "".join(
        [
            f'<span class="tag {"ok" if paired else "warn"}">{escape(readiness["device_status"])}</span>',
            f'<span class="tag {"ok" if readiness["bag_readiness"] == "Ready" else "warn"}">{escape(readiness["bag_readiness"])}</span>',
            f'<span class="tag">{escape(bag_size_label(bag.size_liters))} bag</span>',
            f'<span class="tag">{item_count} active item(s)</span>',
        ]
    )


def build_dashboard_view_model(request: Request, edit_item_id: str = "") -> dict:
    with db_conn() as conn:
        pair = active_pair_code(conn) or create_pair_code(conn)
        bag_row = ensure_single_bag(conn)
        update_bag_readiness(conn, bag_row["bag_id"])
        update_device_state(conn)
        bag_row = conn.execute("SELECT * FROM bags WHERE bag_id = ?", (bag_row["bag_id"],)).fetchone()
        bag = row_to_bag(bag_row)
        item_rows = conn.execute(
            "SELECT * FROM items WHERE bag_id = ? AND deleted = 0 ORDER BY category, name",
            (bag.bag_id,),
        ).fetchall()
        items = [row_to_item(row) for row in item_rows]
        category_rows = conn.execute("SELECT * FROM categories ORDER BY name").fetchall()
        current_time = now_ms()
        alerts = compute_alerts(conn, current_time)
        pi_device_id = get_meta(conn, "pi_device_id") or ""
        paired_rows = conn.execute(
            "SELECT phone_device_id, issued_at FROM tokens WHERE revoked = 0 ORDER BY issued_at DESC"
        ).fetchall()
        paired = len(paired_rows) > 0
        last_sync_time_ms = int(get_meta(conn, "last_sync_time_ms") or "0")
        readiness = build_readiness_summary(items, paired, current_time)
        inventory_groups = build_inventory_groups(item_rows)
        edit_item_row = None
        if edit_item_id:
            candidate = conn.execute("SELECT * FROM items WHERE id = ? AND deleted = 0", (edit_item_id,)).fetchone()
            if candidate and candidate["bag_id"] == bag.bag_id:
                edit_item_row = candidate
        edit_item = row_to_item_record(edit_item_row) if edit_item_row else None
        conn.commit()

    base_url = compute_base_url(request)
    checked_count = sum(1 for row in readiness["checklist"] if row["checked"])
    missing_categories = [row["name"] for row in readiness["checklist"] if not row["checked"]]
    expiring_items = [a for a in alerts if a.type == "expiring_soon"]
    expired_items = [a for a in alerts if a.type == "expired"]
    next_step = (
        "Generate or scan the pairing QR so a phone can connect to this GO BAG."
        if not paired
        else "Use the Android app or this Pi inventory manager, then sync this GO BAG."
    )
    summary_cards = [
        ("Status", readiness["device_status"], "Connected" if paired else "Waiting for a phone"),
        ("Readiness", readiness["bag_readiness"], f"{checked_count} of {readiness['checklist_total']} categories covered"),
        ("Last Sync", format_time_ms(last_sync_time_ms), f"{len(expired_items)} expired, {len(expiring_items)} near expiry"),
    ]
    readiness_alert_rows = "".join(
        [f'<div class="alert-row">{escape(alert)}</div>' for alert in readiness["alerts"]]
    ) or '<div class="empty-state">No readiness alerts.</div>'
    expiry_alert_rows = "".join(
        [
            f'<div class="alert-row"><strong>{escape(a.item_name)}</strong><br>{escape(a.bag_name)} | {escape("Expired" if a.type == "expired" else "Expiring soon")} | {escape(format_expiry_date_ms(a.expiry_date_ms))}</div>'
            for a in alerts
        ]
    ) or '<div class="empty-state">No expiry alerts.</div>'
    missing_html = (
        "".join([f'<span class="tag warn">{escape(category)}</span>' for category in missing_categories])
        if missing_categories
        else '<span class="tag ok">All core categories covered</span>'
    )
    bag_size_options = "".join(
        [
            f'<option value="{bag_type}" {"selected" if bag.bag_id and bag_type == bag_type_for_template(bag.template_id, bag.size_liters) else ""}>{escape(label)}</option>'
            for _, bag_type, label in [(25, "25l", "25L"), (44, "44l", "44L"), (66, "66l", "66L")]
        ]
    )
    inventory_notice = (
        (
            f"USB scan preview is ready. The full-screen scanner uses {USB_STREAM_CMD} streaming for a faster camera view."
            if usb_stream_available() and usb_scan_available() and qr_decode_available()
            else f"USB scan preview is ready with {USB_SCAN_CMD}. Open the scanner to align the QR before capture."
        )
        if usb_scan_available() and qr_decode_available()
        else f"USB scan unavailable. {USB_SCAN_CMD}: {'ok' if usb_scan_available() else 'missing'}, {QR_DECODE_CMD}: {'ok' if qr_decode_available() else 'missing'}."
    )
    return {
        "bag": bag,
        "bag_record": row_to_bag_record(bag_row),
        "category_rows": category_rows,
        "edit_item": edit_item,
        "readiness": readiness,
        "checked_count": checked_count,
        "last_sync_time_ms": last_sync_time_ms,
        "pair": pair,
        "paired": paired,
        "next_step": next_step,
        "summary_html": render_summary_html(summary_cards),
        "hero_tags_html": render_hero_tags_html(paired, readiness, bag, len(items)),
        "readiness_alert_rows": readiness_alert_rows,
        "expiry_alert_rows": expiry_alert_rows,
        "missing_html": missing_html,
        "pairing_card_html": render_pairing_card_html(base_url, pair, pi_device_id, bag, paired),
        "inventory_groups_html": render_inventory_groups_html(inventory_groups),
        "phone_rows_html": render_phone_rows_html(paired_rows),
        "inventory_notice": inventory_notice,
        "bag_size_options": bag_size_options,
        "state_version": str(
            max(
                int(bag.updated_at or 0),
                max((item.updated_at for item in items), default=0),
                int(last_sync_time_ms or 0),
                int(pair["created_at"] or 0),
                max((int(row["issued_at"]) for row in paired_rows), default=0),
            )
        ),
        "base_url": base_url,
    }


def ui_redirect_url(
    bag_id: str = "",
    notice: str = "",
    error: str = "",
    edit_item_id: str = "",
    open_scan: bool = False,
) -> str:
    params: List[str] = []
    if bag_id:
        params.append(f"bag={quote(bag_id)}")
    if edit_item_id:
        params.append(f"edit_item_id={quote(edit_item_id)}")
    if notice:
        params.append(f"notice={quote(notice)}")
    if error:
        params.append(f"error={quote(error)}")
    if open_scan:
        params.append("scan=1")
    return "/" if not params else "/?" + "&".join(params)


@app.get("/ui/state")
def ui_state(request: Request) -> dict:
    view_model = build_dashboard_view_model(request)
    return {
        "state_version": view_model["state_version"],
        "next_step": view_model["next_step"],
        "summary_html": view_model["summary_html"],
        "hero_tags_html": view_model["hero_tags_html"],
        "readiness_alert_rows": view_model["readiness_alert_rows"],
        "expiry_alert_rows": view_model["expiry_alert_rows"],
        "pairing_card_html": view_model["pairing_card_html"],
        "pair_expires_label": format_time_ms(int(view_model["pair"]["expires_at"])),
        "inventory_groups_html": view_model["inventory_groups_html"],
        "inventory_notice": view_model["inventory_notice"],
        "phone_rows_html": view_model["phone_rows_html"],
        "bag_name": view_model["bag"].name,
        "bag_size_label": bag_size_label(view_model["bag"].size_liters),
    }


@app.post("/ui/bag/settings")
async def ui_save_bag_settings(request: Request) -> RedirectResponse:
    try:
        form = await read_ui_form(request)
        bag_type, size_liters = parse_ui_bag_type(form.get("bag_type", ""))
        with db_conn() as conn:
            bag_row = canonical_ui_bag_row(conn)
            current_name = (bag_row["name"] or "").strip()
            target_name = (
                default_bag_name_for_size(size_liters)
                if is_default_bag_name(current_name)
                else current_name or default_bag_name_for_size(size_liters)
            )
            write_bag_record(
                conn,
                bag_row["bag_id"],
                BagUpdateRequest(
                    name=target_name,
                    bag_type=bag_type,
                    last_checked_at=bag_row["last_checked_at"],
                ),
            )
            conn.commit()
        return RedirectResponse(ui_redirect_url(notice=f"Bag size set to {bag_size_label(size_liters)}."), status_code=303)
    except HTTPException as exc:
        return RedirectResponse(ui_redirect_url(error=str(exc.detail)), status_code=303)
    except Exception as exc:
        return RedirectResponse(ui_redirect_url(error=unexpected_ui_error_message("Bag size save", exc)), status_code=303)


@app.post("/ui/pair-code/new")
def ui_new_pair_code(request: Request) -> RedirectResponse:
    require_admin_access(request)
    with db_conn() as conn:
        pair = create_pair_code(conn)
        conn.commit()
    return RedirectResponse(
        ui_redirect_url(notice=f"New pair code ready until {format_time_ms(int(pair['expires_at']))}."),
        status_code=303,
    )


@app.post("/ui/revoke_tokens")
def ui_revoke_tokens(request: Request) -> RedirectResponse:
    require_admin_access(request)
    with db_conn() as conn:
        conn.execute("UPDATE tokens SET revoked = 1")
        update_device_state(conn, "waiting_for_pair")
        conn.commit()
    return RedirectResponse(ui_redirect_url(notice="All phone access was revoked."), status_code=303)


@app.post("/ui/system/shutdown")
def ui_shutdown_system(request: Request, background_tasks: BackgroundTasks) -> JSONResponse:
    require_admin_access(request)
    command = require_shutdown_ready()
    background_tasks.add_task(trigger_safe_shutdown, command)
    return JSONResponse(
        {
            "ok": True,
            "message": "Shutdown in progress. Wait until the screen turns off before disconnecting power.",
        }
    )


@app.post("/camera/usb/scan")
async def usb_camera_scan(request: Request) -> JSONResponse:
    form = await read_ui_form(request)
    requested_bag_id = str(form.get("bag_id") or "").strip()
    try:
        with db_conn() as conn:
            payload = perform_usb_scan_and_save(conn, requested_bag_id=requested_bag_id)
            conn.commit()
        return JSONResponse(payload)
    except HTTPException as exc:
        payload = error_payload_from_detail(exc.detail)
        return JSONResponse(payload, status_code=exc.status_code)
    except Exception as exc:
        logger.exception("USB camera scan failed unexpectedly")
        payload = structured_error_detail(
            "SCAN_FAILED",
            "USB camera scan failed unexpectedly.",
            technical=str(exc),
        )
        return JSONResponse(payload, status_code=500)


@app.post("/camera/usb/scan/decoded")
async def usb_camera_scan_decoded(request: Request) -> JSONResponse:
    form = await read_ui_form(request)
    requested_bag_id = str(form.get("bag_id") or "").strip()
    raw_content = str(form.get("content") or "").strip()
    logger.info("USB live preview submitted decoded QR (%s chars)", len(raw_content))
    try:
        with db_conn() as conn:
            payload = save_scanned_qr_content(conn, raw_content, requested_bag_id=requested_bag_id)
            conn.commit()
        return JSONResponse(payload)
    except HTTPException as exc:
        payload = error_payload_from_detail(exc.detail)
        return JSONResponse(payload, status_code=exc.status_code)
    except Exception as exc:
        logger.exception("USB live preview scan save failed unexpectedly")
        payload = structured_error_detail(
            "SCAN_FAILED",
            "USB camera scan failed unexpectedly.",
            technical=str(exc),
        )
        return JSONResponse(payload, status_code=500)


@app.post("/ui/items/save")
async def ui_save_item(request: Request) -> RedirectResponse:
    try:
        form = await read_ui_form(request)
        item_id = str(form.get("item_id") or "").strip() or None
        payload = build_ui_item_payload(form)
        with db_conn() as conn:
            bag_row = canonical_ui_bag_row(conn)
            bag_id = bag_row["bag_id"]
            merge_or_write_item_record(conn, bag_id, payload, item_id=item_id)
            conn.commit()
        return RedirectResponse(
            ui_redirect_url(
                bag_id=bag_id,
                notice="Inventory item updated." if item_id else "Inventory item saved.",
            ),
            status_code=303,
        )
    except HTTPException as exc:
        bag_id = str(locals().get("bag_id") or "")
        item_id = str(locals().get("item_id") or "")
        return RedirectResponse(ui_redirect_url(bag_id=bag_id, error=str(exc.detail), edit_item_id=item_id or ""), status_code=303)
    except Exception as exc:
        bag_id = str(locals().get("bag_id") or "")
        item_id = str(locals().get("item_id") or "")
        return RedirectResponse(
            ui_redirect_url(
                bag_id=bag_id,
                error=unexpected_ui_error_message("Manual add", exc),
                edit_item_id=item_id or "",
            ),
            status_code=303,
        )


@app.post("/ui/items/scan")
async def ui_scan_item(request: Request) -> RedirectResponse:
    try:
        form = await read_ui_form(request)
        requested_bag_id = str(form.get("bag_id") or "").strip()
        with db_conn() as conn:
            payload = perform_usb_scan_and_save(conn, requested_bag_id=requested_bag_id)
            bag_id = str(payload["bag_id"])
            conn.commit()
        return RedirectResponse(
            ui_redirect_url(bag_id=bag_id, notice=str(payload["message"])),
            status_code=303,
        )
    except HTTPException as exc:
        payload = error_payload_from_detail(exc.detail)
        bag_id = str(locals().get("bag_id") or "")
        return RedirectResponse(
            ui_redirect_url(bag_id=bag_id, error=ui_message_for_error_payload(payload), open_scan=True),
            status_code=303,
        )
    except Exception as exc:
        logger.exception("USB camera scan fallback route failed unexpectedly")
        bag_id = str(locals().get("bag_id") or "")
        payload = structured_error_detail("SCAN_FAILED", "USB camera scan failed unexpectedly.", technical=str(exc))
        return RedirectResponse(
            ui_redirect_url(bag_id=bag_id, error=ui_message_for_error_payload(payload), open_scan=True),
            status_code=303,
        )


@app.post("/ui/items/{item_id}/delete")
async def ui_delete_item(item_id: str, request: Request) -> RedirectResponse:
    try:
        await read_ui_form(request)
        with db_conn() as conn:
            current = get_item_row_or_404(conn, item_id)
            bag_id = current["bag_id"]
            conn.execute(
                """
                UPDATE items
                SET deleted = 1, updated_at = ?, updated_by = ?
                WHERE id = ?
                """,
                (now_ms(), get_meta(conn, "pi_device_id") or "pi", item_id),
            )
            update_bag_readiness(conn, current["bag_id"])
            update_device_state(conn)
            conn.commit()
        return RedirectResponse(ui_redirect_url(bag_id=bag_id, notice="Inventory batch deleted."), status_code=303)
    except HTTPException as exc:
        bag_id = str(locals().get("bag_id") or "")
        return RedirectResponse(ui_redirect_url(bag_id=bag_id, error=str(exc.detail)), status_code=303)
    except Exception as exc:
        bag_id = str(locals().get("bag_id") or "")
        return RedirectResponse(
            ui_redirect_url(bag_id=bag_id, error=unexpected_ui_error_message("Inventory delete", exc)),
            status_code=303,
        )


PI_DASHBOARD_THEME_TOKENS: Dict[str, Dict[str, str]] = {
    "light": {
        "bg": "#f8f9fa",
        "panel": "#ffffff",
        "panel-muted": "#f3f4f5",
        "panel-strong": "#e7e8e9",
        "ink": "#191c1d",
        "muted": "#5a646d",
        "line": "#d8dde2",
        "accent": "#ff6b00",
        "accent-strong": "#c94f00",
        "accent-soft": "#ffe2d1",
        "warn": "#ff6b00",
        "warn-soft": "#fff0e6",
        "danger": "#b3261e",
        "danger-soft": "#ffdad6",
        "success": "#2ecc71",
        "success-soft": "#dff7ea",
        "input-bg": "#ffffff",
        "disabled-bg": "#eceeef",
        "placeholder": "#6f767c",
        "focus-ring": "rgba(255, 107, 0, 0.28)",
        "topbar": "rgba(248, 249, 250, 0.92)",
        "hero-start": "#ffffff",
        "hero-end": "#edf1f4",
        "shadow": "rgba(25, 28, 29, 0.08)",
        "shadow-strong": "rgba(25, 28, 29, 0.16)",
    },
    "dark": {
        "bg": "#131314",
        "panel": "#1b1b1c",
        "panel-muted": "#1f1f20",
        "panel-strong": "#2a2a2b",
        "ink": "#e5e2e3",
        "muted": "#a9a1a4",
        "line": "#353436",
        "accent": "#ff6b00",
        "accent-strong": "#ffb693",
        "accent-soft": "rgba(255, 107, 0, 0.14)",
        "warn": "#ffb693",
        "warn-soft": "rgba(255, 107, 0, 0.14)",
        "danger": "#ffb4ab",
        "danger-soft": "rgba(147, 0, 10, 0.55)",
        "success": "#78dc77",
        "success-soft": "rgba(76, 173, 78, 0.2)",
        "input-bg": "#1b1b1c",
        "disabled-bg": "#252526",
        "placeholder": "#90898c",
        "focus-ring": "rgba(255, 107, 0, 0.34)",
        "topbar": "rgba(19, 19, 20, 0.92)",
        "hero-start": "#1b1b1c",
        "hero-end": "#0e0e0f",
        "shadow": "rgba(0, 0, 0, 0.34)",
        "shadow-strong": "rgba(0, 0, 0, 0.5)",
    },
}


def render_pi_dashboard_css_tokens() -> str:
    lines: List[str] = []
    for selector, color_scheme, token_group in [
        (":root", "light", PI_DASHBOARD_THEME_TOKENS["light"]),
        ('html[data-theme="dark"]', "dark", PI_DASHBOARD_THEME_TOKENS["dark"]),
    ]:
        lines.append(f"    {selector} {{")
        lines.append(f"      color-scheme: {color_scheme};")
        lines.extend([f"      --{name}: {value};" for name, value in token_group.items()])
        lines.append("    }")
    return "\n".join(lines)


@app.get("/", response_class=HTMLResponse)
def home(request: Request) -> HTMLResponse:
    edit_item_id = request.query_params.get("edit_item_id", "").strip()
    view_model = build_dashboard_view_model(request, edit_item_id=edit_item_id)
    bag = view_model["bag"]
    edit_item = view_model["edit_item"]
    readiness = view_model["readiness"]
    category_rows = view_model["category_rows"]
    notice = request.query_params.get("notice", "").strip()
    error = request.query_params.get("error", "").strip()
    open_scan = request.query_params.get("scan", "").strip() == "1"
    admin_query = f"?token={ADMIN_TOKEN}" if ADMIN_TOKEN else ""
    category_options = "".join(
        [
            f'<option value="{escape(row["id"])}" {"selected" if edit_item and edit_item.category_id == row["id"] else ""}>{escape(row["name"])}</option>'
            for row in category_rows
        ]
    )
    checklist_rows = "".join(
        [
            f'<div class="check-row {"ok" if c["checked"] else "warn"}"><span>{escape(c["name"])}</span><strong>{"Ready" if c["checked"] else "Missing"}</strong></div>'
            for c in readiness["checklist"]
        ]
    )
    readiness_percent = (
        round((view_model["checked_count"] / readiness["checklist_total"]) * 100)
        if readiness["checklist_total"]
        else 0
    )
    readiness_color = (
        "var(--success)"
        if readiness_percent >= 90
        else "var(--warn)"
        if readiness_percent >= 51
        else "var(--danger)"
    )
    inventory_form_kicker = "Edit supply batch" if edit_item else "New supply item"
    inventory_form_title = "Update this inventory batch" if edit_item else "Add a supply batch"
    inventory_form_description = (
        "Adjust quantity, expiry, notes, or packed state for the selected batch."
        if edit_item
        else "Manual add and USB scan both store batches locally, then sync later to the phone."
    )
    inventory_submit_label = "Update inventory batch" if edit_item else "Manual add"
    html = f"""
<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Go-Bag Pi Command Center</title>
  <script>
    (() => {{
      try {{
        const savedTheme = localStorage.getItem("gobag-pi-theme");
        if (savedTheme === "dark" || savedTheme === "light") {{
          document.documentElement.dataset.theme = savedTheme;
        }}
        const savedUiScale = localStorage.getItem("gobag-pi-ui-scale");
        if (savedUiScale === "fit" || savedUiScale === "standard") {{
          document.documentElement.dataset.uiScale = savedUiScale;
        }} else if (window.matchMedia("(max-width: 520px), (max-height: 360px)").matches) {{
          document.documentElement.dataset.uiScale = "fit";
        }}
      }} catch (_error) {{
      }}
    }})();
  </script>
  <style>
{render_pi_dashboard_css_tokens()}
    * {{ box-sizing: border-box; }}
    html {{
      scroll-behavior: smooth;
      --touch-keyboard-offset: 0px;
      --page-padding: 18px 16px 40px;
      --topbar-padding: 14px 16px;
      --topbar-gap: 16px;
      --brand-gap: 14px;
      --brand-mark-size: 52px;
      --brand-mark-radius: 10px;
      --brand-mark-font-size: 1rem;
      --header-chip-height: 64px;
      --header-chip-padding: 0 18px;
      --header-chip-gap: 10px;
      --overview-gap: 16px;
      --hero-padding: 22px;
      --panel-padding: 20px;
      --stat-card-padding: 16px;
      --stat-card-min-height: 112px;
      --quick-link-height: 72px;
      --quick-link-padding: 14px 16px;
      --field-padding: 12px 14px;
      --action-button-height: 64px;
      --action-button-padding: 12px 14px;
      --action-button-font-size: 1rem;
      --touch-keyboard-padding: 10px 8px calc(10px + env(safe-area-inset-bottom, 0px));
      --touch-keyboard-gap: 6px;
      --touch-keyboard-button-height: 40px;
      --touch-keyboard-button-padding: 8px 6px;
      --touch-keyboard-button-font-size: 0.92rem;
    }}
    html[data-ui-scale="fit"] {{
      --page-padding: 10px 10px 24px;
      --topbar-padding: 8px 10px;
      --topbar-gap: 10px;
      --brand-gap: 10px;
      --brand-mark-size: 38px;
      --brand-mark-radius: 8px;
      --brand-mark-font-size: 0.84rem;
      --header-chip-height: 42px;
      --header-chip-padding: 0 12px;
      --header-chip-gap: 8px;
      --overview-gap: 10px;
      --hero-padding: 14px;
      --panel-padding: 14px;
      --stat-card-padding: 12px;
      --stat-card-min-height: 86px;
      --quick-link-height: 56px;
      --quick-link-padding: 10px 12px;
      --field-padding: 9px 10px;
      --action-button-height: 46px;
      --action-button-padding: 8px 10px;
      --action-button-font-size: 0.9rem;
      --touch-keyboard-padding: 6px 6px calc(6px + env(safe-area-inset-bottom, 0px));
      --touch-keyboard-gap: 4px;
      --touch-keyboard-button-height: 29px;
      --touch-keyboard-button-padding: 4px 3px;
      --touch-keyboard-button-font-size: 0.78rem;
    }}
    body {{
      margin: 0;
      min-height: 100vh;
      font-family: "Inter", "Segoe UI", Tahoma, sans-serif;
      background:
        radial-gradient(circle at top right, rgba(255, 107, 0, 0.16), transparent 24%),
        radial-gradient(circle at top left, rgba(255, 107, 0, 0.09), transparent 30%),
        var(--bg);
      color: var(--ink);
    }}
    a {{
      color: inherit;
    }}
    main {{
      max-width: 1320px;
      margin: 0 auto;
      padding: var(--page-padding);
    }}
    h1, h2, h3, p {{ margin: 0; }}
    .topbar {{
      position: sticky;
      top: 0;
      z-index: 30;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--topbar-gap);
      padding: var(--topbar-padding);
      border-bottom: 1px solid var(--line);
      background: var(--topbar);
      backdrop-filter: blur(12px);
      box-shadow: 0 10px 22px var(--shadow);
    }}
    .topbar-brand {{
      display: flex;
      align-items: center;
      gap: var(--brand-gap);
      min-width: 0;
    }}
    .brand-mark {{
      width: var(--brand-mark-size);
      height: var(--brand-mark-size);
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border-radius: var(--brand-mark-radius);
      font-family: "Space Grotesk", "Segoe UI", Tahoma, sans-serif;
      font-size: var(--brand-mark-font-size);
      font-weight: 800;
      letter-spacing: 0.08em;
      color: #ffffff;
      background: linear-gradient(180deg, var(--accent), var(--accent-strong));
      box-shadow: 0 12px 24px rgba(255, 107, 0, 0.18);
    }}
    .topbar-kicker,
    .hero-kicker,
    .panel-subtitle,
    .stat-label,
    .pair-code-label {{
      font-size: 0.72rem;
      letter-spacing: 0.18em;
      text-transform: uppercase;
      font-weight: 700;
      color: var(--muted);
    }}
    .topbar-title {{
      font-family: "Space Grotesk", "Segoe UI", Tahoma, sans-serif;
      font-size: clamp(1.1rem, 2vw, 1.45rem);
      font-weight: 800;
      letter-spacing: -0.03em;
    }}
    .topbar-actions {{
      display: flex;
      align-items: center;
      justify-content: flex-end;
      flex-wrap: wrap;
      gap: 8px;
    }}
    .readiness-pill,
    .theme-toggle,
    .view-controls {{
      min-height: var(--header-chip-height);
      border-radius: 12px;
      border: 1px solid var(--line);
      background: var(--panel);
      box-shadow: 0 8px 18px var(--shadow);
    }}
    .readiness-pill {{
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: var(--header-chip-padding);
      font-family: "Space Grotesk", "Segoe UI", Tahoma, sans-serif;
      font-weight: 700;
      color: var(--accent);
    }}
    .readiness-pill-value {{
      font-size: 1.02rem;
      line-height: 1;
    }}
    .readiness-pill-label {{
      color: var(--muted);
      font-size: 0.76rem;
      letter-spacing: 0.12em;
      text-transform: uppercase;
      white-space: nowrap;
    }}
    .theme-toggle {{
      display: inline-flex;
      align-items: center;
      gap: var(--header-chip-gap);
      padding: var(--header-chip-padding);
      width: auto;
      color: var(--ink);
    }}
    .power-button {{
      display: inline-flex;
      align-items: center;
      gap: var(--header-chip-gap);
      min-height: var(--header-chip-height);
      padding: var(--header-chip-padding);
      width: auto;
      border-radius: 12px;
      border: 1px solid rgba(255, 180, 171, 0.2);
      background: linear-gradient(180deg, rgba(128, 28, 36, 0.96), rgba(94, 22, 28, 0.96));
      color: #fff4f2;
      box-shadow: 0 8px 18px rgba(83, 18, 23, 0.24);
    }}
    .power-button:disabled {{
      opacity: 0.55;
      cursor: default;
    }}
    .power-button-icon {{
      font-size: 1rem;
      line-height: 1;
    }}
    .power-button-label {{
      white-space: nowrap;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      font-size: 0.76rem;
      font-weight: 800;
    }}
    .view-controls {{
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 0 8px;
      color: var(--ink);
    }}
    .zoom-toggle {{
      width: calc(var(--header-chip-height) - 10px);
      min-width: calc(var(--header-chip-height) - 10px);
      min-height: calc(var(--header-chip-height) - 10px);
      border-radius: 10px;
      border: 1px solid var(--line);
      background: var(--panel-strong);
      color: var(--ink);
      font-family: "Space Grotesk", "Segoe UI", Tahoma, sans-serif;
      font-size: 1rem;
      font-weight: 800;
      line-height: 1;
      padding: 0;
      cursor: pointer;
    }}
    .zoom-toggle:disabled {{
      opacity: 0.45;
      cursor: default;
    }}
    .zoom-indicator {{
      min-width: 40px;
      text-align: center;
      font-size: 0.72rem;
      font-weight: 700;
      letter-spacing: 0.1em;
      text-transform: uppercase;
      color: var(--muted);
      white-space: nowrap;
    }}
    .theme-toggle-icon {{
      font-size: 1.2rem;
      line-height: 1;
    }}
    .theme-toggle-label {{
      white-space: nowrap;
    }}
    .overview-grid {{
      display: grid;
      gap: var(--overview-gap);
      margin-top: var(--overview-gap);
    }}
    .hero-shell,
    .panel,
    .stat-card,
    .subpanel,
    .inventory-group,
    .batch-card,
    .pair-card,
    .pair-code-card {{
      background: var(--panel);
      border: 1px solid var(--line);
      border-radius: 12px;
      box-shadow: 0 10px 22px var(--shadow);
      color: var(--ink);
    }}
    .hero-shell {{
      position: relative;
      overflow: hidden;
      padding: var(--hero-padding);
      background: linear-gradient(135deg, var(--hero-start), var(--hero-end));
      border-left: 4px solid var(--accent);
    }}
    .hero-shell::after {{
      content: "";
      position: absolute;
      top: -54px;
      right: -54px;
      width: 220px;
      height: 220px;
      border-radius: 50%;
      background: radial-gradient(circle, var(--accent-soft) 0%, transparent 72%);
      pointer-events: none;
    }}
    .hero-shell-inner {{
      position: relative;
      display: grid;
      gap: 18px;
    }}
    .hero-score-row {{
      display: grid;
      gap: 18px;
      align-items: center;
    }}
    .readiness-ring {{
      width: min(100%, 172px);
      aspect-ratio: 1 / 1;
      display: flex;
      align-items: center;
      justify-content: center;
      border-radius: 999px;
      background:
        radial-gradient(circle at center, var(--panel) 0 55%, transparent 56% 100%),
        conic-gradient(
          var(--score-color) 0deg var(--readiness-angle, 300deg),
          var(--panel-strong) var(--readiness-angle, 300deg) 360deg
        );
      box-shadow: inset 0 0 0 10px var(--accent-soft);
    }}
    .readiness-score {{
      font-family: "Space Grotesk", "Segoe UI", Tahoma, sans-serif;
      font-size: clamp(2.8rem, 8vw, 4.8rem);
      font-weight: 800;
      letter-spacing: -0.08em;
      line-height: 0.94;
    }}
    .readiness-score span {{
      font-size: 0.38em;
      color: var(--score-color);
      margin-left: 0.08em;
    }}
    .hero-text {{
      display: grid;
      gap: 12px;
      min-width: 0;
    }}
    .hero-text h1 {{
      font-family: "Space Grotesk", "Segoe UI", Tahoma, sans-serif;
      font-size: clamp(1.85rem, 4vw, 3rem);
      font-weight: 800;
      letter-spacing: -0.04em;
    }}
    .hero-note {{
      max-width: 56ch;
      color: var(--muted);
      line-height: 1.55;
    }}
    .hero-progress {{
      display: grid;
      gap: 10px;
    }}
    .progress-meta {{
      display: flex;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
      color: var(--muted);
      font-size: 0.95rem;
    }}
    .progress-meta strong {{
      color: var(--ink);
    }}
    .progress-bar {{
      height: 12px;
      overflow: hidden;
      border-radius: 999px;
      border: 1px solid var(--line);
      background: var(--panel-strong);
    }}
    .progress-bar span {{
      display: block;
      height: 100%;
      background: linear-gradient(90deg, var(--score-color), var(--accent));
    }}
    .hero-tags, .tag-row {{
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
    }}
    .tag, .pill {{
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-height: 38px;
      border-radius: 999px;
      padding: 8px 14px;
      font-size: 0.84rem;
      font-weight: 600;
      background: var(--panel-muted);
      border: 1px solid var(--line);
      color: var(--ink);
      text-decoration: none;
    }}
    .tag.ok, .pill.ok {{
      background: var(--success-soft);
      color: var(--success);
      border-color: transparent;
    }}
    .tag.warn, .pill.warn {{
      background: var(--warn-soft);
      color: var(--warn);
      border-color: transparent;
    }}
    .tag.danger {{
      background: var(--danger-soft);
      color: var(--danger);
      border-color: transparent;
    }}
    .summary-grid {{
      display: grid;
      gap: 12px;
    }}
    .stat-card {{
      position: relative;
      min-height: var(--stat-card-min-height);
      padding: var(--stat-card-padding);
      overflow: hidden;
    }}
    .stat-card::before {{
      content: "";
      position: absolute;
      inset: 0 auto 0 0;
      width: 4px;
      background: var(--panel-strong);
    }}
    .stat-card-accent::before {{
      background: var(--accent);
    }}
    .stat-card-warn::before {{
      background: var(--warn);
    }}
    .stat-card-success::before {{
      background: var(--success);
    }}
    .stat-value {{
      margin-top: 8px;
      font-family: "Space Grotesk", "Segoe UI", Tahoma, sans-serif;
      font-size: 1.4rem;
      font-weight: 800;
      margin-bottom: 6px;
    }}
    .stat-note {{
      color: var(--muted);
      line-height: 1.45;
      font-size: 0.93rem;
    }}
    .panel {{
      padding: var(--panel-padding);
    }}
    .panel-head {{
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      flex-wrap: wrap;
      gap: 12px;
      margin-bottom: 12px;
    }}
    .panel-title {{
      font-family: "Space Grotesk", "Segoe UI", Tahoma, sans-serif;
      font-size: 1.2rem;
      font-weight: 800;
    }}
    .panel-note {{
      color: var(--muted);
      margin-top: 4px;
      line-height: 1.45;
    }}
    .section-divider {{
      height: 1px;
      margin: 16px 0;
      background: var(--line);
    }}
    .quick-actions {{
      display: grid;
      gap: 12px;
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }}
    .quick-link {{
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      min-height: var(--quick-link-height);
      width: 100%;
      padding: var(--quick-link-padding);
      text-decoration: none;
      border-radius: 10px;
      border: 1px solid var(--line);
      background: var(--panel-muted);
      color: var(--ink);
      cursor: pointer;
      box-shadow: 0 6px 14px var(--shadow);
      transition: transform 150ms ease, filter 150ms ease, background 150ms ease, border-color 150ms ease;
    }}
    .quick-link.primary {{
      background: linear-gradient(135deg, var(--accent), var(--accent-strong));
      border-color: transparent;
      color: #ffffff;
    }}
    .quick-link strong {{
      display: block;
      font-family: "Space Grotesk", "Segoe UI", Tahoma, sans-serif;
      font-size: 1rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: -0.02em;
    }}
    .quick-link small {{
      display: block;
      margin-top: 4px;
      font-size: 0.72rem;
      letter-spacing: 0.14em;
      text-transform: uppercase;
      opacity: 0.78;
    }}
    .quick-link-icon {{
      font-size: 1.35rem;
      font-weight: 700;
      line-height: 1;
    }}
    .alert-columns {{
      display: grid;
      gap: 12px;
    }}
    .subpanel {{
      padding: 14px;
      background: var(--panel-muted);
    }}
    .list-row, .check-row {{
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      padding: 14px 16px;
      margin-top: 10px;
      border-radius: 10px;
      border: 1px solid var(--line);
      background: var(--panel-muted);
    }}
    .row-title {{
      font-weight: 700;
      margin-bottom: 4px;
    }}
    .row-subtitle {{
      color: var(--muted);
      font-size: 0.92rem;
      line-height: 1.4;
    }}
    .check-row strong.ok {{ color: var(--accent); }}
    .check-row strong.warn {{ color: var(--warn); }}
    .alert-row {{
      padding: 12px 14px;
      border-radius: 10px;
      background: var(--panel-muted);
      border: 1px solid var(--line);
      color: var(--ink);
      margin-top: 10px;
    }}
    .alert-row strong {{
      display: block;
      margin-bottom: 4px;
    }}
    .qr-wrap {{
      display: grid;
      gap: 16px;
      align-items: center;
    }}
    .qr-wrap img {{
      width: min(240px, 100%);
      border-radius: 18px;
      border: 1px solid var(--line);
      background: white;
      padding: 10px;
    }}
    .pair-code-card {{
      border: 1px solid var(--line);
      border-radius: 12px;
      background: var(--panel-muted);
      padding: 16px;
      color: var(--ink);
    }}
    .pair-code-label {{
      margin-bottom: 8px;
    }}
    .pair-code-value {{
      font-family: "Cascadia Code", Consolas, monospace;
      font-size: clamp(1.8rem, 8vw, 2.5rem);
      font-weight: 800;
      letter-spacing: 0.1em;
      margin-bottom: 10px;
    }}
    form {{
      margin-top: 14px;
    }}
    form.inline, .button-row form {{
      margin-top: 0;
      flex: 1 1 180px;
    }}
    input, select, textarea {{
      width: 100%;
      border-radius: 10px;
      border: 1px solid var(--line);
      padding: var(--field-padding);
      font: inherit;
      color: var(--ink);
      background: var(--input-bg);
      caret-color: var(--ink);
    }}
    input::placeholder, textarea::placeholder {{
      color: var(--placeholder);
      opacity: 1;
    }}
    select, option {{
      color: var(--ink);
      background: var(--input-bg);
    }}
    option:checked {{
      background: var(--accent-soft);
      color: var(--ink);
    }}
    input:focus-visible, select:focus-visible, textarea:focus-visible, button:focus-visible {{
      outline: 3px solid var(--focus-ring);
      outline-offset: 2px;
    }}
    input:disabled, select:disabled, textarea:disabled, button:disabled {{
      background: var(--disabled-bg);
      color: var(--muted);
      opacity: 1;
      cursor: not-allowed;
    }}
    textarea {{
      min-height: 96px;
      resize: vertical;
    }}
    .field-grid {{
      display: grid;
      grid-template-columns: 1fr;
      gap: 12px;
    }}
    .button-row {{
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      margin-top: 14px;
    }}
    .button-row.compact {{
      margin-top: 10px;
    }}
    .inventory-panel {{
      display: grid;
      gap: 18px;
    }}
    .inventory-manager-shell {{
      display: grid;
      gap: 16px;
    }}
    .inventory-entry-panel,
    .inventory-command-panel,
    .inventory-groups-shell {{
      border: 1px solid var(--line);
      border-radius: 12px;
      background: var(--panel-muted);
      padding: 16px;
    }}
    .inventory-form-kicker,
    .inventory-groups-kicker {{
      font-size: 0.72rem;
      letter-spacing: 0.18em;
      text-transform: uppercase;
      font-weight: 700;
      color: var(--muted);
    }}
    .inventory-form-title,
    .inventory-groups-title {{
      margin-top: 6px;
      font-family: "Space Grotesk", "Segoe UI", Tahoma, sans-serif;
      font-size: 1.45rem;
      font-weight: 800;
      letter-spacing: -0.03em;
    }}
    .inventory-form-description,
    .inventory-groups-caption {{
      margin-top: 6px;
      color: var(--muted);
      line-height: 1.5;
    }}
    .inventory-form-grid {{
      display: grid;
      gap: 12px;
      margin-top: 16px;
    }}
    .inventory-checkbox-row {{
      display: flex;
      align-items: center;
      gap: 10px;
      min-height: 56px;
      padding: 0 4px;
      color: var(--ink);
      font-weight: 600;
    }}
    .inventory-checkbox-row input {{
      width: auto;
      margin: 0;
    }}
    .inventory-command-panel {{
      display: grid;
      gap: 12px;
      align-content: start;
    }}
    .inventory-note-card {{
      border: 1px solid var(--line);
      border-radius: 10px;
      background: var(--panel);
      padding: 14px;
    }}
    .inventory-note-card strong {{
      display: block;
      margin-bottom: 6px;
      font-family: "Space Grotesk", "Segoe UI", Tahoma, sans-serif;
      font-size: 1rem;
      letter-spacing: -0.02em;
    }}
    .inventory-note-card p {{
      color: var(--muted);
      line-height: 1.5;
    }}
    .inventory-groups-shell {{
      display: grid;
      gap: 14px;
    }}
    .inventory-groups-head {{
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 12px;
      flex-wrap: wrap;
    }}
    .inventory-groups-meta {{
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }}
    .banner {{
      border-radius: 12px;
      padding: 14px 16px;
      margin-bottom: 16px;
      border: 1px solid var(--line);
      color: var(--ink);
    }}
    .banner.notice {{
      background: var(--success-soft);
      color: var(--success);
    }}
    .banner.error {{
      background: var(--danger-soft);
      color: var(--danger);
    }}
    .inventory-group {{
      --inventory-accent: var(--accent);
      --inventory-accent-soft: var(--accent-soft);
      border: 1px solid var(--line);
      border-radius: 12px;
      background: var(--panel);
      padding: 16px;
      margin-top: 12px;
      box-shadow: 0 8px 20px var(--shadow);
    }}
    .inventory-tone-accent {{
      --inventory-accent: var(--accent);
      --inventory-accent-soft: var(--accent-soft);
    }}
    .inventory-tone-danger {{
      --inventory-accent: var(--danger);
      --inventory-accent-soft: var(--danger-soft);
    }}
    .inventory-tone-success {{
      --inventory-accent: var(--success);
      --inventory-accent-soft: var(--success-soft);
    }}
    .inventory-tone-warn {{
      --inventory-accent: var(--warn);
      --inventory-accent-soft: var(--warn-soft);
    }}
    .inventory-tone-neutral {{
      --inventory-accent: var(--muted);
      --inventory-accent-soft: var(--panel-strong);
    }}
    .inventory-group-head {{
      display: grid;
      gap: 14px;
    }}
    .inventory-group-mark {{
      width: 56px;
      height: 56px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border-radius: 12px;
      background: var(--inventory-accent-soft);
      color: var(--inventory-accent);
      font-family: "Space Grotesk", "Segoe UI", Tahoma, sans-serif;
      font-size: 1.15rem;
      font-weight: 800;
      letter-spacing: 0.08em;
      border: 1px solid transparent;
    }}
    .inventory-group-body {{
      display: grid;
      gap: 14px;
      min-width: 0;
    }}
    .inventory-group-topline {{
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 14px;
      flex-wrap: wrap;
    }}
    .inventory-group-meta {{
      min-width: 0;
    }}
    .inventory-group-name {{
      font-family: "Space Grotesk", "Segoe UI", Tahoma, sans-serif;
      font-size: 1.25rem;
      font-weight: 800;
      letter-spacing: -0.03em;
    }}
    .inventory-group-tags {{
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-top: 8px;
    }}
    .micro-chip {{
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-height: 32px;
      padding: 6px 10px;
      border-radius: 999px;
      background: var(--panel-muted);
      border: 1px solid var(--line);
      color: var(--muted);
      font-size: 0.78rem;
      font-weight: 700;
      letter-spacing: 0.03em;
      text-transform: uppercase;
    }}
    .micro-chip.ok {{
      background: var(--success-soft);
      border-color: transparent;
      color: var(--success);
    }}
    .micro-chip.warn {{
      background: var(--warn-soft);
      border-color: transparent;
      color: var(--warn);
    }}
    .inventory-group-total {{
      min-width: 112px;
      text-align: right;
    }}
    .inventory-group-total-value {{
      font-family: "Space Grotesk", "Segoe UI", Tahoma, sans-serif;
      font-size: 2rem;
      font-weight: 800;
      letter-spacing: -0.05em;
      line-height: 1;
      color: var(--inventory-accent);
    }}
    .inventory-group-total-label {{
      margin-top: 6px;
      color: var(--muted);
      font-size: 0.82rem;
      text-transform: uppercase;
      letter-spacing: 0.12em;
      font-weight: 700;
    }}
    .inventory-batch-grid {{
      display: grid;
      gap: 12px;
    }}
    .batch-card {{
      border: 1px solid var(--line);
      border-left: 4px solid var(--inventory-accent);
      border-radius: 10px;
      padding: 14px;
      background: var(--panel-muted);
      margin-top: 0;
      display: grid;
      gap: 12px;
    }}
    .batch-card.packed {{
      border-left-color: var(--success);
    }}
    .batch-card.needs-pack {{
      border-left-color: var(--warn);
    }}
    .batch-card-head {{
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 12px;
      flex-wrap: wrap;
    }}
    .batch-card-quantity {{
      font-family: "Space Grotesk", "Segoe UI", Tahoma, sans-serif;
      font-size: 1.2rem;
      font-weight: 800;
      letter-spacing: -0.03em;
    }}
    .batch-card-subtitle {{
      margin-top: 4px;
      color: var(--muted);
      font-size: 0.92rem;
      line-height: 1.4;
    }}
    .batch-card-notes {{
      border: 1px solid var(--line);
      border-radius: 10px;
      background: var(--panel);
      padding: 10px 12px;
      color: var(--muted);
      line-height: 1.45;
    }}
    .inventory-actions {{
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }}
    .inventory-actions form {{
      flex: 1 1 160px;
      margin-top: 0;
    }}
    button.inventory-action {{
      min-height: 56px;
      font-size: 0.94rem;
      padding: 10px 12px;
    }}
    .pair-card {{
      border: 1px solid var(--line);
      border-radius: 12px;
      background: var(--panel);
      padding: 14px;
      color: var(--ink);
    }}
    .pair-card img {{
      width: min(220px, 100%);
      border-radius: 18px;
      border: 1px solid var(--line);
      background: white;
      padding: 10px;
      margin-top: 12px;
      margin-bottom: 12px;
    }}
    button:not(.quick-link):not(.theme-toggle):not(.zoom-toggle):not(.power-button) {{
      width: 100%;
      min-height: var(--action-button-height);
      border: 1px solid transparent;
      border-radius: 10px;
      background: var(--accent);
      color: #ffffff;
      font-weight: 700;
      font-size: var(--action-button-font-size);
      cursor: pointer;
      padding: var(--action-button-padding);
      transition: transform 150ms ease, filter 150ms ease, background 150ms ease, border-color 150ms ease;
    }}
    button:not(.quick-link):not(.theme-toggle):not(.zoom-toggle):not(.power-button):hover {{
      filter: brightness(0.96);
    }}
    button:not(.quick-link):not(.theme-toggle):not(.zoom-toggle):not(.power-button):active {{
      transform: translateY(1px);
    }}
    button.secondary {{
      background: var(--panel-strong);
      color: var(--ink);
      border-color: var(--line);
    }}
    button.secondary.danger {{
      background: var(--danger-soft);
      color: var(--danger);
      border-color: transparent;
    }}
    .empty-state {{
      color: var(--muted);
      padding: 12px 2px 6px;
    }}
    .hidden {{
      display: none !important;
    }}
    body.keyboard-open {{
      padding-bottom: calc(var(--touch-keyboard-offset, 0px) + 12px);
      scroll-padding-bottom: calc(var(--touch-keyboard-offset, 0px) + 24px);
    }}
    .touch-keyboard {{
      position: fixed;
      left: 0;
      right: 0;
      bottom: 0;
      z-index: 55;
      padding: var(--touch-keyboard-padding);
      background: var(--topbar);
      border-top: 1px solid var(--line);
      box-shadow: 0 -12px 28px var(--shadow-strong);
      backdrop-filter: blur(6px);
    }}
    .touch-keyboard-inner {{
      max-width: 1320px;
      margin: 0 auto;
      display: grid;
      gap: var(--touch-keyboard-gap);
    }}
    .touch-keyboard-row {{
      display: grid;
      gap: var(--touch-keyboard-gap);
    }}
    .touch-keyboard-row.ten {{
      grid-template-columns: repeat(10, minmax(0, 1fr));
    }}
    .touch-keyboard-row.six {{
      grid-template-columns: repeat(6, minmax(0, 1fr));
    }}
    .touch-keyboard button {{
      min-height: var(--touch-keyboard-button-height);
      padding: var(--touch-keyboard-button-padding);
      font-size: var(--touch-keyboard-button-font-size);
      border-radius: 10px;
      background: var(--panel);
      color: var(--ink);
      border: 1px solid var(--line);
    }}
    .touch-keyboard button.action {{
      background: var(--panel-strong);
      color: var(--ink);
    }}
    .touch-keyboard button.done {{
      background: var(--accent);
      color: #ffffff;
    }}
    .touch-keyboard button.active {{
      background: var(--accent-soft);
      color: var(--accent-strong);
    }}
    .touch-keyboard button.wide {{
      grid-column: span 2;
    }}
    .touch-keyboard button.space {{
      grid-column: span 2;
    }}
    .check-row.ok strong {{
      color: var(--accent);
    }}
    .check-row.warn strong {{
      color: var(--warn);
    }}
    html.modal-open,
    body.modal-open {{
      height: 100%;
      overflow: hidden;
      overscroll-behavior: none;
    }}
    body.modal-open {{
      touch-action: none;
    }}
    body.modal-open main {{
      max-height: 100vh;
      overflow: hidden;
    }}
    .scan-modal {{
      position: fixed;
      inset: 0;
      z-index: 50;
      width: 100vw;
      height: 100vh;
      height: 100dvh;
      background: #050806;
      display: flex;
      align-items: stretch;
      justify-content: stretch;
      overflow: hidden;
    }}
    .scan-dialog {{
      position: relative;
      width: 100vw;
      height: 100vh;
      height: 100dvh;
      max-height: 100dvh;
      margin: 0;
      background: #050806;
      overflow: hidden;
    }}
    .scan-preview-shell {{
      position: absolute;
      inset: 0;
      min-height: 0;
    }}
    .scan-preview-frame {{
      position: absolute;
      inset: 0;
      min-height: 0;
      height: 100%;
      background: #050806;
      overflow: hidden;
      display: flex;
      align-items: center;
      justify-content: center;
    }}
    .scan-preview-frame::after {{
      content: "";
      position: absolute;
      inset: 0;
      background:
        radial-gradient(circle at center, rgba(0, 0, 0, 0) 0%, rgba(0, 0, 0, 0.42) 100%),
        linear-gradient(180deg, rgba(5, 8, 6, 0.5) 0%, rgba(5, 8, 6, 0.05) 32%, rgba(5, 8, 6, 0.05) 68%, rgba(5, 8, 6, 0.55) 100%);
      pointer-events: none;
      z-index: 1;
    }}
    .scan-preview-frame img {{
      width: 100%;
      height: 100%;
      object-fit: cover;
      display: block;
      background: #050806;
    }}
    .scan-preview-placeholder {{
      position: absolute;
      inset: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 24px;
      text-align: center;
      color: #f8f4ea;
      background: #050806;
      font-size: clamp(0.95rem, 3vw, 1.1rem);
      line-height: 1.4;
      z-index: 2;
    }}
    .scan-overlay {{
      position: absolute;
      inset: 0;
      z-index: 3;
      display: grid;
      grid-template-rows: auto 1fr auto;
      gap: 12px;
      padding: clamp(16px, 3vw, 28px);
      pointer-events: none;
    }}
    .scan-overlay-top {{
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 12px;
      flex-wrap: wrap;
    }}
    .scan-overlay-actions {{
      display: flex;
      gap: 10px;
      align-items: center;
      justify-content: flex-end;
      flex-wrap: wrap;
      pointer-events: auto;
    }}
    .scan-hud-chip,
    .scan-mini-chip,
    .scan-status-card,
    .scan-close-button {{
      border: 1px solid rgba(255, 255, 255, 0.14);
      background: rgba(12, 14, 15, 0.72);
      color: #f8f4ea;
      backdrop-filter: blur(14px);
      box-shadow: 0 12px 28px rgba(0, 0, 0, 0.32);
    }}
    .scan-hud-chip {{
      min-height: 60px;
      padding: 12px 16px;
      border-radius: 16px;
      max-width: min(100%, 360px);
    }}
    .scan-hud-kicker {{
      font-size: 0.7rem;
      letter-spacing: 0.18em;
      text-transform: uppercase;
      font-weight: 700;
      color: rgba(248, 244, 234, 0.72);
    }}
    .scan-hud-title {{
      margin-top: 6px;
      font-family: "Space Grotesk", "Segoe UI", Tahoma, sans-serif;
      font-size: clamp(1rem, 2vw, 1.25rem);
      font-weight: 800;
      letter-spacing: -0.03em;
    }}
    .scan-mini-chip {{
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-height: 48px;
      padding: 0 14px;
      border-radius: 999px;
      font-size: 0.78rem;
      font-weight: 700;
      letter-spacing: 0.14em;
      text-transform: uppercase;
      color: #ffb693;
    }}
    .scan-close-button {{
      width: auto !important;
      min-height: 56px !important;
      padding: 0 18px !important;
      border-radius: 16px !important;
      background: rgba(12, 14, 15, 0.82) !important;
      color: #f8f4ea !important;
      font-size: 0.92rem !important;
      letter-spacing: 0.08em;
      text-transform: uppercase;
      pointer-events: auto;
      box-shadow: 0 12px 28px rgba(0, 0, 0, 0.34);
    }}
    .scan-guides {{
      position: relative;
      display: flex;
      align-items: center;
      justify-content: center;
      pointer-events: none;
    }}
    .scan-viewfinder {{
      position: relative;
      width: min(76vw, 860px);
      height: min(62vh, 430px);
      max-width: calc(100vw - 56px);
      max-height: calc(100vh - 196px);
      border-radius: 28px;
      box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.08);
    }}
    .scan-corner {{
      position: absolute;
      width: clamp(38px, 5vw, 58px);
      height: clamp(38px, 5vw, 58px);
      border-color: #ff6b00;
      border-style: solid;
      filter: drop-shadow(0 0 14px rgba(255, 107, 0, 0.35));
    }}
    .scan-corner.tl {{
      top: 0;
      left: 0;
      border-width: 4px 0 0 4px;
      border-top-left-radius: 20px;
    }}
    .scan-corner.tr {{
      top: 0;
      right: 0;
      border-width: 4px 4px 0 0;
      border-top-right-radius: 20px;
    }}
    .scan-corner.bl {{
      bottom: 0;
      left: 0;
      border-width: 0 0 4px 4px;
      border-bottom-left-radius: 20px;
    }}
    .scan-corner.br {{
      bottom: 0;
      right: 0;
      border-width: 0 4px 4px 0;
      border-bottom-right-radius: 20px;
    }}
    .scan-scanline {{
      position: absolute;
      left: 8%;
      right: 8%;
      top: 50%;
      height: 2px;
      background: linear-gradient(90deg, transparent, #ffb693, transparent);
      box-shadow: 0 0 18px rgba(255, 107, 0, 0.8);
      animation: scan-sweep 2.8s ease-in-out infinite;
      opacity: 0.92;
    }}
    @keyframes scan-sweep {{
      0%, 100% {{
        transform: translateY(-44%);
        opacity: 0.25;
      }}
      50% {{
        transform: translateY(44%);
        opacity: 1;
      }}
    }}
    .scan-focus-dot {{
      position: absolute;
      top: 50%;
      left: 50%;
      width: 18px;
      height: 18px;
      border-radius: 999px;
      border: 2px solid rgba(255, 182, 147, 0.9);
      transform: translate(-50%, -50%);
      box-shadow: 0 0 18px rgba(255, 107, 0, 0.26);
    }}
    .scan-focus-dot::after {{
      content: "";
      position: absolute;
      inset: 5px;
      border-radius: 999px;
      background: #ff6b00;
    }}
    .scan-overlay-bottom {{
      display: flex;
      justify-content: center;
      align-items: flex-end;
    }}
    .scan-status-card {{
      min-width: min(100%, 360px);
      max-width: min(100%, 560px);
      padding: 14px 18px;
      border-radius: 18px;
    }}
    .scan-status-row {{
      display: flex;
      align-items: flex-start;
      gap: 12px;
    }}
    .scan-status-led {{
      flex: 0 0 auto;
      width: 11px;
      height: 11px;
      margin-top: 7px;
      border-radius: 999px;
      background: #ff6b00;
      box-shadow: 0 0 14px rgba(255, 107, 0, 0.7);
    }}
    .scan-status-card[data-tone="danger"] .scan-status-led {{
      background: #ffb4ab;
      box-shadow: 0 0 14px rgba(255, 180, 171, 0.7);
    }}
    .scan-status-card[data-tone="success"] .scan-status-led {{
      background: #78dc77;
      box-shadow: 0 0 14px rgba(120, 220, 119, 0.7);
    }}
    .scan-status-card[data-tone="processing"] .scan-status-led {{
      background: #ffb693;
      box-shadow: 0 0 14px rgba(255, 182, 147, 0.7);
    }}
    .scan-status-title {{
      font-family: "Space Grotesk", "Segoe UI", Tahoma, sans-serif;
      font-size: clamp(1rem, 2vw, 1.2rem);
      font-weight: 800;
      letter-spacing: -0.03em;
    }}
    .scan-status-note {{
      margin-top: 4px;
      color: rgba(248, 244, 234, 0.72);
      line-height: 1.45;
      font-size: 0.92rem;
    }}
    @media (max-width: 719px) {{
      .scan-overlay {{
        padding: 14px;
      }}
      .scan-overlay-top {{
        align-items: stretch;
      }}
      .scan-viewfinder {{
        width: calc(100vw - 32px);
        height: min(52vh, 320px);
        max-height: calc(100vh - 180px);
      }}
      .scan-hud-chip {{
        max-width: 100%;
      }}
      .scan-close-button {{
        flex: 1 1 auto;
        justify-content: center;
      }}
    }}
    .shutdown-modal,
    .shutdown-progress {{
      position: fixed;
      inset: 0;
      z-index: 52;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 16px;
      background: rgba(5, 8, 6, 0.76);
      backdrop-filter: blur(12px);
    }}
    .shutdown-progress {{
      z-index: 54;
      background: rgba(5, 8, 6, 0.88);
    }}
    .shutdown-dialog,
    .shutdown-progress-card {{
      width: min(100%, 380px);
      display: grid;
      gap: 14px;
      border-radius: 18px;
      border: 1px solid var(--line);
      background: var(--panel);
      box-shadow: 0 18px 32px var(--shadow-strong);
      color: var(--ink);
      padding: 18px;
    }}
    .shutdown-progress-card {{
      background: linear-gradient(180deg, var(--panel), var(--panel-strong));
      color: #f8f4ea;
      text-align: center;
    }}
    .shutdown-dialog-head {{
      display: flex;
      align-items: center;
      gap: 12px;
    }}
    .shutdown-icon,
    .shutdown-progress-icon {{
      width: 48px;
      height: 48px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border-radius: 14px;
      font-size: 1.25rem;
      line-height: 1;
    }}
    .shutdown-icon {{
      background: var(--danger-soft);
      color: var(--danger);
    }}
    .shutdown-progress-icon {{
      margin: 0 auto;
      background: rgba(255, 107, 0, 0.16);
      color: #ffb693;
      animation: shutdown-pulse 1.3s ease-in-out infinite;
    }}
    @keyframes shutdown-pulse {{
      0%, 100% {{
        transform: scale(1);
        opacity: 0.72;
      }}
      50% {{
        transform: scale(1.06);
        opacity: 1;
      }}
    }}
    .shutdown-title,
    .shutdown-progress-title {{
      font-family: "Space Grotesk", "Segoe UI", Tahoma, sans-serif;
      font-size: 1.1rem;
      font-weight: 800;
      letter-spacing: -0.03em;
    }}
    .shutdown-note,
    .shutdown-progress-note {{
      color: var(--muted);
      line-height: 1.45;
      font-size: 0.92rem;
    }}
    .shutdown-progress-note {{
      color: rgba(248, 244, 234, 0.76);
    }}
    .shutdown-error {{
      min-height: 1.2em;
      color: var(--danger);
      font-size: 0.88rem;
      line-height: 1.4;
    }}
    .shutdown-actions {{
      display: flex;
      gap: 10px;
    }}
    .shutdown-actions button {{
      flex: 1 1 0;
    }}
    .content-grid {{
      display: grid;
      gap: 16px;
      margin-top: 18px;
    }}
    .content-column {{
      display: grid;
      gap: 16px;
      align-content: start;
    }}
    html[data-ui-scale="fit"] .topbar-kicker {{
      display: none;
    }}
    html[data-ui-scale="fit"] .topbar-title {{
      font-size: 0.98rem;
      line-height: 1.1;
    }}
    html[data-ui-scale="fit"] .theme-toggle-label,
    html[data-ui-scale="fit"] .readiness-pill-label,
    html[data-ui-scale="fit"] .power-button-label {{
      display: none;
    }}
    html[data-ui-scale="fit"] .readiness-pill-value {{
      font-size: 0.92rem;
    }}
    html[data-ui-scale="fit"] .hero-shell-inner,
    html[data-ui-scale="fit"] .hero-score-row,
    html[data-ui-scale="fit"] .hero-progress,
    html[data-ui-scale="fit"] .summary-grid,
    html[data-ui-scale="fit"] .alert-columns,
    html[data-ui-scale="fit"] .content-grid,
    html[data-ui-scale="fit"] .content-column,
    html[data-ui-scale="fit"] .inventory-panel,
    html[data-ui-scale="fit"] .inventory-manager-shell {{
      gap: 10px;
    }}
    html[data-ui-scale="fit"] .readiness-ring {{
      width: min(100%, 128px);
      box-shadow: inset 0 0 0 7px var(--accent-soft);
    }}
    html[data-ui-scale="fit"] .readiness-score {{
      font-size: clamp(2rem, 7vw, 2.8rem);
    }}
    html[data-ui-scale="fit"] .hero-text h1 {{
      font-size: clamp(1.2rem, 4vw, 1.65rem);
    }}
    html[data-ui-scale="fit"] .hero-note,
    html[data-ui-scale="fit"] .stat-note,
    html[data-ui-scale="fit"] .row-subtitle,
    html[data-ui-scale="fit"] .batch-card-subtitle,
    html[data-ui-scale="fit"] .inventory-form-description,
    html[data-ui-scale="fit"] .inventory-groups-caption {{
      font-size: 0.86rem;
      line-height: 1.38;
    }}
    html[data-ui-scale="fit"] .progress-meta,
    html[data-ui-scale="fit"] .panel-note {{
      font-size: 0.84rem;
    }}
    html[data-ui-scale="fit"] .quick-link strong {{
      font-size: 0.88rem;
    }}
    html[data-ui-scale="fit"] .quick-link small {{
      display: none;
    }}
    html[data-ui-scale="fit"] .tag,
    html[data-ui-scale="fit"] .pill,
    html[data-ui-scale="fit"] .micro-chip {{
      min-height: 30px;
      padding: 5px 10px;
      font-size: 0.72rem;
    }}
    html[data-ui-scale="fit"] textarea {{
      min-height: 72px;
    }}
    html[data-ui-scale="fit"] .banner {{
      padding: 10px 12px;
      margin-bottom: 10px;
    }}
    html[data-ui-scale="fit"] .shutdown-dialog,
    html[data-ui-scale="fit"] .shutdown-progress-card {{
      padding: 14px;
      gap: 12px;
    }}
    html[data-ui-scale="fit"] .shutdown-title,
    html[data-ui-scale="fit"] .shutdown-progress-title {{
      font-size: 1rem;
    }}
    html[data-ui-scale="fit"] .shutdown-note,
    html[data-ui-scale="fit"] .shutdown-progress-note {{
      font-size: 0.84rem;
    }}
    @media (min-width: 720px) {{
      .hero-score-row {{
        grid-template-columns: auto 1fr;
      }}
      .summary-grid {{
        grid-template-columns: repeat(3, minmax(0, 1fr));
      }}
      .alert-columns {{
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }}
      .qr-wrap {{
        grid-template-columns: 240px 1fr;
      }}
      .field-grid {{
        grid-template-columns: 1.1fr 0.7fr 0.9fr;
      }}
      .field-grid.settings-grid {{
        grid-template-columns: 1fr auto;
      }}
      .inventory-manager-shell {{
        grid-template-columns: minmax(0, 1.3fr) minmax(240px, 0.9fr);
        align-items: start;
      }}
      .inventory-group-head {{
        grid-template-columns: auto 1fr;
      }}
      .inventory-batch-grid {{
        grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      }}
    }}
    @media (min-width: 980px) {{
      main {{
        padding: 22px 24px 48px;
      }}
      .topbar {{
        padding: 16px 24px;
      }}
      .overview-grid {{
        grid-template-columns: minmax(0, 1.45fr) minmax(340px, 0.95fr);
        align-items: start;
      }}
      .hero-shell {{
        grid-row: span 2;
      }}
      .content-grid {{
        grid-template-columns: minmax(320px, 0.78fr) minmax(0, 1.22fr);
        align-items: start;
      }}
    }}
    @media (max-width: 719px) {{
      .topbar {{
        align-items: flex-start;
      }}
      .topbar-actions {{
        width: 100%;
        justify-content: stretch;
      }}
      .readiness-pill,
      .theme-toggle,
      .view-controls,
      .power-button {{
        flex: 1 1 0;
        justify-content: center;
      }}
      .quick-actions {{
        grid-template-columns: 1fr;
      }}
      html[data-ui-scale="fit"] .topbar {{
        align-items: center;
      }}
      html[data-ui-scale="fit"] .topbar-actions {{
        width: auto;
        flex-wrap: nowrap;
        justify-content: flex-end;
      }}
      html[data-ui-scale="fit"] .readiness-pill,
      html[data-ui-scale="fit"] .theme-toggle,
      html[data-ui-scale="fit"] .view-controls,
      html[data-ui-scale="fit"] .power-button {{
        flex: 0 0 auto;
      }}
    }}
</style>
</head>
<body data-state-version="{escape(view_model['state_version'])}" data-open-scan="{'1' if open_scan else '0'}">
  <header class="topbar">
    <div class="topbar-brand">
      <div class="brand-mark">GB</div>
      <div>
        <div class="topbar-kicker">Pi Hub Dashboard</div>
        <div class="topbar-title">GO BAG Command Center</div>
      </div>
    </div>
    <div class="topbar-actions">
      <div class="readiness-pill">
        <span class="readiness-pill-value">{readiness_percent}%</span>
        <span class="readiness-pill-label">Ready</span>
      </div>
      <div class="view-controls" role="group" aria-label="Adjust display size">
        <button type="button" class="zoom-toggle" id="zoom-out" aria-label="Zoom out" title="Zoom out">-</button>
        <span class="zoom-indicator" id="zoom-indicator">100%</span>
        <button type="button" class="zoom-toggle" id="zoom-in" aria-label="Zoom in" title="Zoom in">+</button>
      </div>
      <button type="button" class="power-button" id="power-button" aria-label="Shut down Raspberry Pi" title="Shut down Raspberry Pi">
        <span class="power-button-icon" aria-hidden="true">&#x23FB;</span>
        <span class="power-button-label">Power</span>
      </button>
      <button type="button" class="theme-toggle" id="theme-toggle" aria-label="Switch to dark mode" title="Switch to dark mode">
        <span class="theme-toggle-icon" id="theme-toggle-icon" aria-hidden="true">&#9791;</span>
        <span class="theme-toggle-label" id="theme-toggle-label">Dark mode</span>
      </button>
    </div>
  </header>
  <main>
    <div class="banner notice {'hidden' if not notice else ''}" id="page-notice">{escape(notice)}</div>
    <div class="banner error {'hidden' if not error else ''}" id="page-error">{escape(error)}</div>

    <section class="overview-grid">
      <section class="hero-shell">
        <div class="hero-shell-inner">
          <div class="hero-kicker">System readiness</div>
          <div class="hero-score-row">
            <div class="readiness-ring" style="--score-color: {readiness_color}; --readiness-angle: {round(readiness_percent * 3.6)}deg;">
              <div class="readiness-score">{readiness_percent}<span>%</span></div>
            </div>
            <div class="hero-text">
              <h1 id="hero-bag-name">{escape(bag.name)}</h1>
              <p class="hero-note" id="hero-next-step">{escape(view_model['next_step'])}</p>
              <div class="hero-tags" id="hero-tags">{view_model['hero_tags_html']}</div>
            </div>
          </div>
          <div class="hero-progress">
            <div class="progress-meta">
              <span>Checklist coverage</span>
              <strong>{view_model['checked_count']}/{readiness['checklist_total']} categories covered</strong>
            </div>
            <div class="progress-bar"><span style="width: {readiness_percent}%; --score-color: {readiness_color};"></span></div>
          </div>
          <section class="summary-grid" aria-label="Status" id="summary-grid">
            {view_model['summary_html']}
          </section>
        </div>
      </section>

      <section class="panel mission-panel">
        <div class="panel-head">
          <div>
            <div class="panel-title">Operational shortcuts</div>
            <div class="panel-note">Fast actions for pairing, inventory, and scanner control on a small landscape touchscreen.</div>
          </div>
          <div class="pill" id="bag-size-badge">{escape(bag_size_label(bag.size_liters))}</div>
        </div>
        <div class="quick-actions">
          <button type="button" class="quick-link primary" data-open-scan="1">
            <span>
              <strong>Scan Item</strong>
              <small>USB camera capture</small>
            </span>
            <span class="quick-link-icon" aria-hidden="true">QR</span>
          </button>
          <a class="quick-link" href="#pairing-panel">
            <span>
              <strong>Pair Phone</strong>
              <small>Open QR handoff</small>
            </span>
            <span class="quick-link-icon" aria-hidden="true">&rarr;</span>
          </a>
          <a class="quick-link" href="#inventory-panel">
            <span>
              <strong>Inventory</strong>
              <small>Add or edit batches</small>
            </span>
            <span class="quick-link-icon" aria-hidden="true">&rarr;</span>
          </a>
          <a class="quick-link" href="#checklist-panel">
            <span>
              <strong>Checklist</strong>
              <small>Review category coverage</small>
            </span>
            <span class="quick-link-icon" aria-hidden="true">&rarr;</span>
          </a>
        </div>
        <div class="section-divider"></div>
        <div class="panel-subtitle">Missing categories</div>
        <div class="tag-row">{view_model['missing_html']}</div>
      </section>

      <section class="panel paired-panel">
        <div class="panel-head">
          <div>
            <div class="panel-title">Paired phones</div>
            <div class="panel-note">Devices currently allowed to sync with this Raspberry Pi.</div>
          </div>
        </div>
        <div id="paired-phones">{view_model['phone_rows_html']}</div>
        <form method="post" action="/ui/revoke_tokens{admin_query}">
          <button type="submit" class="secondary danger">Revoke all phone access</button>
        </form>
      </section>

      <section class="panel alerts-panel">
        <div class="panel-head">
          <div>
            <div class="panel-title">Readiness watchlist</div>
            <div class="panel-note">Catch coverage gaps, expiry issues, and sync blockers before you leave.</div>
          </div>
        </div>
        <div class="alert-columns">
          <div class="subpanel">
            <div class="panel-subtitle">Readiness</div>
            <div id="alerts-readiness">{view_model['readiness_alert_rows']}</div>
          </div>
          <div class="subpanel">
            <div class="panel-subtitle">Expiry</div>
            <div id="alerts-expiry">{view_model['expiry_alert_rows']}</div>
          </div>
        </div>
      </section>
    </section>

    <section class="content-grid">
      <div class="content-column">
        <section class="panel" id="bag-settings-panel">
          <div class="panel-head">
            <div>
              <div class="panel-title">Bag Settings</div>
              <div class="panel-note">This Raspberry Pi keeps one local GO BAG. The only bag-specific setting here is the bag size.</div>
            </div>
          </div>
          <div class="list-row" style="margin-top: 0;">
            <div>
              <div class="row-title" id="bag-name-label">{escape(bag.name)}</div>
              <div class="row-subtitle">Device: {escape(DEVICE_NAME)}</div>
            </div>
          </div>
          <form method="post" action="/ui/bag/settings">
            <div class="field-grid settings-grid">
              <select name="bag_type" aria-label="Bag size">
                {view_model['bag_size_options']}
              </select>
              <button type="submit">Save bag size</button>
            </div>
          </form>
        </section>

        <section class="panel" id="pairing-panel">
          <div class="panel-head">
            <div>
              <div class="panel-title">Pairing QR</div>
              <div class="panel-note">Open the Android app, go to Pair, and scan this GO BAG QR code.</div>
            </div>
            <div class="pill warn" id="pair-expires">Expires {escape(format_time_ms(int(view_model['pair']['expires_at'])))}</div>
          </div>
          <div id="pairing-card">{view_model['pairing_card_html']}</div>
          <form method="post" action="/ui/pair-code/new{admin_query}">
            <button type="submit">Generate new pair code</button>
          </form>
        </section>

        <section class="panel" id="checklist-panel">
          <div class="panel-head">
            <div>
              <div class="panel-title">Checklist</div>
              <div class="panel-note">These are the core categories every go-bag should cover.</div>
            </div>
            <div class="pill">{view_model['checked_count']}/{readiness['checklist_total']}</div>
          </div>
          {checklist_rows}
        </section>
      </div>

      <div class="content-column content-column-wide">
        <section class="panel inventory-panel" id="inventory-panel">
          <div class="panel-head">
            <div>
              <div class="panel-title">Inventory Manager</div>
              <div class="panel-note">Manual add and QR scan both merge by item name, unit, and category. Different expiration dates stay as separate batches under one grouped item.</div>
            </div>
            <div class="pill {'warn' if edit_item else ''}">{'Edit mode' if edit_item else 'Add mode'}</div>
          </div>
          <div class="inventory-manager-shell">
            <section class="inventory-entry-panel">
              <div class="inventory-form-kicker">{escape(inventory_form_kicker)}</div>
              <div class="inventory-form-title">{escape(inventory_form_title)}</div>
              <div class="inventory-form-description">{escape(inventory_form_description)}</div>
              <form method="post" action="/ui/items/save">
                <input type="hidden" name="bag_id" value="{escape(bag.bag_id)}">
                <input type="hidden" name="item_id" value="{escape(edit_item.id if edit_item else '')}">
                <div class="inventory-form-grid">
                  <div class="field-grid">
                    <input type="text" name="name" placeholder="Item name" value="{escape(edit_item.name if edit_item else '')}" required>
                    <input type="number" step="0.01" min="0.01" name="quantity" placeholder="Quantity" value="{edit_item.quantity if edit_item else '1'}" required>
                    <input type="text" name="unit" placeholder="Unit" value="{escape(edit_item.unit if edit_item else 'pcs')}" required>
                  </div>
                  <div class="field-grid">
                    <select name="category_id" required>
                      {category_options}
                    </select>
                    <input type="date" name="expiry_date" value="{escape(edit_item.expiry_date if edit_item and edit_item.expiry_date else '')}">
                    <label class="inventory-checkbox-row">
                      <input type="checkbox" name="packed_status" {"checked" if edit_item and edit_item.packed_status else ""}>
                      Mark batch as packed
                    </label>
                  </div>
                  <textarea name="notes" placeholder="Notes">{escape(edit_item.notes if edit_item else '')}</textarea>
                </div>
                <div class="button-row">
                  <button type="submit">{escape(inventory_submit_label)}</button>
                  <button type="button" class="secondary" data-open-scan="1">Scan with USB camera</button>
                </div>
              </form>
            </section>

            <aside class="inventory-command-panel">
              <div class="inventory-note-card">
                <strong>Scanner status</strong>
                <p id="inventory-live-note">{escape(view_model['inventory_notice'])}</p>
              </div>
              <div class="inventory-note-card">
                <strong>Grouping rule</strong>
                <p>Batches merge by item name, unit, and category. Expiration dates stay separate so replacements stay precise.</p>
              </div>
              <div class="inventory-note-card">
                <strong>Fast action</strong>
                <p>Use the USB camera to capture QR labels without leaving this dashboard.</p>
                <div class="button-row compact">
                  <button type="button" class="secondary" data-open-scan="1">Open scanner</button>
                </div>
              </div>
            </aside>
          </div>

          <section class="inventory-groups-shell">
            <div class="inventory-groups-head">
              <div>
                <div class="inventory-groups-kicker">Grouped inventory</div>
                <div class="inventory-groups-title">Supply batches</div>
                <div class="inventory-groups-caption">Each grouped card keeps separate batches visible for expiry review, packing status, edit, and delete actions.</div>
              </div>
              <div class="inventory-groups-meta">
                <span class="micro-chip">Local Pi store</span>
                <span class="micro-chip">Grouped by item</span>
                <span class="micro-chip">Edit in place</span>
              </div>
            </div>
            <div id="inventory-groups">{view_model['inventory_groups_html']}</div>
          </section>
        </section>
      </div>
    </section>

    <div class="scan-modal hidden" id="scan-modal" aria-hidden="true">
      <section class="scan-dialog" aria-label="USB QR scanner">
        <div class="scan-preview-shell">
          <div class="scan-preview-frame">
            <img id="scan-preview-image" class="hidden" alt="USB camera preview">
            <div class="scan-preview-placeholder" id="scan-preview-placeholder"></div>
          </div>
        </div>
        <div class="scan-overlay" aria-hidden="true">
          <div class="scan-overlay-top">
            <div class="scan-hud-chip">
              <div class="scan-hud-kicker">USB QR scanner</div>
              <div class="scan-hud-title">{escape(bag.name)}</div>
            </div>
            <div class="scan-overlay-actions">
              <div class="scan-mini-chip" id="scan-live-chip">Auto detect</div>
              <button type="button" class="scan-close-button" id="scan-close-button">Close</button>
            </div>
          </div>
          <div class="scan-guides">
            <div class="scan-viewfinder">
              <div class="scan-corner tl"></div>
              <div class="scan-corner tr"></div>
              <div class="scan-corner bl"></div>
              <div class="scan-corner br"></div>
              <div class="scan-scanline"></div>
              <div class="scan-focus-dot"></div>
            </div>
          </div>
          <div class="scan-overlay-bottom">
            <div class="scan-status-card" id="scan-status-card" data-tone="active">
              <div class="scan-status-row">
                <div class="scan-status-led"></div>
                <div>
                  <div class="scan-status-title" id="scan-status-title">Starting camera</div>
                  <div class="scan-status-note" id="scan-status-note">Opening the USB preview for automatic QR detection.</div>
                </div>
              </div>
            </div>
          </div>
        </div>
        <form id="scan-capture-form" class="hidden">
          <input type="hidden" name="bag_id" value="{escape(bag.bag_id)}">
        </form>
      </section>
    </div>
    <div class="shutdown-modal hidden" id="shutdown-modal" aria-hidden="true">
      <section class="shutdown-dialog" aria-label="Confirm Raspberry Pi shutdown">
        <div class="shutdown-dialog-head">
          <div class="shutdown-icon" aria-hidden="true">&#x23FB;</div>
          <div>
            <div class="shutdown-title">Shut down Raspberry Pi?</div>
            <div class="shutdown-note">This safely stops GO BAG services and powers off the Pi. Wait until the screen turns off before disconnecting power.</div>
          </div>
        </div>
        <div class="shutdown-error hidden" id="shutdown-error"></div>
        <div class="shutdown-actions">
          <button type="button" class="secondary" id="shutdown-cancel-button">Cancel</button>
          <button type="button" id="shutdown-confirm-button">Shut down now</button>
        </div>
      </section>
    </div>
    <div class="shutdown-progress hidden" id="shutdown-progress" aria-hidden="true">
      <section class="shutdown-progress-card" aria-label="Raspberry Pi shutdown in progress">
        <div class="shutdown-progress-icon" aria-hidden="true">&#x23FB;</div>
        <div class="shutdown-progress-title">Shutdown in progress</div>
        <div class="shutdown-progress-note" id="shutdown-progress-note">Wait until the screen turns off before disconnecting power.</div>
      </section>
    </div>
    {render_touch_keyboard_html()}
  </main>
  <script>
    (() => {{
      const refreshIntervalMs = {UI_REFRESH_INTERVAL_MS};
      const previewFallbackIntervalMs = {USB_PREVIEW_INTERVAL_MS};
      const autoScanFallbackIntervalMs = {USB_AUTO_SCAN_INTERVAL_MS};
      let lastStateVersion = document.body.dataset.stateVersion || "";
      let previewTimer = 0;
      let autoScanTimer = 0;
      let autoScanStartTimer = 0;
      let liveDetectTimer = 0;
      let previewLoading = false;
      let scanBusy = false;
      let scanCooldownUntil = 0;
      let scanIntervalMs = autoScanFallbackIntervalMs;
      let previewMode = "snapshot";
      let previewUrl = "/camera/usb/preview.jpg";
      let snapshotPreviewUrl = "/camera/usb/preview.jpg";
      let scanSessionId = "";
      let scanFrameUrl = "/camera/usb/session/frame.jpg";
      let scanStatusTimer = 0;
      let latestSessionFrameId = 0;
      let previewFailureCount = 0;
      let barcodeDetector = undefined;
      let detectorFallbackActive = false;
      let lastDetectedRawContent = "";
      let lastDetectedAt = 0;

      const pageNotice = document.getElementById("page-notice");
      const pageError = document.getElementById("page-error");
      const documentRoot = document.documentElement;
      const themeStorageKey = "gobag-pi-theme";
      const uiScaleStorageKey = "gobag-pi-ui-scale";
      const shutdownRequestUrl = "/ui/system/shutdown{admin_query}";
      const themeToggle = document.getElementById("theme-toggle");
      const themeToggleIcon = document.getElementById("theme-toggle-icon");
      const themeToggleLabel = document.getElementById("theme-toggle-label");
      const zoomOutButton = document.getElementById("zoom-out");
      const zoomInButton = document.getElementById("zoom-in");
      const zoomIndicator = document.getElementById("zoom-indicator");
      const powerButton = document.getElementById("power-button");
      const shutdownModal = document.getElementById("shutdown-modal");
      const shutdownCancelButton = document.getElementById("shutdown-cancel-button");
      const shutdownConfirmButton = document.getElementById("shutdown-confirm-button");
      const shutdownError = document.getElementById("shutdown-error");
      const shutdownProgress = document.getElementById("shutdown-progress");
      const shutdownProgressNote = document.getElementById("shutdown-progress-note");
      const scanModal = document.getElementById("scan-modal");
      const scanOpenButtons = Array.from(document.querySelectorAll('[data-open-scan="1"]'));
      const scanCaptureForm = document.getElementById("scan-capture-form");
      const scanPreviewImage = document.getElementById("scan-preview-image");
      const scanPreviewPlaceholder = document.getElementById("scan-preview-placeholder");
      const scanCloseButton = document.getElementById("scan-close-button");
      const scanLiveChip = document.getElementById("scan-live-chip");
      const scanStatusCard = document.getElementById("scan-status-card");
      const scanStatusTitle = document.getElementById("scan-status-title");
      const scanStatusNote = document.getElementById("scan-status-note");
      const touchKeyboard = document.getElementById("touch-keyboard");
      const scrollStateKey = "gobag-scroll-state";
      const keyboardEligibleSelector = 'input:not([type="hidden"]):not([type="checkbox"]):not([type="date"]):not([type="file"]):not([type="radio"]):not([type="range"]):not([disabled]), textarea:not([disabled])';
      let keyboardTarget = null;
      let keyboardShift = false;
      let shutdownPending = false;

      function currentTheme() {{
        return documentRoot.dataset.theme === "dark" ? "dark" : "light";
      }}

      function applyTheme(theme, persist = true) {{
        const nextTheme = theme === "dark" ? "dark" : "light";
        documentRoot.dataset.theme = nextTheme;
        const darkMode = nextTheme === "dark";
        if (persist) {{
          try {{
            localStorage.setItem(themeStorageKey, nextTheme);
          }} catch (_error) {{
          }}
        }}
        if (themeToggle) {{
          const nextLabel = darkMode ? "Light mode" : "Dark mode";
          const nextHint = darkMode ? "Switch to light mode" : "Switch to dark mode";
          themeToggle.setAttribute("aria-pressed", darkMode ? "true" : "false");
          themeToggle.setAttribute("aria-label", nextHint);
          themeToggle.setAttribute("title", nextHint);
          if (themeToggleLabel) {{
            themeToggleLabel.textContent = nextLabel;
          }}
          if (themeToggleIcon) {{
            themeToggleIcon.textContent = darkMode ? "\\u2600" : "\\u263D";
          }}
        }}
      }}

      function saveScrollState() {{
        try {{
          sessionStorage.setItem(
            scrollStateKey,
            JSON.stringify({{
              path: window.location.pathname,
              y: Math.max(window.scrollY || 0, 0),
              ts: Date.now(),
            }}),
          );
        }} catch (_error) {{
        }}
      }}

      function restoreScrollState() {{
        try {{
          const rawState = sessionStorage.getItem(scrollStateKey);
          if (!rawState) {{
            return;
          }}
          sessionStorage.removeItem(scrollStateKey);
          const state = JSON.parse(rawState);
          if (!state || state.path !== window.location.pathname) {{
            return;
          }}
          const top = Math.max(Number(state.y || 0), 0);
          const applyScroll = () => {{
            window.scrollTo({{ top, left: 0, behavior: "auto" }});
          }};
          window.requestAnimationFrame(applyScroll);
          window.setTimeout(applyScroll, 120);
          window.setTimeout(applyScroll, 260);
        }} catch (_error) {{
        }}
      }}

      function initializeTheme() {{
        let storedTheme = "";
        try {{
          storedTheme = localStorage.getItem(themeStorageKey) || "";
        }} catch (_error) {{
        }}
        applyTheme(storedTheme === "dark" || storedTheme === "light" ? storedTheme : currentTheme(), false);
      }}

      function defaultUiScale() {{
        return window.matchMedia("(max-width: 520px), (max-height: 360px)").matches ? "fit" : "standard";
      }}

      function currentUiScale() {{
        return documentRoot.dataset.uiScale === "fit" ? "fit" : "standard";
      }}

      function updateZoomControls() {{
        const fitMode = currentUiScale() === "fit";
        if (zoomOutButton) {{
          zoomOutButton.disabled = fitMode;
        }}
        if (zoomInButton) {{
          zoomInButton.disabled = !fitMode;
        }}
        if (zoomIndicator) {{
          zoomIndicator.textContent = fitMode ? "Fit" : "100%";
        }}
      }}

      function syncTouchKeyboardOffset() {{
        const keyboardHeight =
          touchKeyboard && !touchKeyboard.classList.contains("hidden")
            ? Math.ceil(touchKeyboard.getBoundingClientRect().height)
            : 0;
        documentRoot.style.setProperty("--touch-keyboard-offset", `${{Math.max(keyboardHeight, 0)}}px`);
      }}

      function applyUiScale(scale, persist = true) {{
        const nextScale = scale === "fit" ? "fit" : "standard";
        documentRoot.dataset.uiScale = nextScale;
        if (persist) {{
          try {{
            localStorage.setItem(uiScaleStorageKey, nextScale);
          }} catch (_error) {{
          }}
        }}
        updateZoomControls();
        window.requestAnimationFrame(syncTouchKeyboardOffset);
      }}

      function initializeUiScale() {{
        let storedScale = "";
        try {{
          storedScale = localStorage.getItem(uiScaleStorageKey) || "";
        }} catch (_error) {{
        }}
        applyUiScale(storedScale === "fit" || storedScale === "standard" ? storedScale : defaultUiScale(), false);
      }}

      function setShutdownError(message = "") {{
        if (!shutdownError) {{
          return;
        }}
        if (message) {{
          shutdownError.textContent = message;
          shutdownError.classList.remove("hidden");
        }} else {{
          shutdownError.textContent = "";
          shutdownError.classList.add("hidden");
        }}
      }}

      function powerDialogIsOpen() {{
        return !!(shutdownModal && !shutdownModal.classList.contains("hidden"));
      }}

      function shutdownProgressIsOpen() {{
        return !!(shutdownProgress && !shutdownProgress.classList.contains("hidden"));
      }}

      function syncModalShellState() {{
        const anyModalOpen = scanIsOpen() || powerDialogIsOpen() || shutdownProgressIsOpen();
        documentRoot.classList.toggle("modal-open", anyModalOpen);
        document.body.classList.toggle("modal-open", anyModalOpen);
      }}

      function setPowerUiBusy(isBusy) {{
        if (powerButton) {{
          powerButton.disabled = isBusy;
        }}
        if (shutdownCancelButton) {{
          shutdownCancelButton.disabled = isBusy;
        }}
        if (shutdownConfirmButton) {{
          shutdownConfirmButton.disabled = isBusy;
          shutdownConfirmButton.textContent = isBusy ? "Shutting down..." : "Shut down now";
        }}
      }}

      function openShutdownModal() {{
        if (!shutdownModal || shutdownPending || scanIsOpen()) {{
          return;
        }}
        dismissTouchKeyboard();
        setShutdownError("");
        setPowerUiBusy(false);
        shutdownModal.classList.remove("hidden");
        shutdownModal.setAttribute("aria-hidden", "false");
        syncModalShellState();
      }}

      function closeShutdownModal() {{
        if (!shutdownModal || shutdownPending) {{
          return;
        }}
        shutdownModal.classList.add("hidden");
        shutdownModal.setAttribute("aria-hidden", "true");
        setShutdownError("");
        syncModalShellState();
      }}

      function showShutdownProgress(message) {{
        if (shutdownProgressNote) {{
          shutdownProgressNote.textContent = message || "Wait until the screen turns off before disconnecting power.";
        }}
        if (shutdownProgress) {{
          shutdownProgress.classList.remove("hidden");
          shutdownProgress.setAttribute("aria-hidden", "false");
        }}
        syncModalShellState();
      }}

      async function requestShutdown() {{
        if (shutdownPending) {{
          return;
        }}
        shutdownPending = true;
        setShutdownError("");
        setPowerUiBusy(true);
        try {{
          const response = await fetch(shutdownRequestUrl, {{
            method: "POST",
            headers: {{ "Accept": "application/json" }},
            cache: "no-store",
          }});
          const payload = await response.json().catch(() => ({{ ok: false, detail: "Shutdown request failed." }}));
          if (!response.ok || payload.ok === false) {{
            throw new Error(String(payload.detail || payload.message || "Shutdown request failed."));
          }}
          if (shutdownModal) {{
            shutdownModal.classList.add("hidden");
            shutdownModal.setAttribute("aria-hidden", "true");
          }}
          showShutdownProgress(payload.message || "Shutdown in progress. Wait until the screen turns off before disconnecting power.");
          setBanner("notice", payload.message || "Shutdown in progress.");
        }} catch (error) {{
          shutdownPending = false;
          setPowerUiBusy(false);
          setShutdownError(error instanceof Error ? error.message : "Shutdown request failed.");
          syncModalShellState();
        }}
      }}

      function keyboardTargetSupportsSelection(target) {{
        return target && typeof target.selectionStart === "number" && typeof target.selectionEnd === "number";
      }}

      function focusKeyboardTarget() {{
        if (!keyboardTarget) return;
        try {{
          keyboardTarget.focus({{ preventScroll: true }});
        }} catch (_error) {{
          keyboardTarget.focus();
        }}
      }}

      function dispatchKeyboardInput(target) {{
        target.dispatchEvent(new Event("input", {{ bubbles: true }}));
      }}

      function isKeyboardEligible(target) {{
        return !!(target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) && target.matches(keyboardEligibleSelector);
      }}

      function updateTouchKeyboard() {{
        if (!touchKeyboard) return;
        touchKeyboard.querySelectorAll("[data-key]").forEach((button) => {{
          const key = button.getAttribute("data-key") || "";
          if (/^[a-z]$/.test(key)) {{
            button.textContent = keyboardShift ? key.toUpperCase() : key;
          }}
        }});
        const shiftButton = touchKeyboard.querySelector('[data-keyboard-action="shift"]');
        if (shiftButton) {{
          shiftButton.classList.toggle("active", keyboardShift);
        }}
      }}

      function dismissTouchKeyboard() {{
        if (!touchKeyboard) return;
        keyboardTarget = null;
        keyboardShift = false;
        touchKeyboard.classList.add("hidden");
        touchKeyboard.setAttribute("aria-hidden", "true");
        document.body.classList.remove("keyboard-open");
        updateTouchKeyboard();
        syncTouchKeyboardOffset();
      }}

      function showTouchKeyboard(target) {{
        if (!touchKeyboard || !isKeyboardEligible(target) || scanIsOpen()) return;
        keyboardTarget = target;
        touchKeyboard.classList.remove("hidden");
        touchKeyboard.setAttribute("aria-hidden", "false");
        document.body.classList.add("keyboard-open");
        updateTouchKeyboard();
        window.requestAnimationFrame(syncTouchKeyboardOffset);
        window.setTimeout(syncTouchKeyboardOffset, 40);
        window.setTimeout(() => {{
          if (keyboardTarget === target) {{
            target.scrollIntoView({{ block: "center", behavior: "smooth" }});
          }}
        }}, 80);
      }}

      function insertKeyboardText(rawText) {{
        if (!keyboardTarget) return;
        focusKeyboardTarget();
        const supportsSelection = keyboardTargetSupportsSelection(keyboardTarget);
        const currentValue = keyboardTarget.value || "";
        const text = keyboardShift ? rawText.toUpperCase() : rawText;
        let nextValue = currentValue + text;
        let nextCaret = nextValue.length;
        if (supportsSelection) {{
          const start = keyboardTarget.selectionStart;
          const end = keyboardTarget.selectionEnd;
          nextValue = `${{currentValue.slice(0, start)}}${{text}}${{currentValue.slice(end)}}`;
          nextCaret = start + text.length;
        }}
        keyboardTarget.value = nextValue;
        if (supportsSelection) {{
          try {{
            keyboardTarget.setSelectionRange(nextCaret, nextCaret);
          }} catch (_error) {{
          }}
        }}
        dispatchKeyboardInput(keyboardTarget);
        if (keyboardShift) {{
          keyboardShift = false;
          updateTouchKeyboard();
        }}
      }}

      function backspaceKeyboardText() {{
        if (!keyboardTarget) return;
        focusKeyboardTarget();
        const supportsSelection = keyboardTargetSupportsSelection(keyboardTarget);
        const currentValue = keyboardTarget.value || "";
        if (!currentValue) return;
        let nextValue = currentValue.slice(0, -1);
        let nextCaret = nextValue.length;
        if (supportsSelection) {{
          const start = keyboardTarget.selectionStart;
          const end = keyboardTarget.selectionEnd;
          if (start !== end) {{
            nextValue = `${{currentValue.slice(0, start)}}${{currentValue.slice(end)}}`;
            nextCaret = start;
          }} else if (start > 0) {{
            nextValue = `${{currentValue.slice(0, start - 1)}}${{currentValue.slice(end)}}`;
            nextCaret = start - 1;
          }}
        }}
        keyboardTarget.value = nextValue;
        if (supportsSelection) {{
          try {{
            keyboardTarget.setSelectionRange(nextCaret, nextCaret);
          }} catch (_error) {{
          }}
        }}
        dispatchKeyboardInput(keyboardTarget);
      }}

      function clearKeyboardTarget() {{
        if (!keyboardTarget) return;
        focusKeyboardTarget();
        keyboardTarget.value = "";
        dispatchKeyboardInput(keyboardTarget);
      }}

      function handleTouchKeyboardButton(button) {{
        if (!button) return;
        const action = button.getAttribute("data-keyboard-action") || "";
        const key = button.getAttribute("data-key") || "";
        if (!action && key) {{
          insertKeyboardText(key);
          return;
        }}
        switch (action) {{
          case "shift":
            keyboardShift = !keyboardShift;
            updateTouchKeyboard();
            break;
          case "backspace":
            backspaceKeyboardText();
            break;
          case "space":
            insertKeyboardText(" ");
            break;
          case "clear":
            clearKeyboardTarget();
            break;
          case "done":
            if (keyboardTarget) {{
              keyboardTarget.blur();
            }}
            dismissTouchKeyboard();
            break;
          default:
            break;
        }}
      }}

      function setBanner(kind, text) {{
        const target = kind === "notice" ? pageNotice : pageError;
        const other = kind === "notice" ? pageError : pageNotice;
        if (target) {{
          if (text) {{
            target.textContent = text;
            target.classList.remove("hidden");
          }} else {{
            target.textContent = "";
            target.classList.add("hidden");
          }}
        }}
        if (text && other) {{
          other.textContent = "";
          other.classList.add("hidden");
        }}
      }}

      function setPreviewPlaceholderMessage(message) {{
        if (!scanPreviewPlaceholder) return;
        scanPreviewPlaceholder.textContent = message || "";
      }}

      function setScanUiStatus(title, note = "", tone = "active") {{
        if (scanStatusTitle) {{
          scanStatusTitle.textContent = title || "";
        }}
        if (scanStatusNote) {{
          scanStatusNote.textContent = note || "";
        }}
        if (scanStatusCard) {{
          scanStatusCard.dataset.tone = tone || "active";
        }}
        if (scanLiveChip) {{
          scanLiveChip.textContent =
            tone === "danger"
              ? "Attention"
              : tone === "processing"
              ? "Processing"
              : tone === "success"
              ? "Detected"
              : "Auto detect";
        }}
      }}

      function setPreviewVisible(isVisible) {{
        if (scanPreviewImage) scanPreviewImage.classList.toggle("hidden", !isVisible);
        if (scanPreviewPlaceholder) scanPreviewPlaceholder.classList.toggle("hidden", isVisible);
      }}

      function withCacheBust(url) {{
        const separator = url.includes("?") ? "&" : "?";
        return `${{url}}${{separator}}t=${{Date.now()}}`;
      }}

      function stopPreviewLoop() {{
        if (previewTimer) {{
          window.clearInterval(previewTimer);
          previewTimer = 0;
        }}
      }}

      function stopScanStatusLoop() {{
        if (scanStatusTimer) {{
          window.clearInterval(scanStatusTimer);
          scanStatusTimer = 0;
        }}
      }}

      function stopAutoScanLoop() {{
        if (autoScanTimer) {{
          window.clearInterval(autoScanTimer);
          autoScanTimer = 0;
        }}
        if (autoScanStartTimer) {{
          window.clearTimeout(autoScanStartTimer);
          autoScanStartTimer = 0;
        }}
      }}

      function stopLiveDetectLoop() {{
        if (liveDetectTimer) {{
          window.clearInterval(liveDetectTimer);
          liveDetectTimer = 0;
        }}
      }}

      function startPreviewLoop(intervalMs) {{
        stopPreviewLoop();
        previewTimer = window.setInterval(() => {{
          void refreshPreviewFrame();
        }}, intervalMs || previewFallbackIntervalMs);
      }}

      function startAutoScanLoop(intervalMs) {{
        stopAutoScanLoop();
        scanIntervalMs = intervalMs || autoScanFallbackIntervalMs;
        autoScanStartTimer = window.setTimeout(() => {{
          void submitUsbScan(null, true);
        }}, Math.min(scanIntervalMs, 1100));
        autoScanTimer = window.setInterval(() => {{
          void submitUsbScan(null, true);
        }}, scanIntervalMs);
      }}

      async function getBarcodeDetector() {{
        if (barcodeDetector !== undefined) {{
          return barcodeDetector;
        }}
        if (!("BarcodeDetector" in window)) {{
          barcodeDetector = null;
          return barcodeDetector;
        }}
        try {{
          barcodeDetector = new BarcodeDetector({{ formats: ["qr_code"] }});
        }} catch (_error) {{
          barcodeDetector = null;
        }}
        return barcodeDetector;
      }}

      function scanIsOpen() {{
        return !!(scanModal && !scanModal.classList.contains("hidden"));
      }}

      function resetScannerState() {{
        stopPreviewLoop();
        stopScanStatusLoop();
        stopAutoScanLoop();
        stopLiveDetectLoop();
        previewLoading = false;
        previewFailureCount = 0;
        scanBusy = false;
        scanCooldownUntil = 0;
        scanSessionId = "";
        latestSessionFrameId = 0;
        detectorFallbackActive = false;
        lastDetectedRawContent = "";
        lastDetectedAt = 0;
        setPreviewPlaceholderMessage("");
        setScanUiStatus(
          "Align QR inside frame",
          "The Pi will process the code automatically when the camera locks on.",
          "active",
        );
      }}

      async function refreshDashboard() {{
        try {{
          const response = await fetch("/ui/state", {{
            headers: {{ "Accept": "application/json" }},
            cache: "no-store",
          }});
          if (!response.ok) {{
            return;
          }}
          const state = await response.json();
          if (state.state_version === lastStateVersion) {{
            return;
          }}
          lastStateVersion = state.state_version || "";
          document.body.dataset.stateVersion = lastStateVersion;
          const heroNextStep = document.getElementById("hero-next-step");
          const heroTags = document.getElementById("hero-tags");
          const summaryGrid = document.getElementById("summary-grid");
          const alertsReadiness = document.getElementById("alerts-readiness");
          const alertsExpiry = document.getElementById("alerts-expiry");
          const pairExpires = document.getElementById("pair-expires");
          const pairingCard = document.getElementById("pairing-card");
          const inventoryLiveNote = document.getElementById("inventory-live-note");
          const inventoryGroups = document.getElementById("inventory-groups");
          const pairedPhones = document.getElementById("paired-phones");
          const heroBagName = document.getElementById("hero-bag-name");
          const bagNameLabel = document.getElementById("bag-name-label");
          const bagSizeBadge = document.getElementById("bag-size-badge");
          if (heroNextStep) heroNextStep.textContent = state.next_step || "";
          if (heroTags) heroTags.innerHTML = state.hero_tags_html || "";
          if (summaryGrid) summaryGrid.innerHTML = state.summary_html || "";
          if (alertsReadiness) alertsReadiness.innerHTML = state.readiness_alert_rows || "";
          if (alertsExpiry) alertsExpiry.innerHTML = state.expiry_alert_rows || "";
          if (pairExpires) pairExpires.textContent = `Expires ${{state.pair_expires_label || ""}}`;
          if (pairingCard) pairingCard.innerHTML = state.pairing_card_html || "";
          if (inventoryLiveNote) inventoryLiveNote.textContent = state.inventory_notice || "";
          if (inventoryGroups) inventoryGroups.innerHTML = state.inventory_groups_html || "";
          if (pairedPhones) pairedPhones.innerHTML = state.phone_rows_html || "";
          if (heroBagName) heroBagName.textContent = state.bag_name || "";
          if (bagNameLabel) bagNameLabel.textContent = state.bag_name || "";
          if (bagSizeBadge) bagSizeBadge.textContent = state.bag_size_label || "";
          if (keyboardTarget && !document.contains(keyboardTarget)) {{
            dismissTouchKeyboard();
          }}
        }} catch (_error) {{
        }}
      }}

      function closeScannerWithError(message) {{
        setBanner("error", message || "USB camera scan failed.");
        closeScanModal();
      }}

      function startScanStatusLoop(intervalMs) {{
        stopScanStatusLoop();
        const effectiveInterval = Math.max(Math.min(intervalMs || scanIntervalMs || autoScanFallbackIntervalMs, 900), 250);
        scanStatusTimer = window.setInterval(() => {{
          void pollScanSessionStatus();
        }}, effectiveInterval);
      }}

      async function stopScanSession() {{
        const activeSessionId = scanSessionId;
        if (!activeSessionId) {{
          return;
        }}
        scanSessionId = "";
        stopScanStatusLoop();
        try {{
          await fetch("/camera/usb/session/stop", {{
            method: "POST",
            headers: {{ "Accept": "application/json" }},
            cache: "no-store",
            keepalive: true,
          }});
        }} catch (_error) {{
        }}
      }}

      async function loadSessionFrame(frameId) {{
        if (!scanPreviewImage || !scanIsOpen() || !scanSessionId || previewLoading || !frameId) {{
          return;
        }}
        previewLoading = true;
        scanPreviewImage.onload = () => {{
          previewLoading = false;
          previewFailureCount = 0;
          setPreviewVisible(true);
          if (!scanBusy) {{
            setScanUiStatus(
              "Searching for QR code",
              "Hold the label inside the frame. Detection runs automatically.",
              "active",
            );
          }}
        }};
        scanPreviewImage.onerror = () => {{
          previewLoading = false;
          setPreviewVisible(false);
          setPreviewPlaceholderMessage("USB camera preview unavailable.");
          setScanUiStatus(
            "Preview unavailable",
            "Check the USB camera connection and reopen the scanner.",
            "danger",
          );
          previewFailureCount += 1;
          if (previewFailureCount >= 3) {{
            closeScannerWithError("USB camera preview failed. Check the USB camera connection.");
          }}
        }};
        const frameUrl = `${{scanFrameUrl}}?session_id=${{encodeURIComponent(scanSessionId)}}&frame_id=${{frameId}}`;
        scanPreviewImage.src = withCacheBust(frameUrl);
      }}

      async function pollScanSessionStatus() {{
        if (!scanIsOpen() || !scanSessionId) {{
          return;
        }}
        try {{
          const response = await fetch(`/camera/usb/session/status?session_id=${{encodeURIComponent(scanSessionId)}}`, {{
            headers: {{ "Accept": "application/json" }},
            cache: "no-store",
          }});
          const payload = await response.json().catch(() => ({{ ok: false, code: "CAMERA_PREVIEW_FAILED", message: "USB camera preview failed." }}));
          if (!response.ok || payload.ok === false) {{
            closeScannerWithError(payload.message || "USB camera preview failed.");
            return;
          }}
          if (payload.session_id && payload.session_id !== scanSessionId) {{
            return;
          }}
          scanIntervalMs = payload.interval_ms || scanIntervalMs || autoScanFallbackIntervalMs;
          scanFrameUrl = payload.frame_url || "/camera/usb/session/frame.jpg";
          if (payload.frame_id && payload.frame_id !== latestSessionFrameId) {{
            latestSessionFrameId = payload.frame_id;
            await loadSessionFrame(latestSessionFrameId);
          }}
          if (payload.decoded_content && !scanBusy) {{
            setScanUiStatus(
              "QR detected",
              "Processing the decoded label and preparing the inventory update.",
              "processing",
            );
            await submitDecodedUsbScan(payload.decoded_content, true);
          }}
        }} catch (_error) {{
          closeScannerWithError("USB camera preview failed.");
        }}
      }}

      async function preparePreview() {{
        setPreviewVisible(false);
        setPreviewPlaceholderMessage("Opening USB camera...");
        setScanUiStatus(
          "Starting camera",
          "Opening the USB preview for automatic QR detection.",
          "processing",
        );
        previewFailureCount = 0;
        try {{
          const response = await fetch("/camera/usb/session/start", {{
            method: "POST",
            headers: {{ "Accept": "application/json" }},
            body: new URLSearchParams(new FormData(scanCaptureForm)),
            cache: "no-store",
          }});
          const payload = await response.json().catch(() => ({{ ok: false, code: "CAMERA_PREVIEW_FAILED", message: "USB camera preview failed." }}));
          if (!response.ok || payload.ok === false) {{
            closeScannerWithError(payload.message || "USB camera preview failed.");
            return;
          }}
          scanSessionId = payload.session_id || "";
          scanFrameUrl = payload.frame_url || "/camera/usb/session/frame.jpg";
          scanIntervalMs = payload.interval_ms || autoScanFallbackIntervalMs;
          latestSessionFrameId = 0;
          startScanStatusLoop(scanIntervalMs);
          await pollScanSessionStatus();
        }} catch (_error) {{
          closeScannerWithError("USB camera preview failed.");
        }}
      }}

      function openScanModal() {{
        if (!scanModal) return;
        dismissTouchKeyboard();
        resetScannerState();
        scanModal.classList.remove("hidden");
        scanModal.setAttribute("aria-hidden", "false");
        syncModalShellState();
        void preparePreview();
      }}

      function closeScanModal() {{
        void stopScanSession();
        resetScannerState();
        if (scanModal) {{
          scanModal.classList.add("hidden");
          scanModal.setAttribute("aria-hidden", "true");
        }}
        syncModalShellState();
        if (scanPreviewImage) {{
          scanPreviewImage.removeAttribute("src");
        }}
        previewMode = "snapshot";
        previewUrl = "/camera/usb/preview.jpg";
        snapshotPreviewUrl = "/camera/usb/preview.jpg";
        previewFailureCount = 0;
        setPreviewVisible(false);
        setPreviewPlaceholderMessage("");
      }}

      function handleScanFailure(payload, automatic = false) {{
        const code = payload.code || "SCAN_FAILED";
        const message = payload.message || "USB camera scan failed.";
        if (automatic && code === "NO_QR_DETECTED") {{
          scanCooldownUntil = 0;
          return;
        }}
        closeScannerWithError(message);
      }}

      async function handleScanSuccess(payload) {{
        setScanUiStatus(
          "Inventory updated",
          "The scanned item was accepted and added to this GO BAG.",
          "success",
        );
        setBanner("notice", payload.message || "QR code detected and item added.");
        await refreshDashboard();
        closeScanModal();
      }}

      async function submitUsbScan(event, automatic = false) {{
        if (event) {{
          event.preventDefault();
        }}
        if (!scanCaptureForm || scanBusy || !scanIsOpen()) {{
          return;
        }}
        scanBusy = true;
        scanCooldownUntil = Date.now() + 1000;
        setScanUiStatus(
          "Processing scan",
          "Decoding the current camera frame and validating the QR payload.",
          "processing",
        );
        stopScanStatusLoop();
        stopPreviewLoop();
        stopAutoScanLoop();
        stopLiveDetectLoop();
        try {{
          const response = await fetch("/camera/usb/scan", {{
            method: "POST",
            headers: {{ "Accept": "application/json" }},
            body: new URLSearchParams(new FormData(scanCaptureForm)),
            cache: "no-store",
          }});
          const payload = await response.json().catch(() => ({{ ok: false, code: "SCAN_FAILED", message: "USB camera scan failed unexpectedly." }}));
          if (!response.ok || payload.ok === false) {{
            handleScanFailure(payload, automatic);
            return;
          }}
          await handleScanSuccess(payload);
          return;
        }} catch (_error) {{
          handleScanFailure({{ code: "SCAN_FAILED", message: "USB camera scan failed unexpectedly." }}, automatic);
        }} finally {{
          scanBusy = false;
          if (scanIsOpen() && scanSessionId) {{
            startScanStatusLoop(scanIntervalMs);
            await pollScanSessionStatus();
          }}
        }}
      }}

      async function submitDecodedUsbScan(rawContent, automatic = false) {{
        if (!scanCaptureForm || scanBusy || !scanIsOpen()) {{
          return;
        }}
        const content = (rawContent || "").trim();
        if (!content) {{
          return;
        }}
        scanBusy = true;
        scanCooldownUntil = Date.now() + 1400;
        setScanUiStatus(
          "Processing scan",
          "Applying the decoded QR content to the inventory manager.",
          "processing",
        );
        stopScanStatusLoop();
        stopPreviewLoop();
        stopAutoScanLoop();
        stopLiveDetectLoop();
        try {{
          const requestBody = new URLSearchParams(new FormData(scanCaptureForm));
          requestBody.set("content", content);
          const response = await fetch("/camera/usb/scan/decoded", {{
            method: "POST",
            headers: {{ "Accept": "application/json" }},
            body: requestBody,
            cache: "no-store",
          }});
          const payload = await response.json().catch(() => ({{ ok: false, code: "SCAN_FAILED", message: "USB camera scan failed unexpectedly." }}));
          if (!response.ok || payload.ok === false) {{
            handleScanFailure(payload, automatic);
            return;
          }}
          await handleScanSuccess(payload);
        }} catch (_error) {{
          handleScanFailure({{ code: "SCAN_FAILED", message: "USB camera scan failed unexpectedly." }}, automatic);
        }} finally {{
          scanBusy = false;
          if (scanIsOpen() && scanSessionId) {{
            startScanStatusLoop(scanIntervalMs);
            await pollScanSessionStatus();
          }}
        }}
      }}

      if (themeToggle) {{
        themeToggle.addEventListener("click", () => {{
          applyTheme(currentTheme() === "dark" ? "light" : "dark");
        }});
      }}
      if (zoomOutButton) {{
        zoomOutButton.addEventListener("click", () => {{
          applyUiScale("fit");
        }});
      }}
      if (zoomInButton) {{
        zoomInButton.addEventListener("click", () => {{
          applyUiScale("standard");
        }});
      }}
      if (powerButton) {{
        powerButton.addEventListener("click", openShutdownModal);
      }}
      if (shutdownCancelButton) {{
        shutdownCancelButton.addEventListener("click", closeShutdownModal);
      }}
      if (shutdownConfirmButton) {{
        shutdownConfirmButton.addEventListener("click", () => {{
          void requestShutdown();
        }});
      }}
      if (shutdownModal) {{
        shutdownModal.addEventListener("click", (event) => {{
          if (event.target === shutdownModal) {{
            closeShutdownModal();
          }}
        }});
      }}
      if (scanCloseButton) {{
        scanCloseButton.addEventListener("click", closeScanModal);
      }}
      scanOpenButtons.forEach((button) => {{
        button.addEventListener("click", openScanModal);
      }});
      if (scanCaptureForm) {{
        scanCaptureForm.addEventListener("submit", submitUsbScan);
      }}

      document.addEventListener("submit", (event) => {{
        const form = event.target;
        if (!(form instanceof HTMLFormElement) || form === scanCaptureForm) {{
          return;
        }}
        saveScrollState();
      }});

      document.addEventListener("focusin", (event) => {{
        const target = event.target;
        if (isKeyboardEligible(target)) {{
          showTouchKeyboard(target);
          return;
        }}
        if (!touchKeyboard || touchKeyboard.contains(target)) {{
          return;
        }}
        dismissTouchKeyboard();
      }});

      document.addEventListener("pointerdown", (event) => {{
        const target = event.target;
        if (touchKeyboard && touchKeyboard.contains(target)) {{
          return;
        }}
        if (isKeyboardEligible(target)) {{
          return;
        }}
        dismissTouchKeyboard();
      }});

      if (touchKeyboard) {{
        touchKeyboard.addEventListener("pointerdown", (event) => {{
          if (event.target instanceof HTMLElement && event.target.closest("button")) {{
            event.preventDefault();
          }}
        }});
        touchKeyboard.addEventListener("click", (event) => {{
          const button = event.target instanceof HTMLElement ? event.target.closest("button") : null;
          handleTouchKeyboardButton(button);
        }});
      }}

      initializeTheme();
      initializeUiScale();
      restoreScrollState();
      window.addEventListener("resize", syncTouchKeyboardOffset);

      window.addEventListener("keydown", (event) => {{
        if (event.key !== "Escape") {{
          return;
        }}
        if (powerDialogIsOpen() && !shutdownPending) {{
          closeShutdownModal();
          return;
        }}
        if (scanIsOpen()) {{
          closeScanModal();
        }}
      }});

      window.setInterval(refreshDashboard, refreshIntervalMs);
      if (document.body.dataset.openScan === "1") {{
        openScanModal();
      }}
    }})();
  </script>
</body>
</html>
"""
    return HTMLResponse(html)
