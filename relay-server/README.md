# GO BAG Relay Server

`relay-server/` is the small internet-facing relay for remote status and sync when the phone and Raspberry Pi are on different networks.

It is intentionally limited to:

- remote `GET /device/status`
- remote `GET /sync/status`
- remote `POST /sync`
- only for phones that are already paired with the Pi

The relay never performs pairing and it does not become the source of truth for inventory data. The Raspberry Pi still validates the phone bearer token and still runs the actual sync logic.

## Railway Hobby deployment

Use this folder as the Railway service root.

Start command:

```bash
uvicorn main:app --host 0.0.0.0 --port $PORT
```

Recommended Railway variables:

```env
GOBAG_RELAY_BOOTSTRAP_SECRET=change-me
GOBAG_RELAY_DATA_DIR=/data
```

Attach a volume and mount it at `/data`.

## Pi-side variables

Each Raspberry Pi that connects to the relay needs:

```env
GOBAG_RELAY_URL=https://your-relay.up.railway.app
GOBAG_RELAY_BOOTSTRAP_SECRET=change-me
GOBAG_RELAY_DEVICE_SECRET=unique-secret-per-pi
```

## Android-side variable

For builds that should be able to derive the relay path automatically:

```env
GOBAG_RELAY_BASE_URL=https://your-relay.up.railway.app
```
