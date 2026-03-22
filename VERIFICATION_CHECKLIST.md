# GO BAG Verification Checklist

## Integration status

- `app -> Pi API`:
  Android now stores a real Raspberry Pi endpoint, exposes it in Settings, tests it against `GET /device/status`, and uses live Pi status during pairing and sync.
- `Pi API -> SQLite`:
  The FastAPI server now persists bags, items, categories, device state, sync status, and settings in SQLite at `${GOBAG_DATA_DIR}/gobag.db`.
- `Inventory persistence`:
  Pi-side CRUD endpoints read and write SQLite-backed bag and item records. The sync endpoint still updates the same persistent tables.
- `Sync state visibility`:
  The Android app now stores and shows `connection_status`, `pending_changes_count`, `local_ip`, `last_sync_at`, and the last connection error.
- `Offline behavior`:
  Android mutations remain local-first. Failed sync/refresh calls now mark the saved connection state as offline and preserve local data until the next retry.

## Endpoints added or confirmed

- Working in code:
  `GET /health`
  `GET /device/status`
  `GET /time`
  `GET /bags`
  `POST /bags`
  `PUT /bags/{id}`
  `DELETE /bags/{id}`
  `GET /bags/{id}/items`
  `POST /bags/{id}/items`
  `PUT /items/{id}`
  `DELETE /items/{id}`
  `GET /categories`
  `GET /alerts`
  `GET /sync/status`
  `GET /settings`
  `GET /templates`
  `POST /pair`
  `POST /sync`

## Checks run in this environment

- Passed:
  `python -m py_compile pi-server/main.py pi-server/test_api.py`
- Blocked:
  `python pi-server/test_api.py`
  Reason: local Python does not have `fastapi` installed.
- Blocked:
  `./gradlew.bat :app:compileDebugKotlin`
  Reason: Gradle wrapper tried to download `gradle-8.9-bin.zip`, but network access is blocked in this sandbox.

## Recommended on-machine follow-up

1. Create a Python virtualenv in `pi-server/`, install `requirements.txt`, and run `python test_api.py`.
2. On a network-enabled Android machine, run `./gradlew.bat :app:compileDebugKotlin` or build from Android Studio.
3. Launch the Pi server, open `GET /device/status`, then pair the Android app and verify Settings shows the same endpoint/local IP.
4. Create/edit/delete an item on Android while paired, run sync, then confirm the same bag data through `GET /bags` and `GET /bags/{id}/items`.

## Real-device runtime test plan

### Raspberry Pi fresh install

1. Copy the repo to the Raspberry Pi.
2. Run:
   `cd go-bag/pi-server`
   `chmod +x install.sh`
   `./install.sh`
3. Confirm:
   `systemctl status gobag-backend.service --no-pager`
4. Confirm health:
   `curl http://127.0.0.1:8080/health`
5. Confirm device status:
   `curl http://127.0.0.1:8080/device/status`
6. Note the advertised `local_ip` or set `GOBAG_BASE_URL` in `/opt/gobag/config/gobag.env` if needed.
7. Open the dashboard:
   `http://<pi-ip>:8080/`

### Android install

1. Open `android-app/` in Android Studio on a network-enabled machine.
2. Build and install the app on a physical Android device.
3. Grant camera permission when prompted during QR pairing.
4. Ensure the phone is on the same Wi-Fi or Raspberry Pi hotspot.

### Pairing and automatic first sync

1. On the Pi dashboard, verify a QR code and pair code are visible.
2. On Android, open Pairing.
3. Optionally enter the Pi URL and tap `Test Entered Address`.
4. Tap `Pair and Download Inventory`.
5. Scan the QR code from the Pi dashboard.
6. Verify the Android app reports a paired/authenticated state.
7. Verify the first sync completes automatically and the inventory screen shows Pi-backed bags/items without needing manual sync.

### Inventory CRUD verification

1. On Android, open Inventory.
2. Verify at least one bag is visible.
3. Add an item with quantity, unit, notes, and expiry date.
4. Confirm success feedback mentions Raspberry Pi local update completed.
5. Edit the same item and change quantity.
6. Toggle packed/unpacked status.
7. Delete the item.
8. On the Pi, confirm matching data through:
   `curl http://127.0.0.1:8080/bags`
   `curl http://127.0.0.1:8080/bags/<bag-id>/items`

### Persistence verification

1. Stop and restart the backend:
   `sudo systemctl restart gobag-backend.service`
2. Re-open the Android app.
3. Verify bags/items still appear.
4. Reboot the Raspberry Pi.
5. After boot, confirm:
   `systemctl status gobag-backend.service --no-pager`
6. Re-open the dashboard and Android app.
7. Verify inventory data is still present.

### Offline / disconnected-state verification

1. Turn off the Raspberry Pi backend or disconnect Wi-Fi/hotspot.
2. On Android, edit an item locally.
3. Verify the app shows a saved-locally or offline/error state instead of crashing.
4. Restore backend/network connectivity.
5. Open Sync and run manual sync if needed.
6. Verify the pending local changes reach the Pi and the offline/error state clears.
