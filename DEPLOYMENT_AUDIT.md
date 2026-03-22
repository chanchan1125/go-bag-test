# Deployment Audit

## Verdict

- Zero manual setup: No
- One-command backend setup on Raspberry Pi: Yes
- One-command full touchscreen appliance setup: No
- Current practical deployment target: `systemd`-managed Pi backend plus Android APK, with the dashboard opened in a browser

## Confirmed from code

### What is already deployable

- `pi-server/install.sh` installs system packages, creates `/opt/gobag`, provisions a venv, installs Python requirements, writes `gobag.service`, enables it, and restarts it.
- The service runs `uvicorn main:app --host 0.0.0.0 --port 8080`.
- Runtime data is stored under `/var/lib/gobag`.
- The backend is realistic for Raspberry Pi OS on `armv7l` or `aarch64`.

### What is not yet deployable as promised touchscreen behavior

- There is no native Pi GUI app in the repo.
- There is no kiosk-mode installer.
- There is no autostart browser configuration.
- There is no display manager configuration for fullscreen dashboard boot.

## Runtime assumptions

- Linux with `systemd`
- `sudo` access
- `apt-get`
- Working network path from phone to Pi
- Optional CSI camera and `libcamera-still`

## Risks and gaps

- The installer always installs camera packages, even if the device has no camera.
- The dashboard's advertised URL depends on the incoming HTTP `Host` header unless `GOBAG_BASE_URL` is set, so reverse-proxy or hostname mistakes can break pairing QR output.
- Admin endpoints to rotate pair codes and revoke tokens are open to anyone who can reach the Pi.
- No firewall, TLS, or reverse-proxy setup is included.
- No image-building, Docker, or appliance packaging flow is included.

## Best realistic deployment target

The repository already supports a reasonable backend deployment model:

1. Run `pi-server/install.sh` on Raspberry Pi OS.
2. Let `gobag.service` keep the FastAPI server alive.
3. Access the dashboard at `http://<pi-ip>:8080/`.
4. Install the Android app from Android Studio or a future APK build.

That is the best realistic supported target today. A true "turn on the Pi and land in the dashboard" experience would require an added kiosk layer and should be treated as a follow-up milestone.

## Recommended next deployment improvement

- Highest-value next step: add an optional kiosk installer that configures Chromium fullscreen autostart on Raspberry Pi OS desktop.
- Best packaging strategy after that: ship a preconfigured Raspberry Pi image or image-building script, because that is the cleanest route to low-touch deployment.
