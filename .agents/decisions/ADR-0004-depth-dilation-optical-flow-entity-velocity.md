# ADR-0004: Reverse-Z Depth Dilation, Temporal-Salience Optical Flow, and Depth-Occluded Per-Entity Velocity Buffer

## Status
Accepted (Ratified 2026-11-11; Rev 3 Post-Playtest Hardening)

## Context & Problem Statement
Purely rigid camera matrix reprojection (`ADR-0001`) assumes that all pixels in the 3D scene are stationary in world space between Frame $N-1$ and Frame $N$. In actual gameplay—and verified empirically during playtesting on an Intel Core i5-10210U + NVIDIA GeForce MX330 at 1080p—naive screen-space optical flow and unoccluded 2D entity velocity boxes introduce four critical failure modes:
1. **Stationary Foliage & Cloud Shimmer (Playtest Finding 1):** Evaluating spatial-only trajectory salience (`min(cost(cN, cN0), cost(cP, cP0))`) without checking whether the candidate endpoints actually changed over time causes high-contrast stationary textures (jungle leaves, tree bark, tall grass, iron bars, cloud edges) to hallucinate non-zero flow vectors even at $\Omega = 0^\circ/\text{s}$.
2. **Occluded Underground Entity Bleed-Through (Playtest Finding 2):** `ClientLevel.entitiesForRendering()` includes entities underground in caves or behind trees (`EntMV: 4..10`). Projecting their 2D screen-space bounding boxes into `entityMaskFBO` without a Reverse-Z depth occlusion check against `dMid` warps foreground trees and sky clouds directly in front of the player.
3. **Mobile GPU Bandwidth Saturation (Playtest Finding 3):** Invoking 9-tap depth dilation inside the 2-iteration refinement loops executed >100 texture fetches per pixel (~210M fetches/frame at 1080p), saturating the GeForce MX330's 64-bit GDDR5 bus and dropping base framerate from 49 FPS to 17–28 FPS.
4. **False Stationary Lag-Spike Gating (Playtest Finding 4):** When integrated server tick spikes pushed frame duration above 50 ms while standing still ($\Omega = 0^\circ/\text{s}$), `CameraMatrixTracker.isVelocityGated()` falsely triggered `GATED (SNAP)`.

## Decision Classification (Tier 1 Foundational)
- **Vector 1 (Numerical Results & Correctness):** Temporal motion salience ($\min(\text{spatialSalience}, \text{temporalSalience})$), per-pixel Reverse-Z entity depth occlusion culling (`pixelDepth <= entityDepth + tolerance`), and stationary camera non-gating invariance.
- **Vector 4 (Performance & Scaling):** 5-tap cross seed dilation + single-tap refinement loop + fast aligned-pixel bypass (`bestRawCost <= 0.025 && priorVel == 0`), cutting GPU texture fetches by >80% on aligned terrain.
- **Vector 8 (Algorithmic & Rendering Choices):** Hybrid depth-occluded object-space residual velocity seeding + temporal-salience coarse-to-fine optical flow with 3x3 neighborhood variance AABB clamping.

## Ratified Architecture & Invariants
1. **Phase 1A — 5-Tap Cross Reverse-Z Seed Dilation (`sampleDilatedDepth`):**
   - Evaluates the 5-tap cross maximum Reverse-Z depth on initial seeds and post-warp validation while keeping the 2-pass iterative refinement loop single-tap to preserve mobile GPU memory bandwidth.
2. **Phase 1B — Temporal-Salience Symmetric Optical Flow & Aligned-Pixel Bypass (`computeSymmetricFlowOffset`):**
   - Immediately bypasses optical flow search when camera reprojection already aligns Frame $N$ and Frame $N-1$ (`bestRawCost <= 0.025` and `priorVel == 0`), guaranteeing zero pixel perturbation on stationary terrain, leaves, and clouds.
   - Weights candidate displacements by **Temporal Motion Salience**:
     $$\text{temporalSalience}(\vec{v}) = \min\big(\text{cost}(\text{Curr}(\text{uv}_N), \text{Prev}(\text{uv}_N)),\; \text{cost}(\text{Prev}(\text{uv}_P), \text{Curr}(\text{uv}_P))\big)$$
     $$\text{salience}(\vec{v}) = \min(\text{spatialSalience}(\vec{v}),\; \text{temporalSalience}(\vec{v}))$$
     ensuring static textures (`Curr == Prev`) have strictly zero salience while genuinely moving features crossing a flat background midpoint are captured.
3. **Phase 2 — Reverse-Z Depth-Occluded Entity Velocity Buffer (`EntityVelocityTracker` & `sampleEntityVelocity`):**
   - `EntityVelocityTracker` frustum-culls offscreen and distant (>32 blocks) entities and encodes each moving entity's nearest Reverse-Z depth (`ndcZ`) into channel `B` (`encodedB`) of `entityMaskFBO`.
   - In `reproject.fsh`, `sampleEntityVelocity(texCoord, texelSize, dMid)` rejects sky pixels (`dMid <= 0.0001`) and rejects occluded entities whenever the visible scene surface is nearer than the entity (`dMid > bestMask.b + depthTolerance`).
4. **Stationary Camera Non-Gating Invariant (`CameraMatrixTracker.isVelocityGated`):**
   - When the camera has not rotated or translated (`angularDeltaDegrees <= 0.05` and `translationDelta <= 0.01`), `isVelocityGated` returns `false` regardless of frame duration spikes.
