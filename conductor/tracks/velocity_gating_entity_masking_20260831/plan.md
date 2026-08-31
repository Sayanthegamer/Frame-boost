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

## Phase 2: Dynamic Entity Masking & First-Person Hand Isolation
- [ ] Task: Write failing unit tests & layout verification for entity mask buffers in `ShaderUniformLayoutTest` and `FrameCaptureManagerTest` (Red)
    - [ ] Validate entity mask sampler bindings and uniform definitions
    - [ ] Verify entity mask texture format and initialization
- [ ] Task: Implement entity / hand render hook & mask target generation in `FrameCaptureManager` & mixins (Green)
    - [ ] Allocate `entityMaskFBO` (R8 format) in `FrameCaptureManager`
    - [ ] Add mixin hook into entity rendering / hand rendering passes to tag dynamic pixels into mask buffer
- [ ] Task: Update `reproject.fsh` to sample entity mask and bypass warping on dynamic pixels (Green)
    - [ ] Add `EntityMaskSampler` uniform in `reproject.fsh`
    - [ ] Blend dynamic entity pixels directly from `CurrentColorSampler` at full opacity without reprojection
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Screen-Edge Disocclusion Hardening & Diagnostic Overlay
- [ ] Task: Write shader uniform layout & edge fallback tests in `ShaderUniformLayoutTest` (Red)
    - [ ] Test edge falloff uniform configuration and bounds checks
- [ ] Task: Refactor `reproject.fsh` border clamping & edge disocclusion fallback (Green)
    - [ ] Implement soft border falloff and fallback to `currColor` when reprojection coords exit $[0, 1]$ screen space
    - [ ] Refine depth disocclusion rejection threshold
- [ ] Task: Update `DebugScreenOverlayMixin` with angular velocity & gating telemetry (Green)
    - [ ] Display real-time $\Delta\theta$, $\Delta d$, and Reprojection/Gated status on F3 screen
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
