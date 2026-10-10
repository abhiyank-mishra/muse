# Google UI & Echo Music Design System (Web Architecture Specification)

> **File Name:** `google-ui.md`  
> **Source Project:** Echo Music / EMUSE (Modern Material You + Nothing OS 5.0 + Apple Music Liquid Glass)  
> **Target Environment:** Web Applications (HTML5, Modern CSS / Tailwind CSS, JavaScript / TypeScript / React / Next.js)  
> **Primary Purpose:** Definitive design tokens, component anatomy, spacing & pixel metrics, physics/motion curves, modal sheet mechanics, and production-ready web implementation rules.

---

## 1. Design Philosophy & Aesthetic Identity

This design system is a hybrid of three modern design languages:
1. **Material Design 3 (Expressive & Dynamic):** Dynamic tonal color generation (`PaletteStyle.TonalSpot`), large friendly touch targets, pill badges, and contextual semantic color roles.
2. **Nothing OS 5.0 (Clean, Stark, Minimal):** High-contrast typography, monochrome-first accents, strict borderless or subtle-bordered containers, grouped list cards, flat 0dp elevations, and bold uppercase section labels.
3. **Apple Music Liquid Glass (Glassmorphism):** Multi-layered backdrop blur (`16px` to `60px`), `1.5x` color vibrancy/saturation booster, subtle specular highlight rims (`0.5px`), soft ambient drop shadows, and floating standalone controls.

---

## 2. Global Design Tokens & Measurements (Pixels & REM)

All web layouts are anchored to an **8px base grid** with a **4px sub-grid**.

### 2.1 Spacing & Layout Metrics

| Token Name | DP / PX | REM (16px base) | Web Usage & Context |
| :--- | :--- | :--- | :--- |
| `--space-2xs` | `2px` | `0.125rem` | Inter-card gap inside grouped list cards, badge padding |
| `--space-xs` | `4px` | `0.25rem` | Micro spacers, corner radius for middle list items |
| `--space-sm` | `8px` | `0.5rem` | Component spacing in tab bar, gap between icons & text |
| `--space-md` | `12px` | `0.75rem` | Section header padding, horizontal tab item padding |
| `--space-lg` | `16px` | `1rem` | Screen edge padding, modal sheet horizontal padding |
| `--space-xl` | `20px` | `1.25rem` | Expanded tab horizontal padding, standard card padding |
| `--space-2xl` | `24px` | `1.5rem` | Player skip button spacer, dialog edge padding |
| `--space-3xl` | `32px` | `2rem` | Full Player horizontal padding, sheet bottom padding |

### 2.2 Component Heights & Dimensions

| Component | Height / Width | Tokens & Web Rules |
| :--- | :--- | :--- |
| **Top App Bar** | `64px` (`4rem`) | Fixed height, sticky top, with `env(safe-area-inset-top)` offset |
| **Floating Navigation Bar** | `72px` (`4.5rem`) | Max width `480px`, floats `12px` above screen bottom |
| **Floating MiniPlayer** | `64px` (`4rem`) | Max width `340px` (mobile) / `480px` (desktop), floats `8px` above Nav Bar |
| **Queue Peek Bar** | `64px` (`4rem`) | Collapsed bottom sheet handle & queue header |
| **Standard List Item** | `64px` (`4rem`) | Songs, Artists, Playlists row height |
| **Suggestion Item** | `56px` (`3.5rem`) | Search autocomplete items |
| **Search Filter Bar** | `48px` (`3rem`) | Search category toggle strip |
| **List Thumbnail** | `48px × 48px` | Square thumbnail with `6px` or `8px` rounded corners |
| **MiniPlayer Artwork** | `48px × 48px` | Outer SVG container (`48px`) with inner circle image (`40px`) |
| **Small Grid Artwork** | `104px × 104px` | Compact album grid cards |
| **Standard Grid Artwork**| `128px × 128px` | Default album / artist grid card |
| **Featured Album Art** | `144px × 144px` | Highlighted album cards |
| **Hero Carousel Card** | `320px × 420px` | Community / Daily discover featured playlist mosaic |
| **Main Play/Pause Button**| `84px × 84px` | Large floating control with 9-lobe wavy shape |
| **Skip Previous/Next** | `68px × 68px` | Circular action buttons in full player |

### 2.3 Corner Radius System (Radii)

