# Testing And Verification

## Test strategy

GO BAG needs both code-level verification and live-device verification.

### Code-level verification

- static inspection of Gradle, manifests, imports, and repository wiring
- Python syntax verification for the Pi backend
- installer, launcher, and service review

### Live-device verification

- Raspberry Pi install and service startup
- Android build and install
- QR pairing on a real phone
- first sync and inventory visibility
- CRUD and persistence checks
- offline and reconnect behavior

## What has been statically verified

- Android architecture is aligned with sync-first behavior.
- Pairing now triggers the first sync automatically.
- Room uses an explicit migration instead of destructive fallback.
- Pi install, launcher, service, and config flow are wired in code.
- Pi backend Python syntax passed `python -m py_compile`.

## What still requires live hardware testing

- fresh Android clean build on a network-enabled machine
- actual FastAPI runtime with installed Python requirements
- QR scan and camera permission behavior on a real Android device
- real Pi LAN or hotspot address advertisement
- persistence after service restart and Pi reboot

## Canonical live test checklist

The detailed checklist is also stored in:

- `VERIFICATION_CHECKLIST.md`

Use that file during hands-on validation.

## Raspberry Pi verification steps

1. Copy the repository to the Raspberry Pi.
2. Run:

```bash
cd pi-server
chmod +x install.sh
./install.sh
```

3. Verify service:

```bash
systemctl status gobag-backend.service --no-pager
```

4. Verify health:

```bash
curl http://127.0.0.1:8080/health
```

5. Verify device status:

```bash
curl http://127.0.0.1:8080/device/status
```

6. Open dashboard:

```text
http://<pi-ip>:8080/
```

Expected result:

- service active
- `/health` returns success
- dashboard loads and shows a QR code

## Android verification steps

1. Open `android-app/` in Android Studio.
2. Build and install the app on a physical Android device.
3. Ensure the phone is on the same network or hotspot as the Pi.
4. Open Pairing.
5. Grant camera permission when requested.

Expected result:

- app installs successfully
- pairing screen opens
- QR scanner can open after permission

## End-to-end verification steps

### Pairing and first sync

1. On the Pi dashboard, confirm a QR code is visible.
2. On Android, optionally test the typed Pi URL using `Test Entered Address`.
3. Tap `Pair and Download Inventory`.
4. Scan the Pi QR code.
5. Verify Android shows paired/authenticated state.
6. Verify inventory appears without a manual sync.

Pass criteria:

- pairing succeeds
- first sync runs automatically
- Room-backed inventory screens show Pi-backed data immediately

### Inventory CRUD

1. Open Inventory on Android.
2. Add an item.
3. Edit the item.
4. Toggle packed status.
5. Delete the item.
6. Verify Pi-side data through:

```bash
curl http://127.0.0.1:8080/bags
curl http://127.0.0.1:8080/bags/<bag-id>/items
```

Pass criteria:

- Android updates local UI correctly
- sync pushes changes to the Pi
- Pi responses reflect the updated data

### Persistence after restart

1. Restart the backend:

```bash
sudo systemctl restart gobag-backend.service
```

2. Reopen Android and dashboard.
3. Verify data is still present.

Pass criteria:

- items persist after service restart

### Persistence after reboot

1. Reboot the Raspberry Pi.
2. After boot, verify:

```bash
systemctl status gobag-backend.service --no-pager
```

3. Open dashboard and Android app again.

Pass criteria:

- service starts automatically if enabled
- inventory remains available

### Offline and disconnect behavior

1. Disconnect phone from Pi network or stop the backend.
2. Edit an item on Android.
3. Verify the app does not crash.
4. Restore connectivity.
5. Run sync.

Pass criteria:

- local edits remain visible
- offline or error state is shown clearly
- reconnect and sync restore consistency

## Pass/fail interpretation

### Pass

- backend starts
- Android installs
- pairing works
- first sync populates Room
- CRUD changes persist in Pi SQLite
- data survives restart and reboot
- offline behavior is understandable and recoverable

### Fail

- QR pairing succeeds but inventory stays empty
- service never becomes reachable
- CRUD changes vanish after restart
- app crashes when Pi is offline
- Android cannot install or cannot access the camera for pairing

## Honest current status

- Code-level readiness: strong
- Live-device verification in this environment: incomplete
- The project should be tested on a real Android device and Raspberry Pi before claiming full deployment readiness
