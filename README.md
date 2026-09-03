# Meridian X-Ray

X-Ray and Night Vision for the [Meridian Proxy](../meridian-proxy) — a pure
Layer-2 module built on top of `meridian-core`.

It talks only to neutral APIs (`meridian-api` + `meridian-core-api`) and never
touches raw Hytale packets, so a Hytale protocol update cannot break it.

## Requirements

This module **requires `meridian-core`** — X-Ray reads block types and applies
overrides through `meridian-core`'s `WorldState` service. Put **both** jars in the
proxy's modules folder:

```
modules/
├── meridian-core-impl-*.jar
└── meridian-xray-*.jar
```

`meridian-core` loads first (xray's `module.json` declares
`dependsOn: meridian-core`). Without it, xray is skipped with a warning.

## Features

- **X-Ray** — sees through `Soil_*` and `Rock_*` block types (grass, dirt, stone,
  sandstone, basalt, volcanic, ...), in one of two modes:
  - **Hide** — the blocks are not drawn at all.
  - **Frame** — each block keeps its own texture with a window cut into the
    middle, leaving a border of a few pixels (the width is a setting). The
    original picture comes from the game's `Assets.zip` (via `meridian-core`'s
    `GameAssets`); the cut copy is handed to the client **while it loads a
    world**, under the original's name (via `ClientAssets.provideAtConnect`),
    the way a server mod overrides a vanilla texture. In the session the toggle
    only switches the block's opacity, so it is instant. Terrain keeps its
    outline and colour; ore shows through the holes. `Frame opacity` picks
    between the glass-like transparent mode (inner faces between same-type
    blocks are culled) and the leaves-like cutout mode. Transparent is the
    one that renders the holes for soil and rock; cutout leaves them solid.
  - **Frame glow** — a light radius the targeted blocks emit, so the ground
    the frames reveal is lit without a separate night-vision pass.

    Choose the mode **before joining**: the client binds block types to their
    textures by hash when it loads a world and crashes if such a texture is
    taken away or replaced afterwards, so nothing is swapped mid-session. A
    world joined with Frame off hides the blocks until the next connect.
- **Night Vision** — makes every block emit a dim light.

Toggle both from the module's settings panel in the proxy window.

## Build

```sh
mvn clean package
```

Needs `meridian-api` and `meridian-core-api` in the local Maven repo — build the
`meridian-proxy` and `meridian-core` repos first (`mvn install`).

## Demo

https://youtu.be/O_rd1cLwxMI
