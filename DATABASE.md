# Database

## Overview

GO BAG uses two separate persistent stores:

- Android local Room database
- Raspberry Pi SQLite database

They serve different roles and should not be confused.

## Android local Room database

### Purpose

- local UI source of truth
- stores bag and item state used by the Android screens
- stores downloaded template recommendations
- stores sync conflict records

### Location

The Android Room database is stored inside the app's internal storage on the device. The code-level database name is:

- `gobag.db`

### Current Room schema notes

- version: `2`
- migration: `1 -> 2` defined in `LocalDataSourceFactory.kt`
- destructive fallback: removed

### Current Android tables

- bags
- items
- recommended_templates
- conflicts

## Raspberry Pi SQLite database

### Purpose

- persistent backend source for the Pi
- sync reconciliation store
- dashboard data source
- pairing and token state store

### Paths

Development default:

- `${GOBAG_DATA_DIR}/gobag.db`
- with default `GOBAG_DATA_DIR=/var/lib/gobag`

Installed Raspberry Pi default:

- `/opt/gobag/data/gobag.db`

### Important Pi tables and entities

- `bags`
  bag records and readiness-related fields
- `items`
  inventory rows and sync metadata
- `categories`
  category list for Pi-side CRUD and normalization
- `device_state`
  device status shown by `/device/status` and dashboard
- `settings`
  backend settings record
- `templates`
  recommended item templates
- `tokens`
  paired phone auth tokens
- `pair_codes`
  active six-digit pairing codes
- `meta`
  Pi device ID and last sync metadata

## Relationship between Android Room and Pi SQLite

This relationship is central to the system:

- Android Room:
  local app data for UI rendering and offline operation
- Pi SQLite:
  persistent backend data on the Raspberry Pi
- sync:
  the exchange layer between them

The Android app does not directly mount or query the Pi database. It syncs over HTTP.

## Room migration strategy

Room currently uses explicit migrations instead of destructive recreation.

Documented current migration:

- `1 -> 2`
  creates `recommended_templates`
  creates `conflicts`

This reduces the risk of local data loss during app updates.

## Pi database initialization and migration behavior

The Pi backend does not use Alembic or a separate migration framework.

Instead it uses:

- `CREATE TABLE IF NOT EXISTS`
- `ensure_column(...)` for additive column compatibility
- startup initialization inside `main.py`
- installer DB bootstrap through `scripts/init_db.sh`

This is simple and Raspberry Pi friendly, but it means schema evolution must stay careful and additive.

## Persistence guarantees

### Android

- local inventory survives normal app restarts and device restarts unless the app data is cleared or a future migration is mishandled

### Raspberry Pi

- backend data survives service restarts and Pi reboots because SQLite is stored in a stable data directory

## Data loss risks

- Android could still lose data if a future Room schema version is added without a migration.
- Pi data could be lost if the configured data directory is changed or removed.
- Templates are reseeded by the backend and should be treated as seed data, not long-term user-authored content.

## Backup and export notes

The installer creates a backup directory:

- `/opt/gobag/backups`

No full backup automation is implemented yet, but the Pi SQLite file can be backed up directly.

Example:

```bash
cp /opt/gobag/data/gobag.db /opt/gobag/backups/gobag-$(date +%F).db
```

## Known constraints

- There is no separate server-side migration framework.
- Conflict rows currently exist only on Android.
- Android and Pi may temporarily differ between sync runs. That is expected in the sync-first design.
