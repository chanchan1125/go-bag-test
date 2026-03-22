# Troubleshooting

## Android cannot reach the Pi

Symptoms:

- `Test Entered Address` fails
- settings show offline
- sync fails immediately

Steps:

1. Make sure the phone and Pi are on the same Wi-Fi or hotspot.
2. On the Pi, run:

```bash
curl http://127.0.0.1:8080/health
```

3. Check the Pi IP:

```bash
hostname -I
```

4. Use a full URL in Android, for example:

```text
http://192.168.1.20:8080
```

5. Do not use `localhost` on the phone.

## Pi advertises the wrong address

Symptoms:

- QR points to an unusable URL
- `/device/status` shows blank or wrong `local_ip`

Fix:

1. Edit `/opt/gobag/config/gobag.env`
2. Set:

```env
GOBAG_BASE_URL=http://<actual-pi-ip>:8080
```

3. Restart:

```bash
sudo systemctl restart gobag-backend.service
```

4. Re-open the dashboard and generate a fresh QR code if needed.

## QR pairing succeeds but inventory stays empty

Possible causes:

- first sync failed after pairing
- sync endpoint unreachable even though pairing succeeded

Steps:

1. Open Android Sync screen and retry sync.
2. On the Pi, verify:

```bash
curl http://127.0.0.1:8080/device/status
curl http://127.0.0.1:8080/sync/status
```

3. Check backend logs:

```bash
journalctl -u gobag-backend.service -n 100 --no-pager
```

4. Confirm the phone still shows paired/authenticated, not just saved endpoint.

## Backend service is not starting

Steps:

1. Check service:

```bash
systemctl status gobag-backend.service --no-pager
```

2. Check logs:

```bash
journalctl -u gobag-backend.service -n 200 --no-pager
```

3. Verify the virtualenv exists:

```bash
ls /opt/gobag/.venv/bin/python
```

4. Verify config exists:

```bash
cat /opt/gobag/config/gobag.env
```

5. Try a manual start:

```bash
/opt/gobag/app/scripts/run_backend.sh
```

## Database path or permission issues

Symptoms:

- service starts but cannot open SQLite
- launcher says database missing repeatedly

Check:

```bash
ls -ld /opt/gobag/data
ls -l /opt/gobag/data/gobag.db
```

If missing, re-run:

```bash
cd /opt/gobag/app
sudo GOBAG_CONFIG_FILE=/opt/gobag/config/gobag.env ./scripts/init_db.sh
```

## Sync failing after pairing

Steps:

1. Verify pairing actually stored an auth token on Android.
2. Confirm `/pair` succeeded and phone shows authenticated state.
3. Check backend logs for `/sync` failures.
4. Verify Pi service is still reachable:

```bash
curl http://127.0.0.1:8080/health
```

5. Retry from the Android Sync screen.

## Camera permission issues on Android

Symptoms:

- QR scanner does not open
- pairing button appears to do nothing

Fix:

1. On the phone, open app permissions.
2. Grant Camera permission to GO BAG.
3. Retry `Pair and Download Inventory`.

The app now requests permission before opening the scanner, but users can still deny it at runtime.

## Notification permission issues

Symptoms:

- sync works but no alert notification appears

Fix:

1. Grant notification permission in Android settings.
2. Confirm sync still works even without that permission.

This is a quality-of-life feature, not a core pairing blocker.

## Localhost vs LAN IP confusion

Important rule:

- `localhost` on Android means the phone itself
- it does not mean the Raspberry Pi

Always use:

- `http://<pi-ip>:8080`

not:

- `http://127.0.0.1:8080`

unless you are calling the Pi locally from the Pi itself

## Service is active locally but unreachable from the phone

Possible causes:

- wrong Pi IP
- firewall or network isolation
- phone on different network
- Pi advertising the wrong address

Steps:

1. Verify the backend binds to `0.0.0.0` in config.
2. Confirm the phone is on the same subnet or hotspot.
3. Set `GOBAG_BASE_URL` explicitly.
4. Test access from another device browser using `http://<pi-ip>:8080/`.

## FastAPI dependency issues

Symptoms:

- import errors when starting backend manually

Fix:

```bash
cd /opt/gobag/app
/opt/gobag/.venv/bin/pip install -r requirements.txt
```

If the Pi install was interrupted, run `./install.sh` again.

## Gradle or dependency build issues

Symptoms:

- Android Studio fails to sync or build
- wrapper download fails

Fix:

1. Use a network-enabled build machine.
2. Let Android Studio download the required Gradle distribution and dependencies.
3. Rebuild the `app` module.

In this documentation set, fresh clean Android build verification is still marked partial for exactly this reason.

## Room migration issues

Symptoms:

- app crashes after schema version changes

Fix:

1. Check `LocalDataSourceFactory.kt`.
2. Confirm a real migration exists for the new version.
3. Do not reintroduce destructive fallback unless data loss is acceptable and clearly documented.

## How to inspect logs and status

### Pi service logs

```bash
journalctl -u gobag-backend.service -n 100 --no-pager
```

### Pi launcher logs

```bash
tail -n 100 /opt/gobag/logs/launcher.log
tail -n 100 /opt/gobag/logs/launcher-backend.log
```

### Pi status endpoints

```bash
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/device/status
curl http://127.0.0.1:8080/sync/status
```

### Android status surfaces

- Pairing screen
- Sync screen
- Settings screen

These show saved endpoint, pairing state, connection status, and last connection error.
