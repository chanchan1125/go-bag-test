# Sync And Pairing

## Why this architecture is sync-first

GO BAG uses a sync-first design because Android is expected to work locally even when the Raspberry Pi is not reachable.

That means:

- Android UI reads from Room
- Raspberry Pi stores the backend's persistent copy in SQLite
- pairing and sync keep the two sides aligned

This is different from a live CRUD app where every inventory screen reads directly from the server.

## The three states users often confuse

These states are now intentionally separated in the UI and code.

### Saved endpoint

Meaning:

- the phone has a stored Pi address such as `http://192.168.4.1:8080`
- no authentication has happened yet
- sync is not authorized just because the address is saved

### Reachable backend

Meaning:

- the phone can contact the Pi at the saved or typed address
- connection test works
- this still does not mean the phone is paired or authenticated

### Paired and authenticated

Meaning:

- the phone scanned the Pi QR code
- the Pi accepted the pair code
- the phone received and stored an auth token
- sync is now authorized

## QR pairing flow step by step

1. The Pi dashboard generates a QR payload containing:
   - `base_url`
   - optional `remote_base_url`
   - `pair_code`
   - `pi_device_id`
2. Android requests camera permission if needed.
3. The user scans the QR code.
4. Android parses the payload.
5. Android tests the advertised endpoint.
6. Android sends `POST /pair` with:
   - `phone_device_id`
   - `pair_code`
7. Pi returns:
   - `auth_token`
   - `pi_device_id`
   - `server_time_ms`
8. Android stores pairing data in DataStore.
   - Pi identity
   - local endpoint
   - optional remote endpoint
9. Android fetches templates.
10. Android immediately runs the first sync.

## First sync after pairing

The first sync is now automatic and is one of the most important recent fixes.

Behavior:

- `last_sync_at` is reset to `0`
- Android calls `POST /sync`
- Pi returns server bags and items
- Android writes those into Room
- inventory screens can now show real server-backed data immediately

If this initial sync fails:

- the phone may still be paired and authenticated
- the UI should show a warning state instead of pretending setup fully succeeded
- the user can retry from the Sync screen

## How Room is populated from the Pi

Room does not fill itself from direct bag CRUD reads.

It is populated through:

- first sync after pairing
- later manual or automatic sync runs

This is a deliberate part of the sync-first contract.

## Regular sync flow

1. Android reads `last_sync_at` from DataStore.
2. Android tries the paired Pi's local endpoint first.
3. If the local path is not reachable, Android falls back to the saved remote endpoint.
4. Android collects locally changed bags and items from Room.
5. Android sends them to `POST /sync`.
6. Pi compares incoming changes to SQLite state.
7. Pi returns:
   - `server_bag_changes`
   - `server_item_changes`
   - `conflicts`
   - `auto_resolved`
   - `alerts`
8. Android applies those results into Room and DataStore.

## What happens when sync fails

- local Room data remains intact
- Android marks connection state as offline or failed
- user feedback explains that data is saved locally or needs retry
- later sync attempts can push those pending changes

## Offline edits and reconnection behavior

Current design:

- Android edits are local-first
- if the Pi is unavailable, the app should remain usable locally
- once connectivity returns, the user can run sync and reconcile changes

This is the practical offline-first behavior implemented today.

## Sync state meanings

Common status values seen in code or UI:

- `address_saved`
  endpoint stored, not paired yet
- `paired`
  token present and paired state stored
- `synced`
  backend recently completed sync work
- `waiting_for_pair`
  Pi has no active paired phone
- `offline`
  Android could not reach the Pi
- `unknown`
  default or uninitialized client-side state

UI wording may normalize these for readability.

## Status labels used in the UI

Examples:

- `Phone paired and authenticated`
- `Address saved, pairing still required`
- `Waiting for first secure pairing`
- `Paired with warning`
- `Test Entered Address`
- `Pair and Download Inventory`
- `Save address only`

These labels were intentionally adjusted so users do not confuse configuration with pairing.

## What Android uses directly

Android directly uses:

- `/device/status`
- `/templates`
- `/pair`
- `/sync`
- supporting status and health endpoints

Android does not directly depend on Pi CRUD endpoints for inventory display.

## Current limitations of the sync design

- Conflicts still require explicit handling and live-device validation.
- There is no background service guaranteeing sync on every Android state change.
- Pi CRUD endpoints and Android sync models are different shapes because they serve different purposes.
- Android and Pi can temporarily diverge until the next sync. That is expected.
- Different Wi-Fi networks only work when the Pi has a real remote path configured.
  Examples:
  - secure HTTPS endpoint
  - trusted VPN or mesh address such as WireGuard or Tailscale
