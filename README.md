# Frame Boost 🚀
### Hardware-Agnostic 2x Frame Generation for Minecraft Java Edition

[![Build Status](https://github.com/Sayanthegamer/Frame-boost/actions/workflows/build.yml/badge.svg)](https://github.com/Sayanthegamer/Frame-boost/actions/workflows/build.yml)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.3-brightgreen.svg)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/ModLoader-Fabric-blue.svg)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25%2B-orange.svg)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**Frame Boost (`v1.2.0`)** is a high-performance, open-source **2x Frame Generation** mod for Minecraft Java Edition (Fabric Loader, Blaze3D / OpenGL 3.3+). It doubles perceived visual fluidity across the **3D world, moving entities, particles, and first-person hand/viewmodel** by synthesizing and interleaving an intermediate frame ($T = 0.5$) between base engine frames.

Unlike proprietary vendor solutions (DLSS 3, FSR 3) or heavy neural network models, Frame Boost runs pure GLSL fragment shaders with sub-millisecond overhead—working natively across **NVIDIA (including mobile MX-series), AMD, Intel, and Apple Silicon GPUs** with zero hardware lock-in.

---

## ✨ Features (Full 4-Phase Architecture)

- **⚡ True 2x Visual Fluidity:** Interleaves an intermediate synthesized frame at $T = 0.5$ between every base game frame.
- **📐 Phase 1A — 5-Tap Cross Reverse-Z Depth Dilation:** Locks onto foreground silhouettes in Mojang's Reverse-Z depth buffer ($1.0 = \text{near}, 0.0 = \text{far}$), eliminating sky bleed on thin geometry (fences, grass, leaves) while keeping refinement loops single-tap for high GPU memory bandwidth.
- **🌊 Phase 1B — Temporal-Salience Symmetric Optical Flow (`F9` Presets):** Tracks non-rigid motion (particles, animated water/lava/fire) to $T = 0.5$ while bypassing aligned static terrain (`bestRawCost <= 0.025`) and enforcing temporal motion salience so stationary foliage and clouds never shimmer.
- **🏃 Phase 2 — Depth-Occluded Per-Entity 3D Motion Vectors:** Tracks moving mobs, players, and projectiles in 3D world space, subtracts rigid camera motion to isolate pure entity velocity $(\Delta u_{\text{res}}, \Delta v_{\text{res}})$, and performs per-pixel Reverse-Z depth occlusion culling (`dMid <= entityDepth + tolerance`) so underground cave mobs never warp foreground trees or sky.
- **🗡️ Phase 3 & 4 — 3-Layer World / Viewmodel / GUI Separation & $T = 0.5$ Hand Interpolation:** Captures the post-`render3dHud` buffer before 2D GUI rendering to isolate the 3D first-person hand from crosshairs/hotbars, synthesizing smooth $T = 0.5$ weapon swings and viewmodel bobbing over the unoccluded $T = 0.5$ world background.
- **📊 Real-time F3 Telemetry:** Displays base FPS, effective Hz, CPU work time, reprojection duration, active flow preset (`Flow: PERF / QUAL / OFF`), angular velocity (`Omega`), and tracked moving entity count (`EntMV`).

---

## 🎮 Keybindings & Controls

| Default Key | Action | Description |
| :--- | :--- | :--- |
| **`F8`** | **Toggle Frame Generation** | Instantly enable or disable 2x frame generation at runtime. |
| **`F9`** | **Cycle Optical Flow Preset** | Switch live between **`PERFORMANCE`** (4-Tap Fast MX330 Mode), **`QUALITY`** (12-Tap Ultra Mode), and **`OFF`** (Camera + Entity MV Only). |
| **`F3`** | **Debug Telemetry** | View base FPS, effective Hz, active flow preset, entity MV count, and gating status. |

---

## ⚙️ Configuration

Configuration is automatically saved to `.minecraft/config/framegen.json`:

```json
{
  "enabled": true,
  "interpolationFactor": 0.5,
  "debugOverlayEnabled": true,
  "depthDilationRadius": 1.0,
  "velocityGatingEnabled": true,
  "maxAngularVelocityThreshold": 720.0,
  "maxTranslationVelocityThreshold": 50.0,
  "maxAbsoluteAngleThreshold": 15.0,
  "maxFrameDurationThresholdMs": 50.0,
  "opticalFlowEnabled": true,
  "opticalFlowQualityMode": 1,
  "opticalFlowSearchRadius": 3.0,
  "entityMotionVectorsEnabled": true,
  "entityVelocityMaxUv": 0.125,
  "viewmodelMotionInterpolationEnabled": true,
  "cadencePacingEnabled": false,
  "debugLadderTintEnabled": false
}
```

---

## 🛠️ Building & Auditing

See [ANTIGRAVITY_AUDIT.md](ANTIGRAVITY_AUDIT.md) for the full automated audit prompt and in-game playtest checklist.

```bash
# Build and run all unit tests with Gradle (Windows)
.\gradlew.bat clean check test build --no-daemon --warning-mode all
```

Compiled mod JAR will be located in `build/libs/framegen-1.2.0.jar`.
