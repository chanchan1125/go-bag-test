# GO BAG GitHub Repo And Raspberry Pi Deployment

This guide gives you one clean path to:

1. publish this project to a GitHub repository
2. deploy the Raspberry Pi backend and touchscreen app
3. install the Android APK and pair it with the Pi

## What this project contains

- `android-app/`
  Android phone app
- `pi-server/`
  Raspberry Pi backend, installer, service, and desktop launcher
- `docs/`
  supporting install and verification documentation

## Before you publish to GitHub

Do not push local-only machine files or secrets.

Important examples:

- do keep example files like `pi-server/.env.example`
- do keep `pi-server/config/gobag.env.example`
- do not commit real `gobag.env`
- do not commit databases like `gobag.db`
- do not commit logs, virtualenvs, SDK folders, or Android build outputs
- do not commit `android-app/local.properties`

The current `.gitignore` already covers the main local/runtime files for this project.

## Create the GitHub repository

Create a new GitHub repository in the GitHub web UI.

Recommended settings:

- visibility: `Private`
- initialize with README: `No`
- add `.gitignore` from GitHub: `No`
- add license from GitHub: optional

Suggested repository name:

- `go-bag`

## Publish this folder to GitHub

Run these commands on the machine where `git` is installed:

```bash
cd /path/to/go-bag-main
git init
git add .
git status
git commit -m "Initial GO BAG import"
git branch -M main
git remote add origin git@github.com:<your-user>/go-bag.git
git push -u origin main
```

If you prefer HTTPS instead of SSH:

```bash
git remote add origin https://github.com/<your-user>/go-bag.git
git push -u origin main
```

## Recommended first GitHub checklist

Before the first push, confirm:

- `android-app/local.properties` is not being committed
- no real `.env` file is being committed
- no `gobag.env` file is being committed
- no SQLite database is being committed
- no APK or build folder is being committed unless you intentionally want release artifacts in GitHub

You can use:

```bash
git status
git diff --cached
```

## Deploy to Raspberry Pi 4 from GitHub

On the Raspberry Pi 4:

```bash
sudo apt-get update
sudo apt-get install -y git
cd /home/pi
git clone https://github.com/<your-user>/go-bag.git
cd go-bag/pi-server
chmod +x install.sh
./install.sh
```

If your Pi desktop username is not `pi`, replace `/home/pi` with that user's home directory.

## Optional touchscreen kiosk-style setup

If the Pi is mostly a dedicated GO BAG station, use:

```bash
cd /home/pi/go-bag/pi-server
./install.sh --kiosk
```

That enables:

- backend service autostart
- desktop-login autostart
- GO BAG opening in kiosk-style mode after login

If you want the desktop icon only, without auto-opening after login:

```bash
cd /home/pi/go-bag/pi-server
./install.sh
```

The installed desktop launcher already opens GO BAG in standalone app mode instead of a normal browser tab.

## Where the Pi install lives

After install, the app is placed here:

- app: `/opt/gobag/app`
- config: `/opt/gobag/config/gobag.env`
- data: `/opt/gobag/data/gobag.db`
- logs: `/opt/gobag/logs`

## Configure the Pi backend

Review:

- `/opt/gobag/config/gobag.env`

Most important values:

```env
GOBAG_HOST=0.0.0.0
GOBAG_PORT=8080
GOBAG_BASE_URL=http://<pi-ip>:8080
```

Find the Pi IP address with:

```bash
hostname -I
```

If the dashboard QR or phone connection points to the wrong address, set `GOBAG_BASE_URL` to the Pi's real LAN IP and restart:

```bash
sudo systemctl restart gobag-backend.service
```

## Verify the Pi deployment

Run:

```bash
systemctl status gobag-backend.service --no-pager
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/device/status
curl http://127.0.0.1:8080/sync/status
```

Open the dashboard locally or from another device:

```text
http://<pi-ip>:8080/
```

You can also launch the local window manually:

```bash
/opt/gobag/app/launch.sh
```

## Install the Android app

The debug APK currently builds to:

- `android-app/app/build/outputs/apk/debug/app-debug.apk`

Install it on the phone with Android Studio, `adb`, or by copying the APK to the device and opening it.

Example with `adb`:

```bash
adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk
```

## Pair the phone with the Pi

1. Put the phone and Raspberry Pi on the same network.
2. Open the GO BAG dashboard on the Pi.
3. Open the Android app.
4. Go to Pairing.
5. Scan the QR code shown by the Pi.
6. Confirm inventory downloads and sync works.

## Update the Pi after pushing new GitHub changes

On the Pi:

```bash
cd /home/pi/go-bag
git pull
cd pi-server
./install.sh
```

That refreshes the installed app under `/opt/gobag/app` and restarts the backend service.

## If you cannot use Git on the Pi

Alternative flow:

1. download the GitHub repo ZIP on another machine
2. copy it to the Pi
3. extract it
4. run `pi-server/install.sh`

Git is still the cleaner option because updates become a simple `git pull`.

## Related docs

- `README.md`
- `INSTALLATION_AND_DEPLOYMENT.md`
- `pi-server/README.md`
- `docs/GITHUB_PRIVATE_REPO_GUIDE.md`
