```markdown
# Design System Documentation: Tactical Readiness Framework

## 1. Overview & Creative North Star
### The Creative North Star: "The Digital Swiss Tool"
This design system moves beyond the standard "emergency app" aesthetic to create a high-end, editorial-grade utility. Our mission is to balance **rugged reliability** with **sophisticated precision**. We reject the "safety-yellow-and-caution-tape" cliché in favor of a UI that feels like a premium tactical instrument—calm under pressure, authoritative in its data delivery, and meticulously organized.

To break the "template" look, this system utilizes **Intentional Asymmetry**. Large `display-lg` readiness percentages are offset against tight, technical `label-sm` metadata. We utilize overlapping elements and "broken" grids where status indicators bleed into the margins, creating a sense of a living, breathing machine rather than a static webpage.

---

## 2. Colors & Surface Philosophy
The palette is rooted in a deep, nocturnal charcoal to ensure maximum legibility and reduced eye strain during low-light emergency scenarios.

### The "No-Line" Rule
**Prohibit 1px solid borders for sectioning.** Conventional lines create visual noise and "box in" critical information. Boundaries must be defined solely through background color shifts. Use `surface-container-low` for secondary sections and `surface-container-high` for interactive elements.

### Surface Hierarchy & Nesting
Treat the UI as a series of physical, stacked layers. 
*   **Base:** `surface` (#131314)
*   **Sectioning:** `surface-container-low` (#1B1B1C)
*   **Actionable Cards:** `surface-container-high` (#2A2A2B)
*   **Floating Modals:** `surface-bright` (#39393A) with a `backdrop-blur` of 20px.

### The "Glass & Gradient" Rule
For primary CTAs (e.g., "DEPLOY GO-BAG"), avoid flat fills. Use a subtle linear gradient from `primary` (#FFB693) to `primary_container` (#FF6B00). For status overlays, use semi-transparent surface colors with **Glassmorphism** (Background Blur: 16px) to allow the "rugged" background textures to peak through, providing depth without clutter.

---

## 3. Typography
We use a dual-typeface system to create an "Editorial-Technical" hybrid.

*   **Display & Headlines:** `Space Grotesk`. Its idiosyncratic terminals and monospaced-leaning widths provide a technical, high-end feel. 
    *   *Usage:* Use `display-lg` for the "Readiness Percentage" to make it the undisputed hero of the screen.
*   **Body & UI Labels:** `Inter`. A workhorse for legibility. 
    *   *Usage:* All critical instructions and inventory lists must use `body-md`. 
*   **Hierarchy as Identity:** By pairing a massive `display-md` headline with a tiny, uppercase `label-md` "LAST SYNCED" timestamp, we create a professional, data-dense aesthetic that feels curated, not cluttered.

---

## 4. Elevation & Depth
In an offline-first, rugged environment, "shadows" represent physical depth.

*   **The Layering Principle:** Depth is achieved via **Tonal Stacking**. An item on `surface-container-highest` naturally feels "closer" to the user than one on `surface-dim`.
*   **Ambient Shadows:** For floating action buttons or critical alerts, use a "Tinted Glow." Instead of a black shadow, use a 12% opacity shadow of the `primary` color (#FF6B00) with a 24px blur. This mimics the light of a tactical flashlight or screen glow.
*   **The "Ghost Border" Fallback:** If a boundary is strictly required for accessibility, use `outline_variant` (#5A4136) at 15% opacity. This creates a "hairline" feel that suggests structure without creating a "boxed" layout.

---

## 5. Components

### Modern Cards (The Information Block)
*   **Styling:** No borders. Use `surface-container-high`.
*   **Rules:** Forbid divider lines. Separate "Inventory Item" from "Quantity" using horizontal `spacing-8`. Use vertical `spacing-4` to separate groups.
*   **Interaction:** Active cards should transition to `surface-bright` on tap/hover.

### Tactical Buttons
*   **Primary:** Gradient fill (`primary` to `primary_container`). 0.5rem (lg) roundedness. Height: 56px (exceeding the 44px minimum for gloved/shaking hands).
*   **Secondary/Tertiary:** `surface-container-highest` fill with `on-surface` text. No border.

### Status Badges (The "Alert Level" System)
*   **High Alert:** `error_container` fill with `on_error_container` text.
*   **Med Alert:** `primary_container` fill with `on_primary_container` text.
*   **Low Alert/Ready:** `tertiary_container` fill with `on_tertiary_container` text.

### Connection & Sync Tokens
*   **Synced:** `secondary` (#8CCDFF) small dot + `label-sm` "OFFLINE-READY".
*   **Syncing:** Pulsing opacity (100% to 40%) on `secondary_fixed_dim`.

### Input Fields
*   **Surface:** `surface_container_lowest`. 
*   **Focus State:** A 2px "Ghost Border" using `primary` at 40% opacity. No solid heavy borders.

---

## 6. Do's and Don'ts

### Do
*   **Do** use `spacing-12` and `spacing-16` (large gaps) to create a sense of calm and breathing room between high-stress data points.
*   **Do** utilize `surface-bright` for elements that need to feel "closer" to the thumb.
*   **Do** prioritize the 44px touch target on all interactive elements—safety is the priority.

### Don't
*   **Don't** use pure black (#000000). Use `surface` (#131314) to maintain tonal depth and "soul."
*   **Don't** use 1px solid borders to separate list items. Use the `spacing-px` or a background shift to `surface-container-low`.
*   **Don't** use generic system icons. Use thick-stroke (2pt), rounded-cap icons that match the "rugged" personality of the system.
*   **Don't** crowd the screen. In an emergency, cognitive load is high; if a piece of data isn't vital, move it to a nested `surface-container`.

---

## 7. Contextual Tokens

| Token | Value/Visual | Purpose |
| :--- | :--- | :--- |
| **Readiness** | `display-lg` / `tertiary` | Large % indicator of bag completion. |
| **Alert: High** | `error_container` | Imminent danger / missing critical item (Water/Med). |
| **Alert: Med** | `primary_container` | Expiring items / maintenance needed. |
| **Connection** | `secondary` | Confirmation of "Offline-First" data integrity. |
| **Rugged Radius**| `rounded-lg` (0.5rem) | Standard corner radius for a "machined" feel. |```