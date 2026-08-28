# Implementation Plan: Universal Frame Generation Mod MVP (2x Reprojection Engine)

## Phase 1: Project Scaffolding & Configuration [checkpoint: 2e79731]
- [x] Task: Initialize Fabric Mod Scaffold 0fa333e
  - [x] Configure `build.gradle`, `settings.gradle`, `gradle.properties` (Java 25, Fabric Loom, MC 26.2, Fabric API, JOML)
  - [x] Configure `fabric.mod.json` and `framegen.mixins.json`
- [x] Task: Implement Config & Keybinding Subsystem 2e79731
  - [x] Write unit tests for JSON config serialization, default validation, and toggle state (Red Phase)
  - [x] Implement `FrameGenConfig` and keybinding registration (Green Phase)
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 2: Matrix Mathematics & Reprojection Engine [checkpoint: 6b5f1b5]
- [x] Task: Write Tests for Matrix Reprojection & Unprojection 9f0508c
  - [x] Unit tests for NDC-to-World unprojection via inverse view-projection matrix (Red Phase)
  - [x] Unit tests for World-to-Previous-Clip reprojection and camera motion deltas (Red Phase)
- [x] Task: Implement Camera Matrix Tracker 6b5f1b5
  - [x] Implement `CameraMatrixTracker` caching current/previous View & Projection matrices (Green Phase)
  - [x] Implement matrix interpolation and delta calculation for intermediate frame ($t = 0.5$)
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Frame Capture Subsystem & FBO Management [checkpoint: b3b4d77]
- [x] Task: Write Tests & Scaffold Frame Capture Buffer Lifecycle 7fb86b9
  - [x] Unit tests for RenderTarget lifecycle, resize events, and buffer swapping (Red Phase)
- [x] Task: Implement `FrameCaptureManager` b3b4d77
  - [x] Implement dual persistent `RenderTarget` buffers (`currentFrameFBO`, `previousFrameFBO`)
  - [x] Implement color buffer copy and non-linear depth texture capture before HUD pass (Green Phase)
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 4: GLSL Reprojection Shader & Post-Processing Pass
- [ ] Task: Define Shader Pipeline & Uniform Bindings
  - [ ] Unit tests for shader uniform layout, quad vertex format, and blend factors (Red Phase)
- [ ] Task: Implement `reproject.vsh` and `reproject.fsh`
  - [ ] Write GLSL vertex shader for fullscreen blit quad
  - [ ] Write GLSL fragment shader with depth unprojection, reprojection warp, depth-dilation, and color clamping (Green Phase)
  - [ ] Implement `ReprojectionShaderPass` managing shader lifecycle and uniform uploads
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 5: Pipeline Hooks, Frame Pacing & End-to-End Presentation
- [ ] Task: Implement `GameRendererMixin` Interception
  - [ ] Hook `GameRenderer.render()` immediately prior to HUD drawing to capture 3D scene buffers
- [ ] Task: Implement `FramePacer` Presentation Engine
  - [ ] Regulate presentation timing between base engine frames and generated intermediate frames
  - [ ] Ensure `InGameHud` is rendered independently on top of both engine and generated frames
- [ ] Task: Implement F3 Debug Overlay Telemetry
  - [ ] Display engine FPS, effective 2x FPS, and reprojection time in debug screen
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
