# Android visual parity with STEMwerk REAPER

The Android frontend follows the visual source of truth in the Lua UI, not a separate mobile brand.

Reference modules in STEMwerk-reaper:

- scripts/reaper/_internal/STEMwerk_UI.lua
- scripts/reaper/_internal/STEMwerk_UI_Tokens.lua
- scripts/reaper/_internal/STEMwerk_UI_Draw.lua
- scripts/reaper/_internal/STEMwerk_UI_Backgrounds.lua
- docs/assets/stemwerk-dynamic.svg

## Current Android mapping

| Lua source | Android implementation |
| --- | --- |
| Classic dark background | values/colors.xml: stemwerk_bg and stemwerk_bg_top |
| Classic input/card surfaces | stemwerk_card and stemwerk_input |
| Accent and primary buttons | stemwerk_button and stemwerk_button_primary |
| STEM_BORDER_COLORS | stemwerk_vocals, stemwerk_drums, stemwerk_bass, stemwerk_other |
| Animated STEMwerk artwork | assets/stemwerk_dynamic.svg |
| Adaptive application icon | drawable/stemwerk_icon_foreground.xml |

The Android layout is a native adaptation of the Lua UI. It keeps the same visual language while using Android controls and touch-sized spacing.

Future visual additions should first be checked against the Lua theme tokens and semantic palette. Runtime and model code must remain independent from this visual layer.