```css
:root {
  --radius-xs: 4px;       /* Middle items in grouped cards, codec chips */
  --radius-sm: 6px;       /* Song list thumbnails */
  --radius-md: 12px;      /* Filter chips, small buttons, icons container */
  --radius-lg: 16px;      /* Bottom sheet top corners (collapsed/half) */
  --radius-xl: 24px;      /* Grouped card edges, main container cards */
  --radius-2xl: 28px;     /* Search bars, hero cards */
  --radius-full: 9999px;   /* Pills: TabBar, MiniPlayer, Action buttons, Avatars */
}
```

---

## 3. Color Palette & Theming (CSS Variables)

The design system supports **Dark Mode**, **Pure Black (AMOLED)**, and **Light Mode**. Dynamic color adapts to album artwork using primary, surface, and surface variant tones.

```css
/* Dark Mode (Default) */
:root[data-theme="dark"], :root {
  --color-primary: #ED5564;
  --color-on-primary: #FFFFFF;
  --color-primary-container: rgba(237, 85, 100, 0.2);
  --color-on-primary-container: #FFDADB;

  --color-surface: #121212;
  --color-on-surface: #E6E1E5;
  --color-surface-variant: rgba(255, 255, 255, 0.08);
  --color-on-surface-variant: #CAC4D0;

  --color-surface-container: #1E1E1E;
  --color-surface-container-high: #282828;
  --color-surface-container-highest: #333333;

  --color-outline: rgba(255, 255, 255, 0.12);
  --color-outline-variant: rgba(255, 255, 255, 0.06);

  --color-glass-tint: rgba(18, 18, 18, 0.65);
  --color-glass-border: rgba(255, 255, 255, 0.15);
  --color-glass-shadow: rgba(0, 0, 0, 0.35);
}

/* Pure Black Mode (Nothing OS / AMOLED) */
:root[data-theme="pure-black"] {
  --color-surface: #000000;
  --color-surface-container: #0C0C0C;
  --color-surface-container-high: #141414;
  --color-surface-container-highest: #1F1F1F;
  --color-glass-tint: rgba(0, 0, 0, 0.75);
  --color-outline: rgba(255, 255, 255, 0.16);
}

/* Light Mode */
:root[data-theme="light"] {
  --color-primary: #ED5564;
  --color-on-primary: #FFFFFF;
  --color-surface: #FAFAFA;
  --color-on-surface: #1C1B1F;
  --color-surface-variant: rgba(0, 0, 0, 0.05);
  --color-on-surface-variant: #49454F;

  --color-surface-container: #F3F3F3;
  --color-surface-container-high: #ECECEC;
  --color-surface-container-highest: #E0E0E0;

  --color-outline: rgba(0, 0, 0, 0.12);
  --color-outline-variant: rgba(0, 0, 0, 0.06);

  --color-glass-tint: rgba(250, 250, 250, 0.7);
  --color-glass-border: rgba(255, 255, 255, 0.6);
  --color-glass-shadow: rgba(0, 0, 0, 0.08);
}
```

---

## 4. Typography Hierarchy (Google Sans / Outfit / Inter)

Modern web typography specification derived from `Type.kt`:

| Style Name | Font Size | Line Height | Weight | Letter Spacing | Web Class / Tag |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Display Large** | `57px` (`3.56rem`) | `64px` | Normal (400) | `-0.25px` | Hero Banners |
| **Display Small** | `36px` (`2.25rem`) | `44px` | SemiBold (600) | `0px` | "Settings" Screen Title |
| **Headline Medium**| `28px` (`1.75rem`) | `36px` | Bold (700) | `0px` | Album / Playlist Big Title |
| **Headline Small** | `24px` (`1.5rem`) | `32px` | Normal (400) | `0px` | Dialog Headers |
| **Title Large** | `22px` (`1.375rem`)| `28px` | Bold (700) | `0px` | Now Playing Song Title |
| **Title Medium** | `16px` (`1rem`) | `24px` | Medium (500) | `+0.15px` | Grouped Card Item Title |
| **Body Large** | `16px` (`1rem`) | `24px` | Normal (400) | `+0.5px` | Standard Content Body |
| **Body Medium** | `14px` (`0.875rem`)| `20px` | Normal (400) | `+0.25px` | Song Subtitle / Artist |
| **Body Small** | `12px` (`0.75rem`) | `16px` | Normal (400) | `+0.4px` | Secondary Footers & Meta |
| **Label Large** | `14px` (`0.875rem`)| `20px` | Bold (700) | `+0.1px` | Section Titles (`uppercase`) |
| **Label Medium** | `12px` (`0.75rem`) | `16px` | Medium (500) | `+0.5px` | Duration & Time Labels |
| **Label Small** | `10px` (`0.625rem`)| `14px` | Bold (700) | `+1.0px` | Codec Chips (`OPUS • 160 kbps`) |

