# Go-Bag Android App

## Stack
- Kotlin
- Jetpack Compose only
- Navigation Compose
- MVVM (`ViewModel` + `StateFlow`)
- Room
- Retrofit + OkHttp
- DataStore for `device_state`
- ZXing (`com.journeyapps:zxing-android-embedded`) for QR scan

## Modules
- `:app`
- `:core:common`
- `:core:model`
- `:data:local`
- `:data:remote`
- `:data:repository`
- `:domain`
- `:feature:inventory`
- `:feature:checkmode`
- `:feature:sync`
- `:feature:pairing`

## Behavior notes
- Item JSON uses exact snake_case fields matching Pi server.
- Sync uses `changed_items` where `updated_at > last_sync_at`.
- Auto-sync defaults OFF.
- Auto-sync is disabled when conflicts exist.
- Pairing stores `auth_token` and `time_offset_ms` from `/pair`.

## Build
1. Open `android-app` in Android Studio.
2. Let Gradle sync.
3. Run `app` on device.

## Pair flow
1. Open Pi dashboard (`http://<pi-ip>:8080/`).
2. In app, open Pairing screen and scan QR.
3. App calls `/pair` and stores credentials.
4. Open Sync screen and press Sync Now.
