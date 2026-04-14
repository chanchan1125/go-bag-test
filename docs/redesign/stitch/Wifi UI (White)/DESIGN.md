# Design System Specification: Tactical Clarity (Light Mode)

## 1. Overview & Creative North Star
### Creative North Star: "The Clinical Vanguard"
In emergency and tactical environments, clarity is not a preference—it is a requirement. This design system departs from generic "SaaS-lite" aesthetics to embrace **Clinical Vanguard**: an editorial, high-precision visual language that prioritizes immediate data recognition. 

We break the "template" look by utilizing **intentional asymmetry** and **high-contrast typographic scales**. By shifting away from rigid 1px borders and moving toward tonal layering and stark, geometric typography, we create a UI that feels like a high-end physical instrument. It is serious, authoritative, and optimized for high-glare, high-stress daylight operations.

---

## 2. Colors & Surface Logic
The palette is anchored in `#F8F9FA`, providing a neutral, glare-reducing canvas that allows the safety orange (`#FF6B00`) to act as a high-intensity signal.

### The "No-Line" Rule
To achieve a premium, custom feel, **prohibit 1px solid borders for sectioning.** Boundaries must be defined solely through background color shifts. For example, a `surface-container-low` section sitting on a `surface` background creates a clear, sophisticated separation without the "cheapness" of stroke-based grids.

### Surface Hierarchy & Nesting
Treat the UI as a series of physical layers—stacked sheets of fine, technical paper.
- **Base Level:** `surface` (#F8F9FA)
- **De-emphasized Areas:** `surface-container-low` (#F3F4F5)
- **Interactive/Primary Cards:** `surface-container-lowest` (#FFFFFF)
- **Elevated/Tactical Overlays:** `surface-container-highest` (#E1E3E4)

### The "Glass & Signature Texture" Rule
Use **Glassmorphism** for floating tactical elements (e.g., status bars, quick-action FABs). Apply `surface` colors at 80% opacity with a `20px` backdrop blur. 
- **Signature Gradient:** For primary CTAs, use a subtle vertical gradient from `primary-container` (#FF6B00) to `primary` (#A04100). This adds a "machined" depth that flat hex codes cannot replicate.

---

## 3. Typography: The Editorial Engine
We pair the brutalist geometry of **Space Grotesk** for data/headers with the utilitarian neutrality of **Inter** for instructional text.

*   **Display (Space Grotesk):** Use `display-lg` (3.5rem) with tight letter-spacing (-0.02em) for critical alerts. The massive scale creates an undeniable hierarchy.
*   **Headline (Space Grotesk):** `headline-md` (1.75rem) should be Medium or Bold weight to ground the section.
*   **Body (Inter):** `body-lg` (1rem) for readability. On light backgrounds, use a slightly heavier weight (Medium 500) than you would in dark mode to ensure the "ink" doesn't wash out in sunlight.
*   **Labels (Inter):** `label-md` (0.75rem) in All-Caps with +0.05em tracking for technical metadata and timestamps.

---

## 4. Elevation & Depth
Depth is conveyed through **Tonal Layering** rather than traditional structural shadows.

*   **The Layering Principle:** Place a `surface-container-lowest` (#FFFFFF) card on a `surface-container-low` (#F3F4F5) background. The delta in luminance creates a soft, natural "lift."
*   **Ambient Shadows:** For floating modals, use an extra-diffused shadow: `0 12px 40px rgba(25, 28, 29, 0.06)`. This mimics natural ambient light.
*   **The Ghost Border:** If a container requires a border for accessibility (e.g., in extreme glare), use `outline-variant` (#E2BFB0) at **15% opacity**. Never use a 100% opaque border.

---

## 5. Components

### Buttons & Inputs
*   **Primary Button:** `primary-container` (#FF6B00) background with `on-primary-container` (#572000) text. Use `md` (0.375rem) roundedness for a "rugged" feel. 
*   **Secondary Button:** `surface-container-high` background. No border.
*   **Input Fields:** Use `surface-container-low` as the fill. Upon focus, transition the background to `surface-container-lowest` and apply a 2px bottom-only stroke in `primary`.

### Cards & Tactical Lists
*   **Forbid Divider Lines:** Separate list items using `8px` (spacing-2) of vertical white space or alternating backgrounds (`surface` to `surface-container-low`).
*   **Tactical Chips:** Use `secondary-container` (#FE9A69) with `on-secondary-container` (#763006). These should be `sm` roundedness to maintain the technical, geometric look.

### Emergency HUD (Special Component)
A floating, glassmorphic container at the bottom of the viewport using `surface-bright` at 85% opacity. This houses the most critical "Signal" actions, ensuring they are reachable and high-contrast against any background content.

---

## 6. Do’s and Don’ts

### Do:
*   **DO** use `primary` (#A04100) for text-based links to ensure WCAG 2.1 AAA contrast on light surfaces.
*   **DO** embrace white space. A tactical UI is not a "cluttered" UI; use `spacing-12` (3rem) to separate major functional blocks.
*   **DO** use `tertiary` (#0062A1) for informational/passive data points to distinguish them from the "Action" orange.

### Don’t:
*   **DON’T** use pure black (#000000) for text. Use `on-surface` (#191C1D) to maintain a premium, editorial feel and reduce eye strain.
*   **DON’T** use standard Material shadows. They feel like "templates." Stick to tonal shifts or high-diffusion ambient shadows.
*   **DON’T** use fully rounded (pill) buttons except for Chips. Stay with `md` or `lg` corners to maintain the "Tactical Readiness" vibe.