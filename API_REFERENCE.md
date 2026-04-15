# API Reference

## Notes before the endpoint list

- Base URL is typically `http://<pi-ip>:8080`
- An optional remote base URL can also be advertised for cross-network access
- Android uses a sync-first contract
- Android directly uses only a subset of the Pi API
- Pi CRUD endpoints still exist for Pi/web/admin use

## Auth model

### Pairing auth

- `POST /pair` exchanges a valid pair code for an auth token

### Sync auth

- `POST /sync` requires `Authorization: Bearer <auth_token>`

### Admin auth

- admin routes require `GOBAG_ADMIN_TOKEN` if that token is configured
- accepted via `x-gobag-admin-token` header or `?token=...` query parameter

## Android-direct endpoints

### `GET /health`

- purpose: service health summary
- auth: none
- response fields:
  - `status`
  - `camera_enabled`
  - `camera_cmd_available`

### `GET /device/status`

- purpose: device identity and current Pi-side status
- auth: none
- Android uses it directly
- response fields:
  - `id`
  - `device_name`
  - `last_sync_at`
  - `connection_status`
  - `pending_changes_count`
- `local_ip`
- `local_base_url`
- `remote_base_url`
- `updated_at`
- `pi_device_id`
- `pair_code`
  - `paired_devices`
  - `database_path`

### `GET /sync/status`

- purpose: condensed sync state
- auth: none
- Android uses it directly
- response fields:
  - `id`
  - `device_name`
  - `last_sync_at`
  - `connection_status`
  - `pending_changes_count`
- `local_ip`
- `local_base_url`
- `remote_base_url`
- `updated_at`

### `GET /time`

- purpose: server time helper
- auth: none
- response: map containing server time fields

### `GET /templates`

- purpose: return recommended template items
- auth: none
- Android uses it during pairing
- response:
  - `templates: [...]`

### `POST /pair`

- purpose: pair a phone to the Pi
- auth: none
- request body:

```json
{
  "phone_device_id": "phone-123",
  "pair_code": "123456"
}
```

- response body:

```json
{
  "auth_token": "token",
  "pi_device_id": "pi-id",
  "server_time_ms": 0
}
```

### `POST /sync`

- purpose: synchronize changed bags and items
- auth: bearer token required
- request body fields:
  - `phone_device_id`
  - `last_sync_at`
  - `changed_bags`
  - `changed_items`

- response body fields:
  - `server_time_ms`
  - `server_bag_changes`
  - `server_item_changes`
  - `conflicts`
  - `auto_resolved`
  - `alerts`

## Pi and admin CRUD/status endpoints

These exist in code, but Android does not use them as its primary inventory read path.

### `GET /categories`

- purpose: list category records
- auth: none
- response: list of
  - `id`
  - `name`
  - `icon_or_label`

### `GET /bags`

- purpose: list Pi-side bag records
- auth: none
- response fields per bag:
  - `id`
  - `name`
  - `bag_type`
  - `readiness_status`
  - `last_checked_at`
  - `created_at`
  - `updated_at`

### `POST /bags`

- purpose: create a Pi-side bag record
- auth: none
- request body:
  - `name`
  - `bag_type`

### `PUT /bags/{bag_id}`

- purpose: update a Pi-side bag record
- auth: none
- request body:
  - `name`
  - `bag_type`
  - `last_checked_at`

### `DELETE /bags/{bag_id}`

- purpose: delete a bag and its items
- auth: none

### `GET /bags/{bag_id}/items`

- purpose: list Pi-side items for a bag
- auth: none
- response fields per item:
  - `id`
  - `bag_id`
  - `category_id`
  - `name`
  - `quantity`
  - `unit`
  - `packed_status`
  - `essential`
  - `expiry_date`
  - `minimum_quantity`
  - `condition_status`
  - `notes`
  - `created_at`
  - `updated_at`

### `POST /bags/{bag_id}/items`

- purpose: create a Pi-side item
- auth: none
- request body:
  - `category_id`
  - `name`
  - `quantity`
  - `unit`
  - `packed_status`
  - `essential`
  - `expiry_date`
  - `minimum_quantity`
  - `condition_status`
  - `notes`

### `PUT /items/{item_id}`

- purpose: update a Pi-side item
- auth: none
- request body: same shape as item create

### `DELETE /items/{item_id}`

- purpose: delete a Pi-side item
- auth: none

### `GET /alerts`

- purpose: list current expiry alerts
- auth: none
- response fields:
  - `bag_id`
  - `item_id`
  - `type`
  - `days_left`

### `GET /settings`

- purpose: return Pi-side settings record
- auth: none
- response fields:
  - `id`
  - `default_bag_id`
  - `language`
  - `notifications_enabled`
  - `last_connected_device`

## Operational endpoints

### `GET /`

- purpose: HTML dashboard with readiness, bags, alerts, pair QR, and admin actions
- auth: none for dashboard view

### `GET /kiosk/state`

- purpose: JSON summary for kiosk or local display consumption
- auth: none

### `GET /system/info`

- purpose: system diagnostics
- auth: none

### `GET /camera/status`

- purpose: camera command and config diagnostics
- auth: none

### `GET /camera/capture.jpg`

- purpose: capture image from Pi camera if enabled
- auth: none

## Admin endpoints

### `POST /admin/new_pair_code`

- purpose: rotate and return a new active pair code
- auth: admin token if configured

### `POST /admin/revoke_tokens`

- purpose: revoke all paired phone tokens
- auth: admin token if configured

## Important contract note

Two API shapes coexist on purpose:

- sync DTO shape used by Android
- CRUD/status shape used by Pi/admin and operational surfaces

Do not merge or repurpose them casually without updating both Android and backend code together.
