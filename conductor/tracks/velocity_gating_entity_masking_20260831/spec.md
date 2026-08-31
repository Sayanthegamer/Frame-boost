# Specification: Camera Velocity Gating & Dynamic Entity Masking for Disocclusion-Free Reprojection

## Overview
This track addresses the fundamental architectural limitations of purely spatial matrix reprojection in the Frame Generation mod:
1. **Extreme Camera Snap Artifacts & Disocclusion Voids:** Rapid camera rotation (e.g., 90° snaps in a single tick) exposes unrendered geometry and creates black screen-edge smearing. This track implements **Forward-Only Angular Velocity Gating (Passthrough)**: measuring per-tick angular and translational camera deltas and automatically bypassing reprojection (passing through raw base frame $N$) when rotation exceeds a configurable threshold (e.g. >30°/tick), eliminating latency-inducing bidirectional buffering and avoiding costly overscan rendering.
2. **Dynamic Entity Motion Smearing:** Dynamic entities (mobs, player hands/held items) moving independently of the camera cause ghosting and smearing under static camera matrix reprojection. This track introduces **Dynamic Entity Depth/Stencil Masking & Passthrough Layering**, isolating dynamic entities so they are drawn directly from the current frame without camera-motion warping.
3. **Robust Screen-Edge Clamping & Depth Boundary Rejection in GLSL:** Updating `reproject.fsh` to clamp edge coordinates and gracefully handle depth disocclusion boundaries without creating stretching or black voids.

## Functional Requirements

### 1. Camera Angular Velocity Tracker & Gating (`CameraMatrixTracker` / `FramePacer`)
- Track previous and current camera orientation vectors ($\vec{f}_{\text{prev}}, \vec{f}_{\text{curr}}$).
- Calculate angular rotation delta:
  $$\Delta\theta = \arccos(\text{clamp}(\vec{f}_{\text{prev}} \cdot \vec{f}_{\text{curr}}, -1.0, 1.0)) \times \frac{180}{\pi}$$
- Track translational movement delta:
  $$\Delta d = \|\vec{p}_{\text{curr}} - \vec{p}_{\text{prev}}\|$$
- Expose velocity thresholds in `FrameGenConfig`:
  - `enableVelocityGating`: `boolean` (default: `true`)
  - `maxAngularVelocityThreshold`: `float` (default: `30.0` degrees)
  - `maxTranslationVelocityThreshold`: `float` (default: `10.0` blocks)
- When $\Delta\theta > \theta_{\text{max}}$ or $\Delta d > d_{\text{max}}$, flag frame state as gated; `FramePacer` and `ReprojectionShaderPass` pass through raw base frame $N$ with zero warping and zero latency.

### 2. Dynamic Entity Masking & First-Person Hand Isolation
- Hook entity rendering pipeline (or first-person hand renderer and living entity renderers) to mark dynamic geometry (via Stencil buffer write, Depth Range tag, or an Entity Mask Render Target).
- In `reproject.fsh`, check the dynamic entity mask: pixels belonging to moving entities are sampled directly from `CurrentColorSampler` at $t=1.0$ (or unwarped coordinates) rather than warped by static camera matrix math.
- Prevent player hand / held items from creating smeared trailing paths during camera pans.

### 3. Shader Edge Disocclusion & Border Fallback Handling
- In `reproject.fsh`, handle out-of-bounds UV coordinates (`prevUV.x < 0.0 || prevUV.x > 1.0 || prevUV.y < 0.0 || prevUV.y > 1.0`): smoothly clamp or fall back to `currColor` with a soft border vignette/falloff instead of outputting black or stretched border pixels.
- Enhance depth-discontinuity thresholding to reject warped background pixels when depth delta between current and reprojected previous depth indicates unmasked disocclusion.

### 4. Configuration & Diagnostics
- Config options in `FrameGenConfig`:
  - `enableVelocityGating`: boolean (default: `true`)
  - `angularVelocityThreshold`: float (default: `30.0` degrees)
  - `enableEntityMasking`: boolean (default: `true`)
- Update F3 Debug overlay (`DebugScreenOverlayMixin`) to display instantaneous angular delta ($\Delta\theta$) and whether the current frame was gated/passed-through or reprojected.

## Non-Functional Requirements
- **Zero Input Latency Penalty:** Maintain pure forward presentation without buffering or waiting for future frame $N+1$.
- **Zero Rendering Overhead on Base Engine:** No overscan FOV enlargement or extra geometry rendering passes; lightweight stencil/FBO mask hooks.
- **High Performance:** Velocity calculation done in sub-microsecond vector dot product on CPU; shader masking evaluated in 1-2 texture taps on GPU.

## Acceptance Criteria
- [ ] Snapping the camera 90° (or >30°) in a single tick triggers the velocity gate and outputs a clean base-frame passthrough without black voids or stretching artifacts.
- [ ] Moving entities (e.g. walking zombies, player hand animations) do not produce trailing reprojection smear trails behind them.
- [ ] Unit tests for `CameraMatrixTracker` verify accurate angular delta calculations ($\Delta\theta$) across various orientation quaternions/vectors.
- [ ] Unit tests for `FrameGenConfig` verify persistence and boundary checks for new velocity gating settings.
- [ ] F3 debug overlay accurately reports camera angular velocity and gating status.

## Out of Scope
- Heavy optical flow compute passes or bidirectional frame buffering (which introduces input latency).
- Rendering expanded FOV overscan buffers.
- Modifying third-party shader pipelines (Iris/Sodium specific internal shadow passes).
