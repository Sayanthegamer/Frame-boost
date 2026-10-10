# ADR-0002: Forward-Only Velocity Gating, Dynamic Entity Stencil Masking, and Epipolar Infill

## Status
Accepted (Ratified 2026-08-31; Imported during Cadence Setup)

## Context & Problem Statement
Purely geometric camera matrix reprojection suffers from two critical failure modes in real-time gaming:
1. **Camera Snaps & Fast Panning Disocclusion:** When a player snaps the mouse (e.g., >30° per tick), previous frames do not contain the newly exposed geometry, resulting in black voids, boundary stretching, and temporal shearing.
2. **Independent Entity & Viewmodel Motion:** Dynamic entities (mobs, projectiles) and the first-person viewmodel (player hand, swinging weapons, held items) move independently of camera rotation. Warping them using camera matrices produces smeared trailing ghosting artifacts.

## Decision Classification (Tier 1 Foundational)
- **Vector 1 (Numerical Results & Correctness):** Dot-product angular delta calculation and epipolar trajectory reconstruction.
- **Vector 4 (Performance & Scaling):** Single CPU dot product per tick; 1-2 texture tap mask test on GPU; avoids costly multi-viewport overscan rendering.
- **Vector 6 (Execution & Synchronization Model):** Forward-only presentation ensures zero input latency (no multi-frame buffering queue).
- **Vector 8 (Algorithmic Choices):** Stencil-based pre-hand background capture, directional epipolar infill, and instantaneous passthrough fallback.

## Alternatives Considered
1. **Bidirectional Motion Buffering (Frame Interpolation):**
   - *Pros:* Interpolates between frame $N$ and frame $N+1$, hiding disocclusion.
   - *Cons:* Adds 1 full frame of input lag (16.6ms at 60Hz), severely harming first-person game responsiveness.
2. **Overscan / FOV Padding:**
   - *Pros:* Renders an expanded viewport (e.g. 110% FOV) to fill disocclusion margins.
   - *Cons:* Increases base geometry and rasterization load by 20-30%, hurting baseline framerates on low-end hardware.
3. **Forward-Only Velocity Gating + Dynamic Stencil Masking + Epipolar Infill (Selected):**
   - *Pros:* 0 input latency penalty; instantaneous fallback to raw frame $N$ during high-angular snaps; isolates dynamic entities and viewmodels via `entityMaskFBO` and stencil composition; directionally infills parallax margins along epipolar trajectory lines.
   - *Cons:* Reprojection is temporarily bypassed during extreme 90° flick turns (gracefully falling back to 1x vanilla fluidity during snaps).

## Ratified Architecture & Invariants
1. **Angular & Translation Tracker (`CameraMatrixTracker`):**
   - Forward vectors $\vec{f}_{\text{prev}}, \vec{f}_{\text{curr}}$ extracted from view matrices.
   - Angular delta computed via dot product clamp:
     $$\Delta\theta = \arccos(\text{clamp}(\vec{f}_{\text{prev}} \cdot \vec{f}_{\text{curr}}, -1.0, 1.0)) \times \frac{180}{\pi}$$
   - When $\Delta\theta > \theta_{\text{threshold}}$ (default: 30.0°) or $\Delta d > d_{\text{threshold}}$ (default: 10.0 blocks), `FramePacer` and `ReprojectionShaderPass` pass through raw base frame $N$ without warping.
2. **Dynamic Entity & Viewmodel Isolation:**
   - Allocates `entityMaskFBO` (R8 format). Dynamic entities and viewmodel geometry write to mask.
   - In `reproject.fsh`, masked pixels sample directly from `CurrentColorSampler` at full opacity without reprojection.
   - Pre-hand world background capture preserves depth-coherent stencil composition behind held items.
3. **Screen-Edge Hardening & Directional Epipolar Infill:**
   - Out-of-bounds UV coordinates trigger soft edge falloff and directional epipolar infill along camera motion vectors, eliminating temporal shear fracture.
