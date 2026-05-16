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

- **X-Ray** — hides `Soil_*` and `Rock_*` block types (grass, dirt, stone,
  sandstone, basalt, volcanic, ...).
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
