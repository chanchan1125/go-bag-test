import base64
import calendar
import glob
import io
import json
import os
import platform
import secrets
import shutil
import socket
import sqlite3
import subprocess
import tempfile
import time
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from html import escape
from typing import Dict, List, Literal, Optional, Union
from urllib.parse import quote

import qrcode
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse, Response
from pydantic import BaseModel, Field

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
USB_SCAN_WIDTH = int(os.getenv("GOBAG_USB_SCAN_WIDTH", "1280"))
USB_SCAN_HEIGHT = int(os.getenv("GOBAG_USB_SCAN_HEIGHT", "720"))
USB_SCAN_TIMEOUT_S = float(os.getenv("GOBAG_USB_SCAN_TIMEOUT_S", "12"))
QR_DECODE_CMD = os.getenv("GOBAG_QR_DECODE_CMD", "zbarimg")
UI_REFRESH_INTERVAL_MS = max(int(os.getenv("GOBAG_UI_REFRESH_INTERVAL_MS", "5000")), 2000)
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


def qr_decode_available() -> bool:
    return shutil.which(QR_DECODE_CMD) is not None


def decode_process_output(raw: Optional[bytes]) -> str:
    if not raw:
        return ""
    return raw.decode("utf-8", errors="replace").strip()


def available_usb_camera_devices() -> List[str]:
    return sorted(path for path in glob.glob("/dev/video*") if os.path.exists(path))


def scan_qr_from_usb_camera() -> str:
    if not usb_scan_available():
        raise HTTPException(status_code=503, detail=f"USB scan command not found: {USB_SCAN_CMD}")
    if not qr_decode_available():
        raise HTTPException(status_code=503, detail=f"QR decode command not found: {QR_DECODE_CMD}")

    scan_device = USB_SCAN_DEVICE
    if scan_device and not os.path.exists(scan_device):
        raise HTTPException(status_code=503, detail=f"Configured USB camera device was not found: {scan_device}")
    if not scan_device:
        available_devices = available_usb_camera_devices()
        if available_devices:
            scan_device = available_devices[0]

    with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as tmp:
        out_path = tmp.name

    capture_cmd = [
        USB_SCAN_CMD,
        "-r",
        f"{USB_SCAN_WIDTH}x{USB_SCAN_HEIGHT}",
        "--no-banner",
    ]
    if scan_device:
        capture_cmd.extend(["-d", scan_device])
    capture_cmd.append(out_path)

    try:
        capture = subprocess.run(
            capture_cmd,
            check=False,
            capture_output=True,
            timeout=USB_SCAN_TIMEOUT_S,
        )
        if capture.returncode != 0:
            capture_error = decode_process_output(capture.stderr) or decode_process_output(capture.stdout)
            raise HTTPException(
                status_code=503,
                detail=f"USB camera capture failed ({capture.returncode}): {capture_error[:200] or 'fswebcam could not read from the camera'}",
            )
        if not os.path.exists(out_path) or os.path.getsize(out_path) == 0:
            raise HTTPException(status_code=503, detail="USB camera did not produce an image. Check camera access and file permissions.")

        decode_attempts = [
            [QR_DECODE_CMD, "--quiet", "--raw", out_path],
            [QR_DECODE_CMD, "--raw", out_path],
        ]
        decode = None
        decode_error = ""
        for command in decode_attempts:
            decode = subprocess.run(
                command,
                check=False,
                capture_output=True,
                timeout=USB_SCAN_TIMEOUT_S,
            )
            decode_output = decode_process_output(decode.stdout)
            decode_error = decode_process_output(decode.stderr) or decode_output
            if decode.returncode == 0 and decode_output:
                break
            if "--quiet" in command and "option" in decode_error.lower() and "quiet" in decode_error.lower():
                continue
        if decode is None or decode.returncode != 0:
            raise HTTPException(status_code=400, detail=f"QR scan failed: {decode_error[:200] or 'No QR code detected'}")
        content = decode_process_output(decode.stdout).splitlines()[0].strip() if decode_process_output(decode.stdout) else ""
        if not content:
            raise HTTPException(status_code=400, detail="QR scan returned no readable content")
        return content
    except subprocess.TimeoutExpired:
        raise HTTPException(status_code=504, detail="USB camera scan timed out")
    except OSError as exc:
        raise HTTPException(status_code=503, detail=f"USB camera scan could not start: {exc.strerror or str(exc)}")
    finally:
        try:
            os.remove(out_path)
        except OSError:
            pass


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


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "camera_enabled": CAMERA_ENABLED,
        "camera_cmd_available": camera_command_available(),
        "usb_scan_cmd_available": usb_scan_available(),
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
        "qr_decode_cmd": QR_DECODE_CMD,
        "qr_decode_cmd_available": qr_decode_available(),
    }


