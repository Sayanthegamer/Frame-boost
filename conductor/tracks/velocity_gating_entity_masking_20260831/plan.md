# Implementation Plan: Camera Velocity Gating & Dynamic Entity Masking

## Phase 1: Angular Velocity Tracker & Velocity-Gated Reprojection Fallback [checkpoint: 93cafd0]
- [x] Task: Write failing unit tests for camera angular velocity calculation & velocity gating in `CameraMatrixTrackerTest` (Red) [4041411]
    - [x] Add test cases verifying $\Delta\theta$ calculation across 0°, 30°, 45°, 90°, and 180° yaw/pitch rotations
    - [x] Add test cases for translation velocity delta $\Delta d$
    - [x] Add test cases verifying `isVelocityGated()` threshold evaluation
- [x] Task: Implement angular & translation delta tracking in `CameraMatrixTracker` (Green) [4041411]
    - [x] Extract camera forward direction vector from view matrix
    - [x] Compute angular delta via dot product clamp & arccos
    - [x] Store instantaneous $\Delta\theta$ and $\Delta d$
- [x] Task: Add velocity gating configuration & passthrough control in `FrameGenConfig` and `FramePacer` (Green) [93cafd0]
    - [x] Add `enableVelocityGating`, `maxAngularVelocityThreshold`, and `maxTranslationVelocityThreshold` to `FrameGenConfig`
    - [x] Update `FrameGenConfigTest`
    - [x] Integrate velocity gating check into `FramePacer` to bypass `ReprojectionShaderPass` during high-velocity frames
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) [93cafd0]

## Phase 2: Dynamic Entity Masking & First-Person Hand Isolation [checkpoint: 5099855]
- [x] Task: Write failing unit tests & layout verification for entity mask buffers in `ShaderUniformLayoutTest` and `FrameCaptureManagerTest` (Red) [5099855]
    - [x] Validate entity mask sampler bindings and uniform definitions
    - [x] Verify entity mask texture format and initialization
- [x] Task: Implement entity / hand render hook & mask target generation in `FrameCaptureManager` & mixins (Green) [5099855]
    - [x] Allocate `entityMaskFBO` (R8 format) in `FrameCaptureManager`
    - [x] Add mixin hook into entity rendering / hand rendering passes to tag dynamic pixels into mask buffer
- [x] Task: Update `reproject.fsh` to sample entity mask and bypass warping on dynamic pixels (Green) [5099855]
    - [x] Add `EntityMaskSampler` uniform in `reproject.fsh`
    - [x] Blend dynamic entity pixels directly from `CurrentColorSampler` at full opacity without reprojection
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) [5099855]

## Phase 3: Screen-Edge Disocclusion Hardening & Diagnostic Overlay [checkpoint: ee1b016]
- [x] Task: Write shader uniform layout & edge fallback tests in `ShaderUniformLayoutTest` (Red) [ee1b016]
    - [x] Test edge falloff uniform configuration and bounds checks
- [x] Task: Refactor `reproject.fsh` border clamping & edge disocclusion fallback (Green) [ee1b016]
    - [x] Implement soft border falloff and fallback to `currColor` when reprojection coords exit $[0, 1]$ screen space
    - [x] Refine depth disocclusion rejection threshold
- [x] Task: Update `DebugScreenOverlayMixin` with angular velocity & gating telemetry (Green) [ee1b016]
    - [x] Display real-time $\Delta\theta$, $\Delta d$, and Reprojection/Gated status on F3 screen
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) [ee1b016]

## Phase: Review Fixes [checkpoint: ccd8a5f]
- [x] Task: Apply review suggestions for viewmodel reprojection, delta-time normalized gating, and direct UV clamping [0ed494b]
- [x] Task: Implement multi-condition lag spike gating, hard screen-edge culling, and viewmodel pose tracking [a78ef4b]
- [x] Task: Implement spatial extrapolation inpainting at screen borders to eliminate temporal shear fracture [f9ae427]
- [x] Task: Implement strict sub-pixel border rejection and forward viewmodel lighting synchronization [a8d5eba]
- [x] Task: Implement Multi-Tap Background Depth Infill to eliminate parallax disocclusion tearing [4732a90]
- [x] Task: Implement branchless 4-tap Gaussian infill with continuous confidence blending [4bfb832]
- [x] Task: Implement Directional Epipolar Parallax Infill and Sharp Native Fallback [1d02eb0]
- [x] Task: Implement True Background Epipolar Trajectory and Continuous Parallax Cosine Phase Damping [ccd8a5f]
