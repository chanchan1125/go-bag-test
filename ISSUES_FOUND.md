# Issues Found

## Confirmed issues

1. Pi deployment is backend-only, not touchscreen-appliance ready.
   - Evidence: `pi-server/install.sh` installs a `systemd` service only; no kiosk/browser autostart code exists.
   - Impact: The Pi will not boot directly into the Go-Bag UI without extra manual setup.

2. Android clean build could not be verified in this environment.
   - Evidence: `gradlew.bat :app:assembleDebug` attempted to download `gradle-8.9-bin.zip` and failed because network access was blocked.
   - Impact: Source-level review is strong, but fresh-build status is still unconfirmed here.

3. Android notifications were missing Android 13 permission handling.
   - Evidence: the manifest only declared `INTERNET` and `CAMERA`, while `NotificationHelper` posted notifications directly.
   - Fix applied: added `POST_NOTIFICATIONS` permission and defensive permission/enablement checks before posting.

4. Android UI contained visibly broken separator text in two screens.
   - Evidence: separator text rendered as a bullet character in source and had previously shown as mojibake during terminal inspection.
   - Fix applied: replaced those separators with ASCII `|` in Home and Check Mode screens.

5. Room is configured for destructive migration.
   - Evidence: `android-app/data/local/src/main/java/com/gobag/data/local/LocalDataSourceFactory.kt` calls `fallbackToDestructiveMigration()`.
   - Impact: local Android data may be wiped during schema changes.

6. Pi admin endpoints are unauthenticated.
   - Evidence: `POST /admin/new_pair_code` and `POST /admin/revoke_tokens` in `pi-server/main.py` have no auth dependency.
   - Impact: anyone on the reachable network could rotate pair codes or revoke access.

7. Pi pairing QR can advertise the wrong base URL.
   - Evidence: `pi-server/main.py` builds `base_url` from the request `Host` header unless `GOBAG_BASE_URL` is set.
   - Impact: pairing may fail if the dashboard is opened via an unreachable hostname or forwarded port.

8. FastAPI startup lifecycle uses the older event-hook style.
   - Evidence: `pi-server/main.py` uses `@app.on_event("startup")`.
   - Impact: not broken today, but worth modernizing to lifespan-style startup in a future cleanup pass.

## Confirmed strengths

- Pi and Android sync contracts are structurally aligned.
- Port usage is coherent at `8080`.
- Pairing flow is implemented end to end.
- Android app is properly local-first in architecture.
- Pi backend installer is realistic for Raspberry Pi OS.

## Not verifiable here

- End-to-end pairing on a real LAN
- Clean Android compile from scratch
- Pi camera capture on real hardware
- Real touchscreen usability on a Pi display
