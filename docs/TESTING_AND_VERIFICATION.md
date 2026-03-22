# GO BAG Testing And Verification

This document describes the real verification flow supported by the current codebase. It is a checklist for device testing, not a claim that all checks have already been executed successfully here.

## Verification scope

The critical end-to-end path for GO BAG is:

1. Raspberry Pi backend installs and starts
2. `/health` works
3. `/device/status` works
4. Android app installs on a real device
5. QR pairing succeeds
6. first sync populates Android Room immediately
7. add, edit, and delete operations survive sync and restart cycles

## 1. Backend service running

On the Pi:

```bash
systemctl status gobag-backend.service --no-pager
journalctl -u gobag-backend.service -n 100 --no-pager
```

Pass condition:

- `gobag-backend.service` is active
- there is no immediate crash loop

## 2. `/health` works

```bash
curl http://127.0.0.1:8080/health
```

Pass condition:

- response contains `"status":"ok"`

Notes:

- this endpoint also reports camera availability
- camera status is useful, but camera success is not required for pairing/sync verification

## 3. `/device/status` works

```bash
curl http://127.0.0.1:8080/device/status
```

Pass condition:

- response returns device metadata rather than an error
- `connection_status` is present
- `local_ip` is present or intentionally blank
- `database_path` points to the active SQLite file

## 4. Android app installs

In Android Studio:

1. Open `android-app/`
2. Let Gradle sync
3. Install the `app` module on a real device

Pass condition:

- app installs and launches
- camera permission can be granted for pairing
- notification permission can be granted if prompted

## 5. QR pairing works

Test setup:

- Pi backend running
- phone on the same Wi-Fi network or Pi hotspot
- Pi dashboard open at `http://<pi-ip>:8080/`

Steps:

1. Open Pairing on Android.
2. Optionally enter the Pi address and use `Test Entered Address`.
3. Scan the Pi QR code.
4. Use `Pair and Download Inventory`.

Pass condition:

- pairing succeeds without a `400` or auth error
- app reports successful pairing or a pairing-with-warning state

Important interpretation:

- `Test Entered Address` checks reachability only
- it does not authenticate the phone
- successful QR pairing is the real auth step

## 6. First sync works immediately

Immediately after successful QR pairing:

1. Open Inventory.
2. Check whether bag and item data appears without needing a separate manual sync first.

Pass condition:

- Room is populated right after pairing
- inventory is visible immediately if the Pi already has data

Failure to flag:

- QR pairing succeeds but inventory remains empty unexpectedly
- app reports that pairing succeeded but initial download failed

## 7. Add, edit, and delete item works

Run this on a paired phone:

1. Add an item.
2. Edit that item.
3. Delete or soft-delete that item.
4. Open Sync and run sync if needed.

Pass condition:

- local edits are visible immediately in Android
- sync completes without breaking the paired state
- Pi-side state reflects the change after sync

Practical note:

- the app is local-first for editing
- changes save locally first, then sync to the Pi

## 8. Persistence survives backend restart

1. Make a visible inventory change.
2. Confirm the change exists on the Pi after sync.
3. Restart the backend:

```bash
sudo systemctl restart gobag-backend.service
```

4. Check:

```bash
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/device/status
```

5. Reopen the app and sync again if needed.

Pass condition:

- paired state still works
- Pi data remains present
- previously synced inventory still exists

## 9. Persistence survives Pi reboot

1. Reboot the Pi.
2. After boot, verify:

```bash
systemctl status gobag-backend.service --no-pager
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/device/status
```

3. Open the Android app again.

Pass condition:

- backend auto-starts if installed with autostart enabled
- data is still present in `/opt/gobag/data/gobag.db`
- the app can reconnect and sync with the same paired phone

## 10. Disconnect and reconnect behavior

1. Pair the phone successfully.
2. Disconnect the phone from the network or stop the backend temporarily.
3. Make a local Android change if the app allows it.
4. Restore network/backend access.
5. Run sync again.

Pass condition:

- Android remains usable for local-first flows
- reconnecting allows sync to resume
- status refresh and sync recover without re-pairing in normal cases

## Recommended quick verification commands on the Pi

```bash
systemctl status gobag-backend.service --no-pager
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/device/status
curl http://127.0.0.1:8080/sync/status
```

## Known limits of this document

This checklist is intentionally honest about what it does not prove by itself:

- it does not prove the Pi installer succeeded on your hardware until you run it there
- it does not prove Android QR camera behavior until you test on a real device
- it does not prove same-network routing until the phone and Pi are tested together

Use this as the required acceptance checklist before declaring the project fully deployment-ready.
