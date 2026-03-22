# Android App

## Purpose

The Android app is the primary user-facing client for GO BAG. It is responsible for inventory editing, checklist use, pairing, sync control, and offline-first operation.

## Source of truth on Android

Room is the source of truth for Android UI.

- Screens observe Room-backed flows.
- DataStore stores device and sync metadata, not the full inventory dataset.
- The Raspberry Pi is the backend sync hub, but Android UI does not read inventory directly from Pi CRUD endpoints.

This distinction matters:

- Android Room data:
  drives the current UI
- Raspberry Pi SQLite data:
  drives Pi persistence and sync source state
- sync contract:
  moves changes between them

## Main screens

- Home / dashboard
- Inventory
- Check mode / checklist
- Sync
- Pairing
- Settings

## Navigation structure

Navigation is hosted in `android-app/app/src/main/java/com/gobag/app/MainActivity.kt`.

Routes:

- `home`
- `inventory`
- `check`
- `sync`
- `pair`
- `settings`

## Important modules and files

### App shell

- `android-app/app/src/main/java/com/gobag/app/MainActivity.kt`
- `android-app/app/src/main/java/com/gobag/app/AppContainer.kt`

### Core model

- `android-app/core/model/src/main/java/com/gobag/core/model/Models.kt`

### Local data

- `android-app/data/local/src/main/java/com/gobag/data/local/`

### Remote data

- `android-app/data/remote/src/main/java/com/gobag/data/remote/GoBagApi.kt`
- `android-app/data/remote/src/main/java/com/gobag/data/remote/Dtos.kt`

### Repositories

- `android-app/data/repository/src/main/java/com/gobag/data/repository/GoBagItemRepository.kt`
- `android-app/data/repository/src/main/java/com/gobag/data/repository/GoBagPairingRepository.kt`
- `android-app/data/repository/src/main/java/com/gobag/data/repository/GoBagSyncRepository.kt`
- `android-app/data/repository/src/main/java/com/gobag/data/repository/DeviceStateStore.kt`

### Feature ViewModels

- inventory
- check mode
- sync
- pairing
- settings

## Repository and service layers

### GoBagItemRepository

- exposes bags and items from Room
- inserts and updates local inventory
- soft-deletes items
- supports the inventory and check mode screens

### GoBagPairingRepository

- parses QR payload
- tests the Pi address through `GET /device/status`
- pairs through `POST /pair`
- stores pairing data in DataStore
- downloads templates through `GET /templates`
- immediately triggers the first sync so Room is populated

### GoBagSyncRepository

- refreshes device and sync status
- builds sync payloads from local changes
- calls `POST /sync`
- applies server changes into Room
- stores conflicts locally
- updates `last_sync_at`

## Room usage

The Room database is created by `LocalDataSourceFactory.kt`.

Key facts:

- database name is `gobag.db`
- Room schema version is `2`
- migration `1 -> 2` is defined explicitly
- `fallbackToDestructiveMigration()` has been removed

Current local tables:

- bags
- items
- recommended_templates
- conflicts

## Pairing flow

1. User can optionally type a Pi URL and tap `Test Entered Address`.
2. That only verifies reachability. It does not authenticate the phone.
3. User taps `Pair and Download Inventory`.
4. Android requests camera permission if needed.
5. QR payload is scanned.
6. App calls `POST /pair`.
7. App stores auth token, base URL, Pi device ID, and time offset.
8. App downloads templates.
9. App immediately runs first sync and writes incoming inventory into Room.

## Sync flow

- Sync is manual or triggered after local edits when appropriate.
- Android sends changed bags and items since `last_sync_at`.
- Pi responds with server changes, alerts, conflicts, and auto-resolutions.
- Android updates Room and DataStore.

## Inventory flow

- inventory forms update Room first
- bag and item changes can be synced afterward
- delete is soft delete
- packed status toggles are local-first and then synced

## Permissions used

- `INTERNET`
  required for Pi HTTP calls
- `CAMERA`
  required for QR scan pairing
- `POST_NOTIFICATIONS`
  used for local alert notifications when supported by Android version and user permission

The manifest also enables cleartext traffic because local Pi connections are expected to use HTTP.

## Network behavior

Android directly calls these Pi endpoints:

- `GET /health`
- `GET /device/status`
- `GET /sync/status`
- `GET /time`
- `GET /templates`
- `POST /pair`
- `POST /sync`

Android does not currently use `/bags`, `/items`, `/categories`, `/alerts`, or `/settings` as its primary inventory read path.

## Local and offline behavior

- Room keeps inventory visible even without the Pi
- failed connection checks or syncs update status fields instead of deleting local data
- pairing state and endpoint data survive app restarts through DataStore

## Error, loading, and status behavior

The app distinguishes between:

- saved endpoint only
- reachable backend
- paired and authenticated
- offline
- paired with warning

This helps avoid confusing a saved URL with a real pairing session.

## Notification behavior

Notifications are lightweight and local:

- sync results can generate local alert notifications
- there is no Pi-to-phone push channel
- if notification permission is denied, the app should continue functioning

## Known limitations

- Fresh clean build verification was not completed in this environment because Gradle downloads were blocked.
- The Android UI contract is sync-first, so Pi CRUD endpoints are not reflected live unless a sync updates Room.
- Conflict handling exists, but live device validation is still required for final confidence.

## What to preserve

- Room must remain the Android UI source of truth.
- First sync after pairing must remain automatic.
- The UI should keep distinguishing address configuration from pairing authentication.
