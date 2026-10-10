# ADR-0001: Hardware-Agnostic 2x Matrix Reprojection with Reverse-Z Depth Unprojection

## Status
Accepted (Ratified 2026-08-28; Imported during Cadence Setup)

## Context & Problem Statement
Minecraft Java Edition runs on Blaze3D and OpenGL 3.3+. High refresh-rate displays (120Hz-240Hz) require doubling visual fluidity without:
1. Vendor lock-in or proprietary hardware requirements (DLSS 3 requires NVIDIA RTX 4000 series; FSR 3 requires heavy optical flow pipelines).
2. Deep neural network inference overhead (which introduces excessive memory bandwidth and multi-millisecond frame latency).
3. Distorting Minecraft 26.2's Reverse-Z depth buffer mapping ($1.0 = \text{near}$, $0.0 = \text{far}$).
4. Blurring or corrupting 2D HUD / GUI overlays (chat, hotbar, crosshair, inventory).

## Decision Classification (Tier 1 Foundational)
- **Vector 1 (Numerical Results & Correctness):** Reverse-Z depth mapping handling with pitch singularity protection.
- **Vector 4 (Performance & Scaling):** Sub-millisecond GLSL shader execution (<1ms overhead) suitable for budget/integrated GPUs.
- **Vector 7 (Public & Module Interfaces):** Mixin interception in `GameRenderer` before `Gui`/`InGameHud` compositing.
- **Vector 8 (Algorithmic & Rendering Choices):** Mathematical unprojection to 3D world space and reprojection into previous camera coordinates.

## Alternatives Considered
1. **Optical Flow / Tensor-Assisted Frame Generation:**
   - *Pros:* Handles non-rigid deformable motion across all screen regions.
   - *Cons:* Heavy latency, requires hardware vendor extensions (Vulkan Optical Flow / Tensor cores), incompatible with pure OpenGL 3.3+ Core Profile on macOS / Linux / budget GPUs.
2. **Post-HUD Screen Warping:**
   - *Pros:* Simple full-window capture without mixin lifecycle complexity.
   - *Cons:* Warps UI text, crosshair, and inventory bars, producing unreadable text and visual ghosting.
3. **Camera Matrix Reprojection with Independent HUD Pass (Selected):**
   - *Pros:* Pure GLSL 3.3 fragment shader; intercepts depth and color before HUD pass; 0 additional geometry passes; 0 tensor dependencies; guaranteed crisp UI.
   - *Cons:* Disocclusion voids on camera snaps (addressed in ADR-0002).

## Ratified Architecture & Invariants
1. **Buffer Interception:** `FrameCaptureManager` maintains persistent `RenderTarget` FBOs (`currentFrameFBO`, `previousFrameFBO`, `intermediateFrameFBO`) resized dynamically with the game window.
2. **Matrix Mathematics:** Uses JOML (`Matrix4f`, `Vector4f`) to cache camera `ViewMatrix`, `ProjectionMatrix`, and `InverseViewProjectionMatrix` per render tick.
3. **Shader Pipeline:** `reproject.fsh` unprojects NDC coordinates $(x, y, z_{\text{rev}})$ using $\mathbf{M}_{\text{invViewProj}}$, reprojects into previous camera clip space using $\mathbf{M}_{\text{prevViewProj}}$, performs 3x3 depth-dilation, and clamps color samples against neighborhood extremes.
4. **Intermediate Frame Interleaving:** Synthesizes intermediate frame at time step $t = 0.5$ interleaved between base engine frames $N$ and $N+1$.
5. **HUD Isolation Invariant:** The 3D world is captured prior to `InGameHud.render()`. The 2D GUI is rendered independently on top of both base and synthesized frames.
