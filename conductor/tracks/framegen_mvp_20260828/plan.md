# Implementation Plan: Universal Frame Generation Mod MVP (2x Reprojection Engine)

## Phase 1: Project Scaffolding & Configuration
- [ ] Task: Initialize Fabric Mod Scaffold
  - [ ] Configure `build.gradle`, `settings.gradle`, `gradle.properties` (Java 25, Fabric Loom, MC 26.2, Fabric API, JOML)
  - [ ] Configure `fabric.mod.json` and `framegen.mixins.json`
- [ ] Task: Implement Config & Keybinding Subsystem
  - [ ] Write unit tests for JSON config serialization, default validation, and toggle state (Red Phase)
  - [ ] Implement `FrameGenConfig` and keybinding registration (Green Phase)
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 2: Matrix Mathematics & Reprojection Engine
- [ ] Task: Write Tests for Matrix Reprojection & Unprojection
  - [ ] Unit tests for NDC-to-World unprojection via inverse view-projection matrix (Red Phase)
  - [ ] Unit tests for World-to-Previous-Clip reprojection and camera motion deltas (Red Phase)
- [ ] Task: Implement Camera Matrix Tracker
  - [ ] Implement `CameraMatrixTracker` caching current/previous View & Projection matrices (Green Phase)
  - [ ] Implement matrix interpolation and delta calculation for intermediate frame ($t = 0.5$)
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

## Phase 3: Frame Capture Subsystem & FBO Management
- [ ] Task: Write Tests & Scaffold Frame Capture Buffer Lifecycle
  - [ ] Unit tests for RenderTarget lifecycle, resize events, and buffer swapping (Red Phase)
- [ ] Task: Implement `FrameCaptureManager`
  - [ ] Implement dual persistent `RenderTarget` buffers (`currentFrameFBO`, `previousFrameFBO`)
  - [ ] Implement color buffer copy and non-linear depth texture capture before HUD pass (Green Phase)
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

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
