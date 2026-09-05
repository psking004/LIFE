# LIFE — Design System Reference (Stitch Design DNA)

**Brand Identity**: LIFE
**Tagline**: *Ideas evolve here.*
**Visual Personality**: Minimal, premium, modern, calm, intelligent, academic, focused, trustworthy, collaborative.

---

## 1. Color Palette

### Primary & Accent Tokens
- **Brand Primary Accent**: `Teal 600` (`#0D9488`) / `Teal 400` (`#14B8A6` in dark)
- **Brand Primary Hover/Pressed**: `Teal 700` (`#0F766E`)
- **Brand Accent Light Container**: `Teal 50` (`#F0FDFA`) / `Teal 900` (`#134E4A`)
- **Brand Accent Border**: `Teal 200` (`#99F6E4`)

### Semantic Node Type Tokens
- **Problem Node**:
  - Badge Background: `#FEE2E2` (Light) / `#450A0A` (Dark)
  - Badge Text / Border: `#DC2626` / `#F87171`
  - Label: `PROBLEM`
- **Improvement Node**:
  - Badge Background: `#E0F2FE` (Light) / `#082F49` (Dark)
  - Badge Text / Border: `#0284C7` / `#38BDF8`
  - Label: `IMPROVEMENT`
- **Refinement Node**:
  - Badge Background: `#FEF3C7` (Light) / `#451A03` (Dark)
  - Badge Text / Border: `#D97706` / `#FBBF24`
  - Label: `REFINEMENT`

### Neutral Canvas Tokens
- **Background Light**: `#F8FAFC` (Slate 50)
- **Background Dark**: `#0F172A` (Slate 900)
- **Surface Light**: `#FFFFFF` (White)
- **Surface Dark**: `#1E293B` (Slate 800)
- **Surface Variant Light**: `#F1F5F9` (Slate 100)
- **Surface Variant Dark**: `#334155` (Slate 700)
- **Text Primary Light**: `#0F172A` (Slate 900)
- **Text Primary Dark**: `#F8FAFC` (Slate 50)
- **Text Secondary Light**: `#64748B` (Slate 500)
- **Text Secondary Dark**: `#94A3B8` (Slate 400)
- **Subtle Dividers / Borders**: `#E2E8F0` (Slate 200) / `#334155` (Slate 700)

### Voting States
- **Unvoted**: Slate 500 icon (`#64748B`), neutral border `#E2E8F0`, transparent/neutral background
- **Upvoted**: Teal 600 icon (`#0D9488`), Teal border (`#14B8A6`), Teal tint background (`#CCFBF1` / `#134E4A`)

---

## 2. Typography Hierarchy

- **Brand Display Title**: 28sp, Bold, tracking -0.5sp
- **Screen Title**: 22sp, SemiBold, Slate 900
- **Section / Hero Header**: 18sp, SemiBold, Slate 800
- **Card Title**: 16sp, SemiBold, Slate 900, Line Height 22sp
- **Card Body**: 14sp, Normal, Slate 600, Line Height 20sp
- **Node Badge Label**: 11sp, Bold, tracking 0.5sp (All Caps)
- **Metadata (Author, Time)**: 12sp, Medium, Slate 500
- **Vote Counter**: 13sp, Bold
- **Score Value**: 20sp, Bold, Teal 700

---

## 3. Spacing & Shape Tokens

- **Grid Base**: 8dp
- **Padding - Screen Horizontal**: 16dp
- **Padding - Card Inner**: 16dp
- **Padding - Compact Item**: 12dp
- **Gap - Card Vertical**: 12dp
- **Corner Radii**:
  - Cards: 16dp
  - Badges: 8dp
  - Buttons (Primary/Secondary): 12dp
  - Bottom Bar Container: 24dp (top rounded or sleek full-width)
  - Upvote Pill: 20dp
- **Touch Target**: Minimum 48dp on all interactive triggers

---

## 4. Tree Layout Geometry

- **Indentation Per Depth**: 20dp per level
- **Tree Guide Rails**: 2dp solid line (`#CBD5E1` / `#475569`)
- **Node Bullet Indicator**: 10dp filled circle with 2dp ring
- **Branch Connector**: Smooth curved or L-shaped branch line to child node
- **Expand/Collapse Pill**: Compact 28dp x 28dp touch target with micro-icon and child counter