---

## 5. Signature UI Pattern: Grouped List Cards (Nothing OS Style)

Instead of individual floating card pills, items within a category are grouped into a continuous unified card block with an inner **2px gap** and contextual corner radii.

### Visual Architecture:
- **Group Title:** `font-size: 14px`, `text-transform: uppercase`, `font-weight: 700`, `color: var(--color-on-surface-variant)`, `padding: 8px 0 8px 8px`.
- **Inter-Item Gap:** `2px` (`0.125rem`).
- **Corner Radii Rules:**
  - **Single item:** `24px 24px 24px 24px`
  - **First item in group:** `24px 24px 4px 4px`
  - **Middle item(s):** `4px 4px 4px 4px`
  - **Last item in group:** `4px 4px 24px 24px`
- **Container Color:** `var(--color-surface-container-high)`.
- **Elevation:** Flat `0px` (No drop shadow).
- **Padding per Row:** `16px horizontal, 14px vertical` (or `14px horizontal, 10px vertical` for compact).

### HTML & Pure CSS Implementation:

```html
<div class="settings-group">
  <div class="group-title">Theme</div>
  <div class="grouped-card-container">
    <div class="grouped-item">
      <div class="item-icon-box">
        <svg class="icon">...</svg>
      </div>
      <div class="item-text">
        <div class="item-title">App Icon</div>
        <div class="item-subtitle">Choose your launcher icon</div>
      </div>
    </div>
    <div class="grouped-item">
      <div class="item-icon-box">
        <svg class="icon">...</svg>
      </div>
      <div class="item-text">
        <div class="item-title">App Font</div>
      </div>
      <div class="item-trailing">System Default</div>
    </div>
    <div class="grouped-item">
      <div class="item-icon-box">
        <svg class="icon">...</svg>
      </div>
      <div class="item-text">
        <div class="item-title">High Refresh Rate</div>
        <div class="item-subtitle">Force display to run at 120Hz</div>
      </div>
      <div class="item-trailing">
        <input type="checkbox" class="toggle-switch" checked />
      </div>
    </div>
  </div>
</div>
```

```css
.settings-group {
  width: 100%;
  margin-bottom: 20px;
}

.group-title {
  font-size: 12px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--color-on-surface-variant);
  padding: 6px 0 8px 8px;
}

.grouped-card-container {
  display: flex;
  flex-direction: column;
  gap: 2px; /* Strict 2px gap */
  width: 100%;
}

.grouped-item {
  display: flex;
  align-items: center;
  padding: 14px 18px;
  background-color: var(--color-surface-container-high);
  cursor: pointer;
  transition: background-color 0.15s ease, transform 0.1s ease;
  user-select: none;
}

.grouped-item:hover {
  background-color: var(--color-surface-container-highest);
}

.grouped-item:active {
  transform: scale(0.99);
}

/* Contextual Radius Automation */
.grouped-item:first-child:last-child {
  border-radius: 24px;
}

.grouped-item:first-child:not(:last-child) {
  border-radius: 24px 24px 4px 4px;
}

.grouped-item:not(:first-child):not(:last-child) {
  border-radius: 4px;
}

.grouped-item:last-child:not(:first-child) {
  border-radius: 4px 4px 24px 24px;
}

.item-icon-box {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background-color: rgba(255, 255, 255, 0.05);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 16px;
  flex-shrink: 0;
}

.item-text {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.item-title {
  font-size: 15px;
  font-weight: 500;
  color: var(--color-on-surface);
}

.item-subtitle {
  font-size: 13px;
  color: var(--color-on-surface-variant);
}

.item-trailing {
  margin-left: 12px;
  font-size: 13px;
  color: var(--color-on-surface-variant);
}
```

---

## 6. How Settings Opens: Architecture, Flow & Lifecycle

In this design, settings is not just a standard page link. It uses a **Two-Tier Layered Transition**:

