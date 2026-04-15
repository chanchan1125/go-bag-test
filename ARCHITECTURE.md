# Architecture

## System overview

GO BAG is built as a local-first sync system with two primary runtime components:

- Android app for editing, checklist, pairing, sync, and local offline use
- Raspberry Pi backend for pairing, sync, dashboard, and persistent storage

## Component diagram

```text
+---------------------------+
| Android App               |
|---------------------------|
| Compose Screens           |
| ViewModels                |
| Repository layer          |
| Room local database       |
| DataStore device state    |
+-------------+-------------+
              |
              | local HTTP
              | or secure remote HTTP(S)
              | /health /device/status
              | /pair /templates /sync
              v
+-------------+-------------+
| Raspberry Pi Backend      |
|---------------------------|
| FastAPI app               |
| Pairing QR dashboard      |
| Sync reconciliation       |
| CRUD/status endpoints     |
| SQLite persistence        |
+-------------+-------------+
              |
              v
+---------------------------+
| SQLite on Raspberry Pi    |
| bags, items, categories   |
| device_state, settings    |
| templates, tokens, meta   |
+---------------------------+
```

## Android app layers

### UI layer

- Compose screens in `app/` and `feature/`
- navigation hosted by `MainActivity.kt`
- screens include dashboard, inventory, checklist, sync, pairing, and settings

### ViewModel layer

- orchestrates UI state and user actions
- talks to repositories
- converts repository results into loading, success, offline, and error states

### Repository layer

- `GoBagItemRepository`
  local bag and item access through Room
- `GoBagPairingRepository`
  QR parsing, connection test, pairing, initial sync trigger
- `GoBagSyncRepository`
  manual sync, conflict handling, device status refresh

### Local persistence layer

- Room stores bags, items, templates, and conflict records
- DataStore stores phone identity, pairing state, selected bag, sync timestamps, and connection state

## Raspberry Pi backend layers

### API and dashboard layer

- FastAPI app in `pi-server/main.py`
- serves REST endpoints and HTML dashboard
- dashboard shows pairing QR, readiness, bag summary, and alerts

### Sync layer

- receives `POST /sync`
- compares Android changes against SQLite state
- upserts newer rows
- returns server-side changes back to Android
- returns conflict records when both sides changed incompatible fields

### Persistence layer

- SQLite file on the Pi
- initialized automatically on startup
- reused across service restarts and Pi reboots

## Database layer distinction

The system deliberately uses two local stores:

- Android Room:
  local UI source of truth on the phone
- Raspberry Pi SQLite:
  persistent shared backend state on the Pi

The sync contract keeps them aligned over time. They are not direct mirrors at every instant.

## Sync and pairing layer

### Why sync-first was chosen

Sync-first matches the existing app architecture with the least risky rewrite.

- Android screens were already built around Room observation.
- Local-first behavior is important when the Pi is offline.
- Sync gives clearer conflict handling than partially live-editing the Pi from every screen.
- Pi CRUD endpoints are still useful for Pi/web/admin use without forcing Android into a different data path.

### Pairing flow

```text
Pi dashboard renders QR payload
  -> Android scans QR
  -> Android stores Pi identity plus local and optional remote endpoints
  -> Android calls POST /pair
  -> Android stores auth token, base URL, Pi device ID
  -> Android downloads templates
  -> Android immediately runs first sync
  -> Room is populated with Pi-backed inventory data
```

### Regular sync flow

```text
Android local edit
  -> app tries the local endpoint first
  -> if local fails, app falls back to the remote endpoint
  -> Room updated immediately
  -> Sync request built from changed rows since last_sync_at
  -> Pi compares incoming rows with SQLite rows
  -> Pi returns server changes, conflicts, alerts
  -> Android applies results into Room and updates DataStore state
```

## Data flow examples

### Add item on Android

```text
InventoryScreen
  -> InventoryViewModel
  -> GoBagItemRepository inserts item into Room
  -> GoBagSyncRepository optionally runs sync
  -> Pi sync handler upserts item into SQLite
  -> later sync/device state updates are reflected in Android UI
```

### Pair and first inventory download

```text
PairingScreen
  -> PairingViewModel
  -> GoBagPairingRepository parses QR
  -> test /device/status
  -> POST /pair
  -> save auth/base_url/pi_device_id
  -> GET /templates
  -> POST /sync with last_sync_at = 0
  -> apply server bags/items into Room
  -> inventory screen shows Pi-backed data immediately
```

## Offline-first behavior

- Android edits update Room first.
- If the Pi is offline, Android keeps local data and shows error or offline state.
- Sync can be retried later.
- Pairing state, selected bag, and sync timestamps remain in DataStore.
- The app should not require constant Pi connectivity to browse or edit local data.

## Distinction between Room-first Android and Pi CRUD endpoints

This distinction is important:

- Android primary app path:
  Room plus pairing/sync
- Pi CRUD endpoints:
  direct bag and item endpoints for Pi/web/admin use and operational inspection

Those CRUD endpoints exist, but Android does not currently use them as its main read path.

## Raspberry Pi startup flow

```text
install.sh
  -> copies app into /opt/gobag/app
  -> writes /opt/gobag/config/gobag.env
  -> creates virtualenv and installs requirements
  -> initializes /opt/gobag/data/gobag.db
  -> installs gobag-backend.service
  -> installs desktop launcher

systemd boot
  -> gobag-backend.service
  -> scripts/run_backend.sh
  -> uvicorn main:app --host 0.0.0.0 --port 8080
```

## Deployment flow

```text
Fresh Pi
  -> run pi-server/install.sh
  -> backend starts as systemd service
  -> user opens dashboard or launcher
  -> Android pairs by QR
  -> first sync downloads data into Room
```

## Current verification status

- Code structure and configuration are aligned with this architecture.
- Python syntax verification has passed.
- Full live-device verification is still required for a final product sign-off.
