# ADR-0005: 3-Layer World/Viewmodel/GUI Separation, T=0.5 Hand Motion Interpolation & F9 Optical Flow Quality Presets

- **Status:** Accepted
- **Date:** 2026-03-30

## Context
During live playtesting of `v1.1.0` on an NVIDIA GeForce MX330, three visual and performance issues were identified:
1. **Stationary Leaf/Cloud Shimmer & GPU Bandwidth:** Spatial-only salience allowed static high-contrast edges (leaves against sky) to drift during optical flow search, and 9-tap depth dilation + 12-tap optical flow cost ~54+ texture samples per pixel.
2. **Underground/Occluded Mob Distortion (`EntMV: 4–10`):** `ClientLevel.entitiesForRendering()` includes underground caves and mobs behind walls; without frustum culling or a Reverse-Z depth occlusion test, unseen mobs warped foreground trees and sky.
3. **1x First-Person Hand Stutter (`Phase 3 & 4`):** `hud_composite.fsh` treated both the 3D first-person hand (`render3dHud`) and 2D GUI (`Gui.render`) as a single static overlay. While prevents crosshair ghosting, the player's swinging sword or bobbing hand stayed frozen at $T = 1.0$ during the synthesized $T = 0.5$ frame.

## Decisions
1. **3-Layer Frame Capture (`WorldOnly`, `PostHand`, `MainColor`):**
   - `GameRendererMixin` hooks both after `renderLevel` (capturing `WorldOnly` + `Depth` in `FrameCaptureManager`) and after `render3dHud` (capturing `currentPostHandFBO` and ping-ponging `prevPostHandFBO`).
   - `hud_composite.fsh` separates Layer 2 (3D hand via `abs(PostHand - WorldOnly)` with stationary fast-path strictly gated on `hCurr0.a > 0.35 && hPrev0.a > 0.35` and `if (hN.a <= 0.35) continue;` bandwidth guard inside the 9-tap search) from Layer 3 (2D GUI via `abs(MainColor - CurrPostHand * V)`).
   - During the synthesized $T = 0.5$ frame, `synthesizeMidpointHand` searches a 9-tap symmetric neighborhood `(uv + 0.5*v in CurrPostHand, uv - 0.5*v in PrevPostHand)` to translate the moving hand to its midpoint position while clearing vacated $T = 1.0$ and $T = 0.0$ endpoints (`anyPrevHandNearby -> mask = 0.0`) so the unoccluded $T = 0.5$ world shows through cleanly.
2. **Temporal-Salience Optical Flow & `F9` Quality Presets (`PERF` / `QUAL` / `OFF`):**
   - `computeSymmetricFlowOffset` gates displacement candidates by **temporal salience** (`min(cost(cN, cPrevAtN), cost(cP, cCurrAtP))`), ensuring static textures where $I_N(x) \approx I_{N-1}(x)$ never receive negative salience bonuses.
   - `KeybindingHandler` binds `F9` (`key.framegen.flow_preset`) to cycle `uOpticalFlowQuality` between `1` (`PERFORMANCE`: 4-tap cardinal diamond + Lucas-Kanade, default for MX330/integrated GPUs), `2` (`QUALITY`: 12-tap coarse+fine diamond + Lucas-Kanade), and `0` (`OFF`), and matches `evaluateDilatedDepth3x3` to the 5-tap cardinal cross (`{1, 3, 4, 5, 7}`).
   - `FramePacer.performHybridWait` uses a `3_500_000L` (3.5 ms) spin-wait margin so sub-3.5 ms pacing waits avoid Windows `LockSupport.parkNanos` timer-quantum overshoots (~6.5 ms).
3. **Frustum-Culled, Depth-Occluded Entity Motion Vectors:**
   - `EntityVelocityTracker` frustum-culls offscreen entities (`ndcZ <= 0`, `|ndcX,Y| > 1.25`, distance `> 32m`) and encodes each entity's nearest Reverse-Z depth into channel `B` (`encodedB`).
   - `sampleEntityVelocity(texCoord, texelSize, dMid)` in `reproject.fsh` rejects sky (`dMid <= 0.0001`) and rejects occluded entities whenever `dMid > bestMask.b + depthTolerance`.