```
[Main Home / Library Screen]
             │
             ▼ (Click Profile Avatar on Top App Bar)
   Tier 1: Modal Bottom Sheet (`SettingDialoge`)
             │  • Slide-up with 0.54s spring easing
             │  • Scrim dimming background
             │  • Drag handle for swipe-to-dismiss
             │  • Shows Account Profile, AI lyrics switch, App settings link
             │
             ▼ (Click "Settings" item inside Sheet)
   Tier 2: Full Settings Screen (`SettingsScreen`)
                • Modal sheet smoothly dismisses
                • Navigates to `/settings` route with horizontal slide
                • Features live search bar & 11 category grouped cards
```

### 6.1 Tier 1: Modal Bottom Sheet Lifecycle & Specs
1. **Trigger:** Tapping the Profile photo (`24px` circle with badge) at the top right of `TopAppBar`.
2. **Sheet Surface:**
   - Width: `100%`, Max-width: `500px` (centered on desktop).
   - Border radius: `28px 28px 0 0`.
   - Background: `var(--color-surface-container)`.
   - Drag Handle: `32px` width, `4px` height, `border-radius: 9999px`, centered, `margin: 12px auto 8px auto`.
   - Scrim Background: `rgba(0, 0, 0, 0.6)` with backdrop blur `8px`.
3. **Physics Formula (Spring Damping):**
   - Entering: TranslateY `100%` $\to$ `0%` using `cubic-bezier(0.18, 0.89, 0.32, 1.05)` or Spring (`damping: 0.85, stiffness: 350`).
   - Drag-to-dismiss threshold: Drag delta $> 120px$ or flick velocity $> 800px/s$ snaps to close.

### 6.2 Tier 2: Full Settings Screen Transition
- **Page Slide In:** `transform: translateX(100%)` to `translateX(0)` with `350ms cubic-bezier(0.2, 0.0, 0.0, 1.0)`.
- **Search Header:** Persistent search field (`border-radius: 28px`), height `52px`, filters items in real time.
- **Back Navigation:** Circular back button on the top left slides the screen back to previous position.

---

## 7. Floating Navigation Bar & Sliding Pill Indicator

The bottom navigation bar floats above the content and features a dynamic sliding background pill that morphs between tab clicks.

### Exact Geometry:
- **Container Height:** `72px` (`4.5rem`).
- **Max Width:** `480px`.
- **Bottom Float Margin:** `12px` (`0.75rem`) above safe area.
- **Horizontal Screen Padding:** `16px`.
- **Shape:** `border-radius: 9999px` (Pill shape).
- **Background:** Liquid Glass blur `16px` or `var(--color-surface-container-high)`.
- **Outer Border:** `1px solid var(--color-glass-border)`.
- **Sliding Indicator Pill:**
  - Background: `var(--color-surface-container-highest)` (Dark) / `var(--color-primary-container)` (Dynamic).
  - Shape: `border-radius: 24px`.
  - Spring Spec: `spring(dampingRatio: 1.0, stiffness: 400)` (No overshoot, smooth fluid slide).
- **Tab Item Micro-Interactions:**
  - Active Icon scale: `1.12x` with slight bouncy spring.
  - Active Label: Expands horizontally (`width: 0` $\to$ `auto`, `opacity: 0` $\to$ `1`).
  - Active Tab Press Scale: Shrinks to `0.91x` on mousedown / touchstart.

### Web Component (HTML / CSS / JS):

```html
<nav class="floating-nav-bar">
  <div class="sliding-pill" id="navIndicator"></div>
  <button class="nav-tab active" data-index="0">
    <svg class="tab-icon">...</svg>
    <span class="tab-label">Home</span>
  </button>
  <button class="nav-tab" data-index="1">
    <svg class="tab-icon">...</svg>
    <span class="tab-label">Search</span>
  </button>
  <button class="nav-tab" data-index="2">
    <svg class="tab-icon">...</svg>
    <span class="tab-label">Voice</span>
  </button>
  <button class="nav-tab" data-index="3">
    <svg class="tab-icon">...</svg>
    <span class="tab-label">Library</span>
  </button>
  <button class="nav-tab" data-index="4">
    <svg class="tab-icon">...</svg>
    <span class="tab-label">More</span>
  </button>
</nav>
```

