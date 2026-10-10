# Product Guidelines: Universal Frame Generation Mod

## 1. User Experience & Visual Design
- **HUD & Telemetry Integration:**
  - Standard gameplay remains clean and uncluttered.
  - Seamless integration into Minecraft's `F3` Debug Screen displaying base FPS, generated FPS, and frame pacing delta.
  - Subtle, non-intrusive action bar notification when toggled via hotkey.
- **Artifact Suppression:**
  - Prioritize visual stability: HUD, menus, and text must remain completely crisp without reprojection ghosting.
  - Depth-dilation and neighborhood color clamping to mitigate fast camera pan disocclusion.

## 2. Resilience & Error Handling
- **Fail-Safe Operation:**
  - Never crash the Minecraft client if rendering/shader errors occur.
  - Gracefully fallback to standard 1x vanilla rendering if FBO allocation, depth format support, or GLSL compilation fails.
- **Resource Lifecycle:**
  - Proper FBO resizing on viewport changes and deterministic cleanup on shutdown.

## 3. Logging & Architecture Standards
- **Structured Logging (`UniversalFrameGen`):**
  - `INFO`: Initialization, detected GL capabilities, enable/disable toggles.
  - `WARN`: Fallback triggers and recoverable shader/FBO warnings.
  - `DEBUG`: Reprojection matrix data and frame pacing metrics.
- **Documentation Standards:**
  - Explicit documentation of mathematical coordinate spaces (NDC, View, World, Clip space) and GLSL uniform semantics.
