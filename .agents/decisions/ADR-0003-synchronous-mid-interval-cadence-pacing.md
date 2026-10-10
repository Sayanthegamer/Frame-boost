# ADR-0003: Synchronous Mid-Interval Cadence Pacing for 2x Frame Presentation

## Status
Accepted (Ratified 2026-10-11; Authorized by Human Engineer)

## Context & Problem Statement
Under Option A ($T=0.5$ Half-Step Midpoint Reprojection), an intermediate frame and a base engine frame are synthesized during the client render tick.
Empirical diagnostic telemetry probes revealed that `presentIntermediateFrame()` and Minecraft's native `windowSurface.present()` were executing back-to-back within **$0.093\,\text{ms}$ (93 microseconds)** on the render thread.

Because the Windows Desktop Window Manager (DWM) and display hardware scan out at $60\,\text{Hz}$ ($16.67\,\text{ms}$ refresh intervals), submitting two frames in a $0.093\,\text{ms}$ burst causes DWM to overwrite the intermediate frame with the base frame before the monitor scans it out. The display was locked to displaying real frames at $30\,\text{Hz}$, defeating visual fluidity.

## Decision Classification (Tier 1 Consequential)
- **Vector 4 (Performance & Scaling):** Bounded pacing interval to prevent frame-drop harmonic collapse under variable base frame rates.
- **Vector 6 (Concurrency, Synchronization & Execution Model):** Pacing synchronization between intermediate swapchain presentation and base engine swapchain presentation.
- **Vector 7 (Public & Module Interfaces):** Mixin injection timing in `Minecraft.renderFrame()` between `GameRenderer.render()` and `windowSurface.present()`.

## Alternatives Considered
1. **Interleaved Sub-Tick Dispatch (Alternate `runTick` Simulation):**
   - *Pros:* Relies on native GLFW V-Sync blocking on each swap.
   - *Cons:* High blast radius; breaks network packet handling (`ClientPacketListener`), sound engine, and causes severe incompatibility with Sodium/Lithium/Iris mod hooks.
2. **Synchronous Mid-Interval Cadence Pacing (Selected):**
   - *Pros:* Surgical blast radius on render thread presentation boundary; holds intermediate frame on display for a full half-interval ($\Delta t_{\text{half}} \approx 16.6\,\text{ms}$ at 30 FPS base); adaptive budgeting ensures zero lag spikes if base render time increases; 100% compatible with existing mods.
   - *Cons:* Requires high-precision hybrid wait on the render thread.

## Ratified Architecture & Implementation Invariants
1. **Target Half-Step Interval Calculation:**
   $$\Delta t_{\text{half}} = \frac{1}{2} \cdot \Delta t_{\text{base}} = \frac{1000}{2 \cdot R_{\text{base}}}\,(\text{ms})$$
   where $R_{\text{base}}$ is the moving-average base framerate recorded by `FramePacer`.
2. **Hybrid High-Precision Sleep/Spin Wait:**
   - Immediately following `presentIntermediateFrame()`:
     The render thread waits for $\Delta t_{\text{half}}$ minus any elapsed work time.
     - Long wait portion ($> 1.5\,\text{ms}$): `LockSupport.parkNanos()` or `Thread.sleep(1)` to minimize CPU consumption.
     - Terminal alignment portion ($\le 1.5\,\text{ms}$): High-precision busy spin against `System.nanoTime()` to achieve sub-millisecond ($< 0.1\,\text{ms}$) vblank alignment.
3. **Adaptive Under-Budgeting Guard:**
   If $\Delta t_{\text{half}} \le \text{elapsedWorkTime}$, the wait is clamped to $0$ to never stall or degrade base engine framerate.
4. **Gating Bypass:**
   When a frame is gated (`isVelocityGated() == true`), intermediate presentation is skipped and pacing sleep is bypassed entirely, presenting the base frame immediately.