```css
.floating-nav-bar {
  position: fixed;
  bottom: calc(12px + env(safe-area-inset-bottom, 0px));
  left: 50%;
  transform: translateX(-50%);
  width: calc(100% - 32px);
  max-width: 480px;
  height: 64px;
  background: var(--color-glass-tint);
  backdrop-filter: blur(20px) saturate(160%);
  -webkit-backdrop-filter: blur(20px) saturate(160%);
  border: 1px solid var(--color-glass-border);
  border-radius: 9999px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.25);
  display: flex;
  align-items: center;
  justify-content: space-around;
  padding: 4px;
  z-index: 1000;
}

.sliding-pill {
  position: absolute;
  top: 4px;
  bottom: 4px;
  background: rgba(255, 255, 255, 0.12);
  border-radius: 24px;
  transition: transform 0.35s cubic-bezier(0.2, 0, 0, 1),
              width 0.35s cubic-bezier(0.2, 0, 0, 1);
  pointer-events: none;
  z-index: 1;
}

.nav-tab {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  border: none;
  background: transparent;
  color: var(--color-on-surface-variant);
  border-radius: 24px;
  cursor: pointer;
  transition: transform 0.15s ease, color 0.2s ease;
}

.nav-tab:active {
  transform: scale(0.91);
}

.nav-tab.active {
  color: var(--color-on-surface);
}

.nav-tab.active .tab-icon {
  transform: scale(1.12);
}

.nav-tab .tab-label {
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
  max-width: 0;
  opacity: 0;
  overflow: hidden;
  transition: max-width 0.3s cubic-bezier(0.2, 0, 0, 1), opacity 0.25s ease;
}

.nav-tab.active .tab-label {
  max-width: 80px;
  opacity: 1;
}
```

```javascript
// Dynamic Sliding Indicator Positioning
function updateNavIndicator() {
  const activeTab = document.querySelector('.nav-tab.active');
  const indicator = document.getElementById('navIndicator');
  if (!activeTab || !indicator) return;

  const tabRect = activeTab.getBoundingClientRect();
  const barRect = activeTab.parentElement.getBoundingClientRect();

  indicator.style.width = `${tabRect.width}px`;
  indicator.style.transform = `translateX(${tabRect.left - barRect.left - 4}px)`;
}
```

---

## 8. Floating MiniPlayer: Circular Progress Thumbnail & Gestures

The MiniPlayer floats immediately above the Navigation Bar and includes an integrated circular progress ring surrounding the song artwork.

### Exact Geometry:
- **Height:** `64px`.
- **Max Width:** `340px` (phone) / `480px` (tablet & desktop).
- **Bottom Offset:** Positioned `8px` above the Floating Nav Bar (total `84px` above bottom).
- **Shape:** `border-radius: 9999px` (Pill).
- **Border:** `1px solid var(--color-glass-border)`.
- **Artwork & Circular Ring:**
  - Total container: `48px × 48px`.
  - Circular Track Stroke: `3px` width, color `rgba(255, 255, 255, 0.2)`.
  - Active Progress Stroke: `3px` width, color `var(--color-primary)`, `stroke-linecap: round`.
  - Artwork circle: `40px × 40px`, centered inside the progress track with `border-radius: 50%`.
- **Title / Artist Column:**
  - Title: `14px`, `font-weight: 600`, single-line text marquee overflow.
  - Artist: `12px`, `color: var(--color-on-surface-variant)`.
- **Controls:** Skip previous (`|◀`), Play/Pause (`▶`), Skip next (`▶|`).
- **Horizontal Swipe Gestures:**
  - Dragging horizontally slides track left/right with spring resistance.
  - Exceeding `50px` threshold or quick swipe triggers Next / Previous song.

### Web Circular Progress Thumbnail (SVG Implementation):

```html
<div class="miniplayer-thumb-box">
  <svg class="progress-ring" width="48" height="48" viewBox="0 0 48 48">
    <!-- Background Track -->
    <circle
      cx="24" cy="24" r="21"
      stroke="rgba(255, 255, 255, 0.2)"
      stroke-width="3"
      fill="transparent"
    />
    <!-- Active Progress Track (-90deg rotated) -->
    <circle
      id="miniPlayerProgress"
      cx="24" cy="24" r="21"
      stroke="var(--color-primary)"
      stroke-width="3"
      stroke-linecap="round"
      fill="transparent"
      stroke-dasharray="131.95"
      stroke-dashoffset="65.97"
      transform="rotate(-90 24 24)"
    />
  </svg>
  <img src="album-cover.jpg" alt="Artwork" class="miniplayer-img" />
</div>
```

