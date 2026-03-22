# GO BAG Project Context

## Purpose

GO BAG is an offline-first emergency inventory and readiness system. It helps a user maintain one or more emergency bags on Android while a Raspberry Pi serves as the local hub for pairing, sync, readiness visibility, and persistent storage.

## Problem it solves

The project is designed for a situation where cloud access is optional or unavailable. A user should still be able to:

- manage bag inventory from a phone
- sync that data to a Raspberry Pi on a local network or hotspot
- keep data persistent after restarts and reboots
- understand bag readiness and expiry risk without depending on internet services

## Main components

- `android-app/`
  Android client, local Room database, DataStore device state, sync and pairing UI
- `pi-server/`
  FastAPI server, HTML dashboard, SQLite persistence, pairing, sync, installer, launcher, service assets
- root documentation files
  canonical engineering docs for architecture, deployment, API, testing, and troubleshooting

## Current architecture decision

The current system is sync-first.

- Android UI reads from local Room tables.
- Android syncs with the Raspberry Pi through `/pair`, `/sync`, and related status endpoints.
- Raspberry Pi CRUD endpoints exist, but Android does not use them as its main live UI data path.
- This is intentional and should not be casually redesigned unless the whole app contract is being changed.

## Android role

- primary user-facing editor
- local-first source for UI rendering through Room
- stores device and pairing state in DataStore
- performs QR pairing and sync
- remains usable when the Pi is unavailable

## Raspberry Pi role

- local backend and sync hub
- persistent shared storage in SQLite
- pairing QR generator and token issuer
- dashboard and operational status surface
- installable service on Raspberry Pi OS

## Database role

There are two important databases.

- Android local Room database:
  caches and serves the UI state on the phone
- Raspberry Pi SQLite database:
  stores the Pi-side persistent inventory, pairing state, device state, settings, and dashboard data

These are not the same database. They are connected by the sync contract.

## Sync role

Sync is the official Android-to-Pi integration path.

- Pairing creates trust and stores the Pi endpoint and auth token.
- The first sync now runs immediately after pairing.
- Later sync runs exchange changed bags and items since `last_sync_at`.
- Conflicts are detected and stored on Android for user resolution.

## Installer, launcher, and service role

The Pi side is intended to feel like a deployable product, not a developer-only script pile.

- `pi-server/install.sh`
  one-time installer
- `pi-server/scripts/run_backend.sh`
  stable backend startup command
- `pi-server/scripts/launch.sh`
  desktop-friendly launcher
- `pi-server/systemd/gobag-backend.service`
  boot-time service

## Important assumptions

- Android and Pi communicate over local HTTP on the same LAN or hotspot.
- Cleartext HTTP is allowed on Android because the Pi typically uses local IP URLs like `http://192.168.x.x:8080`.
- The Pi may need `GOBAG_BASE_URL` set explicitly on complex network setups.
- The Pi dashboard is summary-oriented. Android remains the main editing surface.

## Current limitations

- Full Android clean build verification was blocked in this environment by restricted Gradle downloads.
- Full live FastAPI runtime verification was blocked here because the required Python packages are not installed in this local environment.
- Admin protection is intentionally lightweight and local-product-oriented, not enterprise auth.
- The Pi backend is mostly implemented in one large file, `pi-server/main.py`.

## What not to accidentally change

- Do not switch Android away from Room-first UI without intentionally redesigning the whole app contract.
- Do not make Android partially depend on Pi CRUD endpoints for some screens and sync for others unless that rewrite is deliberate and end-to-end complete.
- Do not remove the immediate first sync after pairing.
- Do not reintroduce `fallbackToDestructiveMigration()` in Room.
- Do not let the Pi advertise `localhost` or `127.0.0.1` as the primary phone-facing address unless there is no better option.

## Fast orientation for another engineer or AI agent

Read these files first:

1. `pi-server/main.py`
2. `android-app/app/src/main/java/com/gobag/app/MainActivity.kt`
3. `android-app/data/repository/src/main/java/com/gobag/data/repository/GoBagSyncRepository.kt`
4. `android-app/data/repository/src/main/java/com/gobag/data/repository/GoBagPairingRepository.kt`
5. `android-app/data/local/src/main/java/com/gobag/data/local/LocalDataSourceFactory.kt`
6. `INSTALLATION_AND_DEPLOYMENT.md`
7. `SYNC_AND_PAIRING.md`

## Bottom line

GO BAG is a Room-first Android app synchronized with a SQLite-backed Raspberry Pi FastAPI server. The Pi is the local sync hub and deployment target. Pairing plus sync is the central integration contract.
