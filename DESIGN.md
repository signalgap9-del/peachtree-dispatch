---
version: alpha
name: AtmosPath
description: A map-first, climate-aware route planning interface for cars, vans, and trucks.
colors:
  primary: "#0B57D0"
  on-primary: "#FFFFFF"
  primary-container: "#E8F0FE"
  secondary: "#188038"
  warning: "#F9AB00"
  danger: "#D93025"
  background: "#F8FAFD"
  surface: "#FFFFFF"
  surface-muted: "#F1F3F4"
  text-primary: "#202124"
  text-secondary: "#5F6368"
  outline: "#DADCE0"
typography:
  heading-lg:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: "700"
    lineHeight: 40px
    letterSpacing: -0.02em
  heading-sm:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: "700"
    lineHeight: 26px
  body-md:
    fontFamily: Inter
    fontSize: 15px
    fontWeight: "500"
    lineHeight: 23px
  label-sm:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: "700"
    lineHeight: 18px
  data-sm:
    fontFamily: DM Mono
    fontSize: 12px
    fontWeight: "500"
    lineHeight: 16px
rounded:
  sm: 8px
  md: 12px
  lg: 16px
  xl: 24px
  full: 9999px
spacing:
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 32px
components:
  search-field:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-primary}"
    typography: "{typography.body-md}"
    rounded: "{rounded.xl}"
    height: 48px
    padding: 0 16px
  mode-selector-active:
    backgroundColor: "{colors.primary-container}"
    textColor: "{colors.primary}"
    typography: "{typography.label-sm}"
    rounded: "{rounded.full}"
    height: 36px
    padding: 0 14px
  panel:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.lg}"
    padding: "{spacing.md}"
  panel-muted:
    backgroundColor: "{colors.surface-muted}"
    textColor: "{colors.text-secondary}"
    rounded: "{rounded.md}"
    padding: "{spacing.sm}"
  risk-warning:
    backgroundColor: "{colors.warning}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.full}"
    padding: 4px 8px
  risk-danger:
    backgroundColor: "{colors.danger}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.full}"
    padding: 4px 8px
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.label-sm}"
    rounded: "{rounded.full}"
    height: 40px
    padding: 0 18px
---

## Overview

AtmosPath is a calm, nationwide map-first navigation and route intelligence product.
It borrows the immediate usability of consumer mapping products while exposing
production-grade climate risk and optimization detail. The map remains the main
canvas; panels explain or control it without becoming a generic CRM dashboard.

## Colors

- Blue is reserved for primary navigation actions, the selected vehicle mode,
  and the currently focused route.
- Green communicates optimized or low-risk outcomes.
- Amber and red communicate climate or operational risk.
- Surfaces stay white or near-white so map geometry and route colors remain the
  strongest visual information.

## Typography

Use Inter for interface hierarchy, Noto Sans KR as the Korean fallback, and
DM Mono only for distances, durations, risk scores, and algorithm metadata.
Do not shrink interface text below 12px on production screens.

## Layout

The map fills the viewport. Search and vehicle mode selection sit at the top.
Route planning and itinerary controls occupy a floating left panel. Contextual
weather information and map tools float on the right. The selected route summary
sits above the bottom edge.

## Elevation & Depth

Use restrained elevation: white surfaces, subtle outlines, and soft shadows.
Backdrop blur is allowed only for floating map panels. Avoid decorative gradients
and excessive glass effects.

## Shapes

Use pill shapes for mode selectors and primary actions. Use 12-16px rounded
corners for panels and cards. Map markers must remain legible at a glance.

## Components

Search behaves like a place and route search, not a database filter. Vehicle
selectors are always visible and support Car, Van, and Truck. Route cards expose
vehicle type, duration, distance, and climate risk. Every map tool needs a real
interaction and an accessible label.

## Do's and Don'ts

- DO preserve the map as the dominant canvas.
- DO show real addresses and route geometry.
- DO explain climate-aware optimization decisions.
- DO support car, van, and truck route profiles.
- DON'T use Google trademarks or copy proprietary Google assets.
- DON'T present vehicle modes that do not change backend calculations.
- DON'T turn the product into a dense fleet-only CRM dashboard.