```css
.miniplayer-thumb-box {
  position: relative;
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.progress-ring {
  position: absolute;
  top: 0;
  left: 0;
  pointer-events: none;
}

.miniplayer-img {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  object-fit: cover;
  border: 1px solid rgba(255, 255, 255, 0.2);
}
```

---

## 9. Full Screen Player: Aurora Glow Mesh & Wavy Play Button

### 9.1 Animated Glow Mesh (Aurora Background)
In `Player.kt`, the player generates a dynamic multi-orb animated background extracted from the album artwork:
- **6 Radial Orbs** orbit using sine/cosine trigonometry:
  $$x = \text{width} \times \text{oscillate}(0.0, 1.0, \text{phase})$$
  $$y = \text{height} \times \text{oscillate}(0.0, 1.0, \text{phase})$$
- Base layer: `#050505`.
- Loop duration: `20 seconds` (`20000ms`) with `linear` pacing.
- **Web Pure CSS Equivalent:**

```css
.aurora-mesh-background {
  position: absolute;
  inset: 0;
  background: #050505;
  overflow: hidden;
  z-index: 0;
}

.aurora-mesh-background::before,
.aurora-mesh-background::after {
  content: "";
  position: absolute;
  width: 140vw;
  height: 140vh;
  top: -20vh;
  left: -20vw;
  filter: blur(90px);
  opacity: 0.65;
  animation: rotateAurora 22s linear infinite alternate;
}

.aurora-mesh-background::before {
  background: radial-gradient(circle at 30% 30%, var(--color-primary) 0%, transparent 50%),
              radial-gradient(circle at 70% 60%, #4a154b 0%, transparent 60%);
}

.aurora-mesh-background::after {
  background: radial-gradient(circle at 60% 40%, #1e3a8a 0%, transparent 55%),
              radial-gradient(circle at 40% 70%, #d97706 0%, transparent 50%);
  animation-duration: 28s;
  animation-direction: reverse;
}

@keyframes rotateAurora {
  0% { transform: rotate(0deg) scale(1); }
  50% { transform: rotate(180deg) scale(1.15); }
  100% { transform: rotate(360deg) scale(1); }
}
```

### 9.2 The 9-Lobe Wavy / Flower Play-Pause Button
When playing, the `84px` play/pause button transforms into a **9-sided modulated wavy cookie polygon** rotating slowly (`8s` linear infinite loop). When paused, it smoothly animates back into a perfect circle (`300ms`).

```css
/* Wavy Shape CSS Clip-Path / Polygon */
.wavy-play-button {
  width: 84px;
  height: 84px;
  border-radius: 50%;
  background: #FFFFFF;
  color: #000000;
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.35);
  transition: transform 0.2s cubic-bezier(0.34, 1.56, 0.64, 1), border-radius 0.3s ease;
}

.wavy-play-button:active {
  transform: scale(0.90);
}

/* When playing: continuous slow rotation */
.wavy-play-button.playing {
  animation: rotateWavy 8s linear infinite;
  /* SVG Mask or Smooth Polygon clip path can be applied */
}

@keyframes rotateWavy {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
```

---

## 10. Real-Time Synced Lyrics Engine (Web Architecture)

From `LyricsV2.kt` and `MetroLyrics.kt`:
1. **Vertical Alignment:** Active line scrolls smoothly to the vertical center of the container (`scroll-margin: 50vh`).
2. **Inactive Lines:**
   - Opacity: `0.35`
   - Blur: `0.8px` to `1.2px` (creates depth of field)
   - Font: `20px`, Medium weight (`500`).
3. **Active Line:**
   - Opacity: `1.0`
   - Blur: `0px`
   - Scale: `1.04x`
   - Font: `26px`, Bold (`700`), pure white `#FFFFFF`.
   - Text Glow Shadow: `0 0 16px rgba(255, 255, 255, 0.45)`.
4. **Syllable Karaoke Word-by-Word:** Each word transitions its background gradient clip smoothly as timestamp progress reaches that syllable.

---

## 11. Strict Rules for Implementing in Any Web App

Follow these **10 Mandatory UI Rules** when building any web application based on this design system:

