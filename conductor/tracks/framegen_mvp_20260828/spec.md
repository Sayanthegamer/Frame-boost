# Track Specification: Universal Frame Generation Mod MVP (2x Reprojection Engine)

## Overview
Implement the core 2x frame generation engine for Minecraft Java Edition 26.2 (Fabric Mod Loader, Mojang mappings, Blaze3D/OpenGL). The mod intercepts the rendering pipeline after 3D world rendering but before HUD drawing, captures color/depth buffers along with camera transformation matrices, and runs a GLSL reprojection shader pass to synthesize intermediate frames ($t = 0.5$) with independent HUD overlays, doubling visual smoothness without neural network overhead.

## Functional Requirements
1. **Frame & Depth Capture Subsystem (`FrameCaptureManager`):**
   - Manage two persistent `RenderTarget` buffers (`currentFrameFBO`, `previousFrameFBO`) synchronized with window size.
   - Capture color texture and non-linear depth buffer texture prior to UI rendering.
   - Cache camera `ViewMatrix`, `ProjectionMatrix`, and calculate `InverseViewProjectionMatrix` per render tick.
2. **Pipeline Mixin Interception (`GameRendererMixin` & Window Hooks):**
   - Inject into `GameRenderer.render()` immediately prior to `Gui.render()` / `InGameHud.render()`.
   - Intercept render/swap loop to dispatch generated intermediate frame passes.
3. **Reprojection Shader Pipeline (`reproject.fsh`):**
   - Unproject screen pixels to 3D world space using `InverseViewProj`.
   - Reproject world coordinates into previous camera clip space using `PrevViewProj`.
   - Sample current and previous color textures with depth-dilation and neighborhood color clamping.
   - Blend intermediate frame at $t = 0.5$.
4. **Frame Pacing & Presentation (`FramePacer`):**
   - Interleave presentation of base engine frames and synthesized frames.
   - Render HUD (crosshair, chat, hotbar) independently on top of both frame types.
5. **Configuration & Diagnostics:**
   - JSON configuration file with hotkey toggle and F3 debug overlay telemetry.

## Non-Functional Requirements
- **Performance:** Minimal shader overhead (<1ms) suitable for low/mid-range GPUs.
- **Resilience:** Safe fallback to standard 1x vanilla rendering on any GL/shader error without crashing.

## Acceptance Criteria
- [ ] Fabric mod scaffold compiles cleanly under Java 25 / Gradle Loom.
- [ ] `FrameCaptureManager` maintains valid color and depth textures.
- [ ] Camera matrix unprojection and reprojection math is mathematically verified via tests.
- [ ] `reproject.fsh` compiles and executes fullscreen reprojection pass.
- [ ] `GameRendererMixin` hooks correctly before HUD pass.
- [ ] Frame generation toggle hotkey and F3 telemetry function properly.

## Out of Scope
- Sodium / Iris internal pipeline replacement (planned for modular compatibility follow-up).
- Optical flow motion vectors or >2x frame generation.
