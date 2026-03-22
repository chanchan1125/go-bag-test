# UI/UX Review

## Overall assessment

The current UI is usable, especially on Android, but it is still closer to a functional prototype than a polished emergency-use product. The strongest qualities are simple navigation, large tap targets, and clear main actions. The main weaknesses are operational polish, alert clarity, and the lack of a real Pi kiosk experience.

## Android app

### What works well

- Large buttons and card-based actions are touch friendly.
- The home screen exposes the main jobs clearly: inventory, check mode, sync, pairing.
- Inventory and check mode are straightforward and readable.
- Conflict actions are visible and explicit.
- The app is local-first, which is appropriate for unreliable connectivity.

### Issues

- There are no visible progress indicators beyond button-label changes, so long-running network operations may feel stalled.
- Conflict resolution copy is functional but still technical. In an emergency-use context, "Keep phone", "Keep Pi", "Keep deleted", and "Restore" may need more context.
- Sync and inventory success messages repeatedly say "Raspberry Pi local update completed," which reads awkwardly and may confuse non-technical users.
- Android alert notifications identify bags by `bag_id`, not by human bag name.
- Notification permission handling is still incomplete UX-wise. The code now avoids unsafe posting without permission, but the app does not request that permission from the user.

### Touch/readability verdict

- Readability: Good
- Touch targets: Good
- Navigation clarity: Good
- Error feedback: Fair
- Loading states: Fair
- Emergency-use suitability: Fair to good

## Raspberry Pi dashboard

### What works well

- The dashboard is legible and summary-focused.
- Pairing QR and next steps are prominent.
- Readiness, bag count, and alert sections are easy to scan.
- The palette and layout are more intentional than a bare admin page.

### Issues

- The dashboard is a browser page, not a dedicated touchscreen shell.
- No code exists to boot directly into the dashboard on the Pi.
- No explicit touchscreen affordances or kiosk controls are implemented.
- Long pages on smaller displays may require scrolling and could be awkward in a wall-mounted or always-on context.
- Admin actions like pair-code rotation and token revocation are exposed as plain buttons with no auth guard.

### Emergency-use verdict

- Dashboard clarity: Good
- Touchscreen readiness: Partial
- Low-cognitive-load design: Decent
- Appliance feel: Not there yet

## Concrete recommendations

- Add a kiosk/autostart layer for Chromium on Raspberry Pi OS desktop.
- Add explicit loading indicators for pair, sync, and QR operations.
- Replace bag-id-based notification titles with human bag names when available.
- Add a permission request flow for Android notifications.
- Simplify conflict copy for non-technical users.
- Consider a lighter, daylight-friendly Android theme variant for outdoor or stressful environments.