### Rule 1: The Grouped Card Law (Never Floating Individual Pills for Settings)
- **DO NOT** render separate, individual card blocks for list items in settings or menus.
- **ALWAYS** wrap items belonging to a section inside a single card group with an internal `2px` gap. The first item must have `24px` top corners, the last item must have `24px` bottom corners, and middle items must have `4px` corners.

### Rule 2: Flat Elevations on Glass (Zero Box-Shadows Inside Frosted Glass)
- **NEVER** apply standard Material 3 elevated drop shadows (`elevation: 4dp`, `box-shadow: 0 4px 12px rgba(...)`) to buttons or menus placed inside a Liquid Glass container.
- Translucent blur layers render drop shadows as dark muddy patches. Keep buttons inside glass completely flat (`box-shadow: none`), using contrast or subtle `border: 0.5px solid rgba(255,255,255,0.2)`.

### Rule 3: The 72px / 64px Spatial Clearance Rule
- The Floating Nav Bar occupies `72px` and floats `12px` above bottom.
- Any scrollable list container (`main`, `section`, `ul`) **MUST** include a bottom padding of at least:
  $$\text{padding-bottom} = 72\text{px} + 64\text{px} (MiniPlayer) + 32\text{px} (\text{safety}) = 168\text{px}$$
- Failure to do this causes bottom list items to be permanently covered by the floating controls.

### Rule 4: Micro-Interaction Press Physics
- Every primary clickable control must have an active tactile scale-down:
  - Navigation tab: `scale(0.91)`
  - Big Play/Pause button: `scale(0.90)`
  - Grouped card row: `scale(0.99)`
  - Action icon buttons: `scale(0.92)`
- Use spring-like cubic beziers: `cubic-bezier(0.34, 1.56, 0.64, 1)` or `cubic-bezier(0.2, 0, 0, 1)`.

### Rule 5: Pure Black Dark Mode Contrast
- When in dark mode, secondary surface containers must never be generic gray. Use calibrated dark tones:
  - Background: `#121212` (or AMOLED `#000000`).
  - Container High: `#1E1E1E` to `#282828`.
  - Border Highlights: `rgba(255, 255, 255, 0.12)`.
  - Inactive Text: `rgba(255, 255, 255, 0.6)`.

### Rule 6: Sticky Header Fade-in
- Sticky headers (`TopAppBar`, Search bar) start transparent and fade into frosted glass (`backdrop-filter: blur(16px)`) only after the user scrolls $> 16px$.

### Rule 7: Uppercase Section Headers
- All section titles (`KEEP LISTENING`, `FORGOTTEN FAVORITES`, `THEME`, `ACCOUNT`) **MUST** be styled in `uppercase`, with `font-size: 12px - 14px`, `font-weight: 700`, and `letter-spacing: 0.05em`.

### Rule 8: Pill Search Inputs
- All standalone search inputs must use `border-radius: 9999px` (or `28px`), with no underline indicators, containing an integrated clear (`×`) button that appears only when text is entered.

### Rule 9: Edge Specular Highlights on Frosted Glass
- True Liquid Glass requires a double-edge physical effect:
  - `border: 0.5px solid rgba(255, 255, 255, 0.2)`
  - `box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2), inset 0 1px 1px rgba(255, 255, 255, 0.25)`

### Rule 10: Two-Tier Settings Architecture
- Never navigate directly to a full-screen settings page from the main view. Always open the lightweight **Modal Bottom Sheet** first for rapid access to account status and essential switches, with an entry point to the full nested settings view.

---

## 12. Quick Reference Checklist for New Web Apps

- [ ] Import Google Sans, Outfit, or Plus Jakarta Sans variable fonts.
- [ ] Define the CSS Custom Properties from Section 3.
- [ ] Ensure `backdrop-filter` is supported and fallback to solid `#1E1E1E` is provided for older browsers.
- [ ] Implement the `grouped-card-container` CSS with automated nth-child / first-last radii.
- [ ] Implement the Floating Nav Bar centered at `max-width: 480px`.
- [ ] Anchor the MiniPlayer with a circular SVG progress track.
- [ ] Add `168px` bottom padding to all scrollable views.
- [ ] Implement bottom sheet gestures with touch velocity tracking.
- [ ] Include active press scales (`0.90` - `0.92`) on all interactive buttons.
