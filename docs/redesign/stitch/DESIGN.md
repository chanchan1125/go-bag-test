# GO BAG: Tactical Readiness Design System (Signal Protocol)

This document serves as the technical specification for implementing the GO BAG emergency inventory UI across Android (Mobile) and Raspberry Pi (Kiosk) platforms.

## 1. Design Philosophy: "The Digital Swiss Tool"
*   **Offline-First:** UI should feel immediate, local, and reliable.
*   **High Stress/High Contrast:** Maximum legibility is prioritized over decorative elements.
*   **Contextual Sizing:** 
    *   **Mobile:** Dense information for expert users.
    *   **Pi Kiosk:** Massive touch targets (min 64px height) for low-dexterity/high-stress scenarios.

---

## 2. Visual Foundation

### 2.1 Color Palette
#### Dark Mode (Signal Protocol Dark)
| Token | HEX | Usage |
| :--- | :--- | :--- |
| `surface-primary` | `#131314` | Main background |
| `surface-secondary`| `#1B1B1C` | Cards and secondary containers |
| `brand-primary` | `#FF6B00` | Primary actions, key readiness indicators |
| `brand-accent` | `#FFB693` | Soft highlights, secondary buttons |
| `status-critical` | `#B3261E` | Expired items, critical errors |
| `status-warning` | `#FF6B00` | Missing items, low power |
| `status-success` | `#2ECC71` | Checked items, sync complete |

#### Light Mode (Signal Protocol Light)
| Token | HEX | Usage |
| :--- | :--- | :--- |
| `surface-primary` | `#F8F9FA` | Main background |
| `surface-secondary`| `#FFFFFF` | Cards and elevated elements |
| `text-primary` | `#191C1D` | High-contrast body text |
| `brand-primary` | `#FF6B00` | Consistency across modes |

### 2.2 Typography
*   **Primary Font:** `Space Grotesk` (Headlines, Stats, Status)
    *   *Rationale:* Monospaced feel with high character distinction.
*   **Secondary Font:** `Inter` (Body, Labels, Form Inputs)
*   **Scale:**
    *   `Display L`: 48px Space Grotesk Bold (Readiness Score)
    *   `Headline M`: 24px Space Grotesk Bold (Page Titles)
    *   `Label S`: 10px Inter Bold Uppercase (Tactical Metadata, 2px tracking)

---

## 3. Spacing & Layout

### 3.1 Mobile (Android)
*   **Grid:** 4-column fluid layout.
*   **Margins:** 16px horizontal, 24px vertical.
*   **Gutter:** 12px between cards.
*   **Touch Targets:** Minimum 48x48px.

### 3.2 Raspberry Pi (Kiosk)
*   **Grid:** 12-column landscape layout.
*   **Margins:** 24px global.
*   **Touch Targets:** Minimum 64x64px.
*   **Padding:** Card internal padding 20px min.

---

## 4. Components & Interaction Rules

### 4.1 Cards
*   **States:**
    *   `Default`: 1px border or subtle shadow.
    *   `Active/Pressed`: 2px brand-primary border or slight scale down (98%).
    *   `Error`: Left border accent in `#B3261E`.
*   **Corner Radius:** 4px (Tactical/Rugged feel).

### 4.2 Buttons
*   **Primary:** Filled `#FF6B00` with white text. High-contrast icon.
*   **Secondary:** Outlined or subtle background grey.
*   **Interaction:** 150ms ease-in-out transition on hover/tap.

### 4.3 Data Viz (Readiness Circle/Bar)
*   **Logic:**
    *   0-50%: Red (`#B3261E`)
    *   51-89%: Orange (`#FF6B00`)
    *   90-100%: Green (`#2ECC71`)

---

## 5. Screen Inventory Mapping
*   **Dashboard:** {{DATA:SCREEN:SCREEN_12}} (Dark) / {{DATA:SCREEN:SCREEN_16}} (Light)
*   **Inventory:** {{DATA:SCREEN:SCREEN_3}} (Dark) / {{DATA:SCREEN:SCREEN_15}} (Light)
*   **Check Mode:** {{DATA:SCREEN:SCREEN_17}} (Dark) / {{DATA:SCREEN:SCREEN_14}} (Light)
*   **Pi Hub:** {{DATA:SCREEN:SCREEN_11}} (Dark) / {{DATA:SCREEN:SCREEN_20}} (Light)
*   **Full-Screen Scan:** {{DATA:SCREEN:SCREEN_18}} (Dark) / {{DATA:SCREEN:SCREEN_22}} (Light)

---

## 6. Development Notes
*   **Icons:** Material Symbols (Rounded)
*   **Elevation:** Avoid heavy blurs; use high-contrast borders and simple opacity layers to maintain performance on low-power Pi hardware.
*   **Dark Mode Support:** All components must use the defined semantic tokens for seamless switching.