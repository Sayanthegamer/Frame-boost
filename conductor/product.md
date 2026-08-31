# Product Definition: Universal Frame Generation Mod (2x Reprojection Engine)

## Vision & Summary
A lightweight, hardware-agnostic 2x Frame Generation system for Minecraft Java Edition (Fabric Loader, unobfuscated official mappings, Blaze3D/OpenGL). The mod generates and presents an intermediate frame ($N + 0.5$) between base engine frames ($N$ and $N+1$) using Camera Matrix Reprojection and Depth Buffer Unprojection, avoiding heavy neural models to run smoothly on all hardware.

## Target Audience & Core Value
- **Audience:** Minecraft players seeking 2x visual fluidity and high refresh rate motion on standard/budget GPUs without tensor hardware.
- **Core Value:** Doubles perceived framerate with ultra-low latency, sub-millisecond shader overhead, and no proprietary vendor lock-in.

## Key Architecture & Features
1. **Frame & Matrix Capture (`FrameCaptureManager`):**
   - Persistent `RenderTarget` FBOs (`previousFrameFBO`, `currentFrameFBO`, `intermediateFrameFBO`, `entityMaskFBO`).
   - Interception of color and non-linear depth buffers before HUD overlay rendering.
   - Per-frame caching of camera View, Projection, Inverse View-Projection matrices, and angular velocity deltas.
2. **GLSL Reprojection Pipeline (`reproject.fsh`):**
   - Unprojects screen coordinates to 3D world space and reprojects into previous camera space.
   - Depth-dilation, color clamping, dynamic entity masking, and smooth edge boundary falloff to suppress ghosting and disocclusion artifacts.
   - Intermediate frame blending ($t = 0.5$).
3. **Presentation & Pacing Engine (`FramePacer`):**
   - Regulated frame presentation interleaving base frames and generated frames ($t = 0.5$).
   - Forward-only angular velocity gating: automatically falls back to raw base-frame passthrough on rapid camera snaps (>30°/tick) with zero input lag.
   - Independent HUD pass (`InGameHud`) rendered cleanly on top of both frame types.
4. **Configuration & Controls:**
   - Lightweight JSON configuration file with hotkey toggle, velocity gating thresholds, and real-time rotation delta telemetry on F3 debug overlay.

## Scope & Compatibility Strategy
- Primary target: Vanilla Fabric Loader / Blaze3D / OpenGL.
- Modular architecture prepared for downstream integration with Sodium / Iris.