@app.get("/camera/status")
def camera_status() -> dict:
    return {
        "camera_enabled": CAMERA_ENABLED,
        "camera_cmd": CAMERA_CMD,
        "camera_cmd_available": camera_command_available(),
        "usb_scan_cmd": USB_SCAN_CMD,
        "usb_scan_cmd_available": usb_scan_available(),
        "qr_decode_cmd": QR_DECODE_CMD,
        "qr_decode_cmd_available": qr_decode_available(),
        "resolution": {"width": CAMERA_WIDTH, "height": CAMERA_HEIGHT},
    }


@app.get("/camera/capture.jpg")
def camera_capture() -> Response:
    image = capture_camera_jpeg()
    return Response(content=image, media_type="image/jpeg")


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
    return "".join(
        [
            f"""
            <div class="stat-card">
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
            <div class="list-row">
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


def render_inventory_groups_html(inventory_groups: List[InventoryGroupView]) -> str:
    return "".join(
        [
            f"""
            <div class="inventory-group">
              <div class="panel-head" style="margin-bottom: 8px;">
                <div>
                  <div class="panel-title">{escape(group.name)}</div>
                  <div class="panel-note">{escape(group.category)} | Total {group.total_quantity:g} {escape(group.unit)}</div>
                </div>
              </div>
              {''.join(
                  [
                      f'''
                      <div class="batch-card">
                        <div class="row-title">Batch: {batch.item.quantity:g} {escape(batch.item.unit)}</div>
                        <div class="row-subtitle">{escape(batch.expiry_label)} | {"Packed" if batch.item.packed_status else "Needs pack"}</div>
                        {f'<div class="row-subtitle">{escape(batch.item.notes)}</div>' if batch.item.notes else ''}
                        <div class="button-row compact">
                          <form method="get" action="/">
                            <input type="hidden" name="edit_item_id" value="{escape(batch.item.id)}">
                            <button type="submit" class="secondary">Edit batch</button>
                          </form>
                          <form method="post" action="/ui/items/{escape(batch.item.id)}/delete">
                            <input type="hidden" name="bag_id" value="{escape(group.bag_id)}">
                            <button type="submit" class="secondary danger">Delete batch</button>
                          </form>
                        </div>
                      </div>
                      '''
                      for batch in group.batches
                  ]
              )}
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
        f"USB scan is ready with {USB_SCAN_CMD}."
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


def ui_redirect_url(bag_id: str = "", notice: str = "", error: str = "", edit_item_id: str = "") -> str:
    params: List[str] = []
    if bag_id:
        params.append(f"bag={quote(bag_id)}")
    if edit_item_id:
        params.append(f"edit_item_id={quote(edit_item_id)}")
    if notice:
        params.append(f"notice={quote(notice)}")
    if error:
        params.append(f"error={quote(error)}")
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
    form = await request.form()
    bag_type = str(form.get("bag_type") or "").strip()
    try:
        size_liters = size_liters_for_bag_type(bag_type)
        with db_conn() as conn:
            bag_row = ensure_single_bag(conn)
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


@app.post("/ui/items/save")
async def ui_save_item(request: Request) -> RedirectResponse:
    form = await request.form()
    bag_id = str(form.get("bag_id") or "").strip()
    item_id = str(form.get("item_id") or "").strip() or None
    try:
        quantity = float(str(form.get("quantity") or "1").strip())
    except ValueError:
        return RedirectResponse(ui_redirect_url(bag_id=bag_id, error="Quantity must be a valid number", edit_item_id=item_id or ""), status_code=303)

    payload = ItemWriteRequest(
        category_id=str(form.get("category_id") or "").strip(),
        name=str(form.get("name") or "").strip(),
        quantity=quantity,
        unit=str(form.get("unit") or "").strip(),
        packed_status=str(form.get("packed_status") or "").lower() in {"on", "true", "1"},
        essential=False,
        expiry_date=str(form.get("expiry_date") or "").strip() or None,
        minimum_quantity=0,
        condition_status="good",
        notes=str(form.get("notes") or "").strip(),
    )
    try:
        with db_conn() as conn:
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
        return RedirectResponse(ui_redirect_url(bag_id=bag_id, error=str(exc.detail), edit_item_id=item_id or ""), status_code=303)


@app.post("/ui/items/scan")
async def ui_scan_item(request: Request) -> RedirectResponse:
    form = await request.form()
    bag_id = str(form.get("bag_id") or "").strip()
    try:
        parsed = parse_item_qr_content(scan_qr_from_usb_camera())
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
        with db_conn() as conn:
            merge_or_write_item_record(conn, bag_id, payload)
            conn.commit()
        return RedirectResponse(
            ui_redirect_url(bag_id=bag_id, notice=f"Scanned {parsed.name} into inventory."),
            status_code=303,
        )
    except HTTPException as exc:
        return RedirectResponse(ui_redirect_url(bag_id=bag_id, error=str(exc.detail)), status_code=303)
    except Exception as exc:
        detail = str(exc).strip() or exc.__class__.__name__
        return RedirectResponse(
            ui_redirect_url(bag_id=bag_id, error=f"USB camera scan failed unexpectedly: {detail[:200]}"),
            status_code=303,
        )


@app.post("/ui/items/{item_id}/delete")
async def ui_delete_item(item_id: str, request: Request) -> RedirectResponse:
    form = await request.form()
    bag_id = str(form.get("bag_id") or "").strip()
    try:
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
        return RedirectResponse(ui_redirect_url(bag_id=bag_id, notice="Inventory batch deleted."), status_code=303)
    except HTTPException as exc:
        return RedirectResponse(ui_redirect_url(bag_id=bag_id, error=str(exc.detail)), status_code=303)


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
    html = f"""
<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta http-equiv="refresh" content="300">
  <title>Go-Bag Pi Command Center</title>
  <style>
    :root {{
      --bg: #f4efe4;
      --panel: #fffaf0;
      --ink: #1f2a23;
      --muted: #5f675d;
      --line: #d8ccb4;
      --accent: #365f4c;
      --accent-soft: #dfeadf;
      --warn: #8b5e1a;
      --warn-soft: #f4e5bd;
      --danger: #8c2f39;
      --danger-soft: #f6d8d9;
    }}
    * {{ box-sizing: border-box; }}
    body {{
      margin: 0;
      font-family: "Segoe UI", Tahoma, sans-serif;
      background:
        radial-gradient(circle at top right, rgba(54, 95, 76, 0.18), transparent 28%),
        linear-gradient(180deg, #efe4cb 0%, var(--bg) 45%, #ece4d2 100%);
      color: var(--ink);
    }}
    main {{
      max-width: 760px;
      margin: 0 auto;
      padding: 20px 16px 40px;
    }}
    h1, h2, h3, p {{ margin: 0; }}
    .hero {{
      background: linear-gradient(135deg, rgba(54, 95, 76, 0.96), rgba(28, 44, 37, 0.96));
      color: #f8f4ea;
      border-radius: 24px;
      padding: 24px 20px;
      box-shadow: 0 16px 40px rgba(25, 42, 33, 0.16);
      margin-bottom: 18px;
    }}
    .hero-grid {{
      display: grid;
      grid-template-columns: 1fr;
      gap: 18px;
      align-items: center;
    }}
    .hero-kicker {{
      font-size: 0.84rem;
      letter-spacing: 0.14em;
      text-transform: uppercase;
      opacity: 0.82;
      margin-bottom: 10px;
    }}
    .hero h1 {{
      font-size: clamp(2rem, 4vw, 2.5rem);
      margin-bottom: 12px;
    }}
    .hero p {{
      color: rgba(248, 244, 234, 0.82);
      line-height: 1.5;
      margin-bottom: 16px;
    }}
    .hero-tags, .tag-row {{
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
    }}
    .tag, .pill {{
      display: inline-flex;
      align-items: center;
      border-radius: 999px;
      padding: 7px 12px;
      font-size: 0.84rem;
      font-weight: 600;
      background: #f2ede3;
      border: 1px solid var(--line);
      color: inherit;
      text-decoration: none;
    }}
    .tag.ok, .pill.ok {{
      background: var(--accent-soft);
      color: var(--accent);
    }}
    .tag.warn, .pill.warn {{
      background: var(--warn-soft);
      color: var(--warn);
    }}
    .tag.danger {{
      background: var(--danger-soft);
      color: var(--danger);
    }}
    .panel, .stat-card {{
      background: var(--panel);
      border: 1px solid var(--line);
      border-radius: 22px;
      box-shadow: 0 8px 24px rgba(65, 52, 33, 0.08);
    }}
    .summary-grid {{
      display: grid;
      gap: 18px;
      grid-template-columns: 1fr;
      margin-bottom: 20px;
    }}
    .stat-card {{
      padding: 18px;
      min-height: 112px;
    }}
    .stat-label {{
      text-transform: uppercase;
      letter-spacing: 0.12em;
      font-size: 0.76rem;
      color: var(--muted);
      margin-bottom: 8px;
    }}
    .stat-value {{
      font-size: 1.35rem;
      font-weight: 800;
      margin-bottom: 6px;
    }}
    .stat-note {{
      color: var(--muted);
      line-height: 1.45;
    }}
    .panel {{
      padding: 20px 18px;
      margin-bottom: 18px;
    }}
    .panel-head {{
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      margin-bottom: 12px;
    }}
    .panel-title {{
      font-size: 1.15rem;
      font-weight: 800;
    }}
    .panel-note {{
      color: var(--muted);
      margin-top: 4px;
      line-height: 1.45;
    }}
    .list-row, .check-row {{
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 12px;
      padding: 14px 0;
      border-top: 1px solid rgba(216, 204, 180, 0.8);
    }}
    .list-row:first-child, .check-row:first-child {{
      border-top: none;
      padding-top: 0;
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
      padding: 10px 12px;
      border-radius: 14px;
      background: #f2ede3;
      border: 1px solid var(--line);
      color: var(--muted);
      margin-top: 10px;
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
      border-radius: 18px;
      background: #f2ede3;
      padding: 16px;
    }}
    .pair-code-label {{
      text-transform: uppercase;
      letter-spacing: 0.12em;
      font-size: 0.76rem;
      color: var(--muted);
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
      flex: 1;
    }}
    input, select, textarea {{
      width: 100%;
      border-radius: 14px;
      border: 1px solid var(--line);
      padding: 12px 14px;
      font: inherit;
      color: var(--ink);
      background: #fffdf7;
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
    .banner {{
      border-radius: 18px;
      padding: 14px 16px;
      margin-bottom: 16px;
      border: 1px solid var(--line);
    }}
    .banner.notice {{
      background: var(--accent-soft);
      color: var(--accent);
    }}
    .banner.error {{
      background: var(--danger-soft);
      color: var(--danger);
    }}
    .inventory-group {{
      border: 1px solid var(--line);
      border-radius: 18px;
      background: #fffdf7;
      padding: 14px;
      margin-top: 12px;
    }}
    .batch-card {{
      border: 1px solid rgba(216, 204, 180, 0.8);
      border-radius: 14px;
      padding: 12px;
      background: #f9f2e4;
      margin-top: 10px;
    }}
    .pair-card {{
      border: 1px solid var(--line);
      border-radius: 18px;
      background: #fffdf7;
      padding: 14px;
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
    button {{
      width: 100%;
      min-height: 50px;
      border: 1px solid rgba(54, 95, 76, 0.2);
      border-radius: 14px;
      background: var(--accent);
      color: #f8f4ea;
      font-weight: 700;
      font-size: 1rem;
      cursor: pointer;
      padding: 12px 14px;
    }}
    button.secondary {{
      background: #d9d3c4;
      color: var(--ink);
      border-color: rgba(31, 42, 35, 0.12);
    }}
    button.secondary.danger {{
      background: var(--danger-soft);
      color: var(--danger);
      border-color: rgba(140, 47, 57, 0.18);
    }}
    .empty-state {{
      color: var(--muted);
      padding: 8px 0 4px;
    }}
    @media (min-width: 720px) {{
      .qr-wrap {{
        grid-template-columns: 240px 1fr;
      }}
      .field-grid {{
        grid-template-columns: 1.1fr 0.7fr 0.9fr;
      }}
      .field-grid.settings-grid {{
        grid-template-columns: 1fr auto;
      }}
    }}
  </style>
</head>
<body data-state-version="{escape(view_model['state_version'])}">
  <main>
    <section class="hero">
      <div class="hero-grid">
        <div>
          <div class="hero-kicker">Go-Bag Pi Command Center</div>
          <h1>Home base for this GO BAG, phone, and sync.</h1>
          <p id="hero-next-step">{escape(view_model['next_step'])}</p>
          <div class="hero-tags" id="hero-tags">{view_model['hero_tags_html']}</div>
        </div>
        <div class="panel">
          <div class="panel-title">What to do next</div>
          <p class="panel-note">This Raspberry Pi manages one physical GO BAG. Pair a phone, update inventory, and keep the kiosk view current.</p>
          <div class="tag-row" style="margin-top: 12px;">
            {view_model['missing_html']}
          </div>
        </div>
      </div>
    </section>

    <section class="summary-grid" aria-label="Status" id="summary-grid">
      {view_model['summary_html']}
    </section>

    {f'<div class="banner notice">{escape(notice)}</div>' if notice else ''}
    {f'<div class="banner error">{escape(error)}</div>' if error else ''}

    <section class="panel">
      <div class="panel-head">
        <div>
          <div class="panel-title">Alerts</div>
          <div class="panel-note">Use this section to catch issues before you leave.</div>
        </div>
      </div>
      <div id="alerts-readiness">{view_model['readiness_alert_rows']}</div>
      <div id="alerts-expiry">{view_model['expiry_alert_rows']}</div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <div>
          <div class="panel-title">Bag Settings</div>
          <div class="panel-note">This Raspberry Pi keeps one local GO BAG. The only bag-specific setting here is the bag size.</div>
        </div>
        <div class="pill" id="bag-size-badge">{escape(bag_size_label(bag.size_liters))}</div>
      </div>
      <div class="list-row" style="padding-top: 0; border-top: none;">
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

    <section class="panel">
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

    <section class="panel">
      <div class="panel-head">
        <div>
          <div class="panel-title">Inventory Manager</div>
          <div class="panel-note">Manual add and QR scan both merge by item name, unit, and category. Different expiration dates stay as separate batches under one grouped item.</div>
        </div>
      </div>
      <div class="row-subtitle" id="inventory-live-note" style="margin-bottom: 12px;">{escape(view_model['inventory_notice'])}</div>
      <form method="post" action="/ui/items/save">
        <input type="hidden" name="bag_id" value="{escape(bag.bag_id)}">
        <input type="hidden" name="item_id" value="{escape(edit_item.id if edit_item else '')}">
        <div class="field-grid">
          <input type="text" name="name" placeholder="Item name" value="{escape(edit_item.name if edit_item else '')}" required>
          <input type="number" step="0.01" min="0.01" name="quantity" placeholder="Quantity" value="{edit_item.quantity if edit_item else '1'}" required>
          <input type="text" name="unit" placeholder="Unit" value="{escape(edit_item.unit if edit_item else 'pcs')}" required>
        </div>
        <div class="field-grid" style="margin-top: 12px;">
          <select name="category_id" required>
            {category_options}
          </select>
          <input type="date" name="expiry_date" value="{escape(edit_item.expiry_date if edit_item and edit_item.expiry_date else '')}">
          <label style="display:flex; align-items:center; gap:8px; padding: 12px 0;">
            <input type="checkbox" name="packed_status" {"checked" if edit_item and edit_item.packed_status else ""} style="width:auto;">
            Mark batch as packed
          </label>
        </div>
        <textarea name="notes" placeholder="Notes" style="margin-top: 12px;">{escape(edit_item.notes if edit_item else '')}</textarea>
        <div class="button-row">
          <button type="submit">{'Update inventory batch' if edit_item else 'Manual Add'}</button>
        </div>
      </form>
      <form method="post" action="/ui/items/scan" class="inline">
        <input type="hidden" name="bag_id" value="{escape(bag.bag_id)}">
        <button type="submit" class="secondary">Scan with USB camera</button>
      </form>
      <div id="inventory-groups">{view_model['inventory_groups_html']}</div>
    </section>

    <section class="panel">
      <div class="panel-head">
        <div>
          <div class="panel-title">Pairing access</div>
          <div class="panel-note">Devices currently allowed to sync with this Raspberry Pi.</div>
        </div>
      </div>
      <div id="paired-phones">{view_model['phone_rows_html']}</div>
      <form method="post" action="/ui/revoke_tokens{admin_query}">
        <button type="submit" class="secondary danger">Revoke all phone access</button>
      </form>
    </section>

    <section class="panel">
      <div class="panel-head">
        <div>
          <div class="panel-title">Checklist</div>
          <div class="panel-note">These are the core categories every go-bag should cover.</div>
        </div>
        <div class="pill">{view_model['checked_count']}/{readiness['checklist_total']}</div>
      </div>
      {checklist_rows}
    </section>
  </main>
  <script>
    (() => {{
      const refreshIntervalMs = {UI_REFRESH_INTERVAL_MS};
      let lastStateVersion = document.body.dataset.stateVersion || "";

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
          if (bagNameLabel) bagNameLabel.textContent = state.bag_name || "";
          if (bagSizeBadge) bagSizeBadge.textContent = state.bag_size_label || "";
        }} catch (_error) {{
        }}
      }}

      window.setInterval(refreshDashboard, refreshIntervalMs);
    }})();
  </script>
</body>
</html>
"""
    return HTMLResponse(html)
