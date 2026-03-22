# Changelog Current State

## Architecture decision summary

The project is now explicitly documented as sync-first.

- Android UI uses Room as its source of truth.
- Raspberry Pi is the backend sync hub.
- Pairing plus sync is the official Android integration contract.
- Pi CRUD endpoints remain available for Pi/web/admin use.

## Recently fixed

- pairing now triggers an immediate first sync into Room
- Android no longer waits for a manual sync to show Pi-backed inventory after pairing
- `fallbackToDestructiveMigration()` was removed
- explicit Room migration was added
- Pi advertised address logic was improved to avoid unusable localhost-style results when possible
- pairing and settings UX now distinguish saved address, reachable backend, and paired/authenticated state
- `Scan for Devices` wording was changed to `Test Entered Address`
- QR pairing now requests camera permission before opening the scanner
- lightweight admin token protection was added for Pi admin routes
- Raspberry Pi install, launcher, service, and deployment docs were aligned with the current code

## What remains partial or unverified

- fresh Android clean build verification still requires a network-enabled environment
- full FastAPI runtime verification still requires the backend dependencies to be installed in the local Python environment
- real hardware validation on an actual Raspberry Pi and Android device is still required for final sign-off

## Current final status

- Frontend: PARTIAL
- Backend: PARTIAL
- Database: WORKING
- Frontend-Backend Connection: WORKING
- Backend-Database Connection: WORKING
- Raspberry Pi Readiness: PARTIAL

## Why those verdicts are still partial

- Frontend is partial because clean build and install were not fully executed in this environment.
- Backend is partial because live FastAPI runtime with installed dependencies was not executed here.
- Raspberry Pi readiness is partial because the install and startup flow is implemented, but not fully hardware-validated in this environment.

## Known limitations

- backend logic is still concentrated in `pi-server/main.py`
- admin route protection is lightweight and local-use oriented
- sync behavior is code-aligned, but live device verification is still the remaining practical gate

## Recommended next steps

1. Run the Raspberry Pi installer on an actual Pi.
2. Build and install the Android app on a real phone.
3. Execute the checklist in `VERIFICATION_CHECKLIST.md`.
4. Capture any real-device defects discovered during pairing, sync, and persistence testing.
5. Only after that, upgrade the partial verdicts if runtime behavior matches the documented flows.
