# Antigravity Audit & Playtest Verification Prompt (Full Phase 1–4 Upgrade + F9 Preset Toggle — v1.2.0 Rev 2)

Paste the prompt block below into **Antigravity** after extracting `Frame-boost-v1.2.0-full-upgrade.zip` to audit all four phases (`ADR-0004` and `ADR-0005`), verify the Rev 2 fixes (`synthesizeMidpointHand` alpha gating + MX330 bandwidth guard, `FramePacer` 3.5 ms Windows timer-quantum spin margin, and 100% GLSL/Java reference parity), and run the complete 62-test JUnit 5 verification suite.

## 1. Prompt to Paste into Antigravity

> **Full Audit Request: Frame Boost v1.2.0 Rev 2 (Phases 1–4 Complete: Anti-Shimmer Temporal-Salience Flow, `F9` Perf/Quality Presets, Depth-Occluded Entity MVs & 3-Layer** $T=0.5$ **Viewmodel Interpolation)**
>
> Please extract the updated files from `Frame-boost-v1.2.0-full-upgrade.zip` into the workspace and run a full architectural, mathematical, and build verification audit against `.agents/decisions/ADR-0004-depth-dilation-optical-flow-entity-velocity.md` and `.agents/decisions/ADR-0005-three-layer-viewmodel-interpolation-and-flow-presets.md`:
>
> 1. **Execute Automated Test & Build Suite:**
>
>    * Run `.\gradlew.bat clean check test build --no-daemon --warning-mode all` and verify that all **10 JUnit 5 test suites (62 tests total)** pass with 0 failures and 0 Gradle deprecation warnings, producing `build/libs/framegen-1.2.0.jar` — specifically confirming both `HudCompositeShaderTest.testThreeLayerMidpointHandInterpolationMovesHandWithoutGhosting` and `CadencePacerTest.testPacingWaitExecutionBounded` pass on Windows.
>
> 2. **Verify Phase 1 & `F9` Flow Preset Contracts (`reproject.fsh`, `KeybindingHandler.java`, `FrameGenConfig.java`):**
>
>    * Confirm `computeSymmetricFlowOffset` in `reproject.fsh` (and `DEFAULT_FSH` in `ReprojectionShaderPass.java`) bypasses aligned static pixels (`bestRawCost <= 0.025 && dot(bestVel, bestVel) < 1e-10`) immediately after Stage 1A (matching `evaluateOpticalFlowSynthesis` in `ReprojectionShaderPass.java`), weights displacements by `temporalSalience = min(cost(cN, cPrevAtN), cost(cP, cCurrAtP))`, and branches cleanly between `uOpticalFlowQuality == 1` (`PERF`: 4-tap cardinal diamond + LK) and `uOpticalFlowQuality >= 2` (`QUAL`: 8-tap coarse + 4-tap fine diamond + LK).
>
>    * Confirm `evaluateDilatedDepth3x3` in `ReprojectionShaderPass.java` uses the exact 5-tap cardinal cross (`indices {1, 3, 4, 5, 7}`) matching `sampleDilatedDepth` in `reproject.fsh`.
>
>    * Confirm `KeybindingHandler.java` registers `F9` (`key.framegen.flow_preset`) cycling `PERF -> QUAL -> OFF -> PERF` and `FrameGenConfigTest.testCycleOpticalFlowQualityMode` passes.
>
> 3. **Verify Phase 2 Depth-Occluded Entity Velocity Contracts (`EntityVelocityTracker.java` & `reproject.fsh`):**
>
>    * Confirm `EntityVelocityTracker` frustum-culls offscreen/distant (>32m) entities and encodes each moving entity's nearest Reverse-Z depth into channel `B` (`encodedB`).
>
>    * Confirm `sampleEntityVelocity(texCoord, texelSize, dMid)` in `reproject.fsh` rejects sky pixels (`dMid <= 0.0001`) and rejects underground/behind-tree entities when `dMid > bestMask.b + depthTolerance`, and confirm `OpticalFlowAndDilationTest.testOccludedEntityBehindForegroundTreeIsRejectedByReverseZDepthCheck` passes.
>
> 4. **Verify Phase 3 & 4 3-Layer World/Viewmodel/GUI Separation (`FrameCaptureManager.java`, `GameRendererMixin.java`, `hud_composite.fsh`):**
>
>    * Confirm `GameRendererMixin.framegen$onAfterRender3dHud` injects at `@At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(...)V", shift = At.Shift.AFTER)` to capture `currentPostHandFBO` prior to 2D GUI rendering.
>
>    * Confirm `hud_composite.fsh` (`synthesizeMidpointHand`) gates the stationary fast-path strictly on `if (hCurr0.a > 0.35 && hPrev0.a > 0.35)`, skips non-hand pixels inside the 9-tap loop via `if (hN.a <= 0.35) continue;` (keeping panning background terrain at 22 fetches/pixel instead of 58), interpolates the moving hand to its $T = 0.5$ midpoint position while clearing vacated endpoints (`anyPrevHandNearby -> mask = 0.0`), and isolates 2D GUI elements via `MainColorSampler` vs. `CurrPostHandSampler * V`.
>
>    * Confirm `HudCompositeShaderTest.testThreeLayerMidpointHandInterpolationMovesHandWithoutGhosting` passes.

## 2. In-Game Playtest Checklist (`v1.2.0` Rev 2)

1. **`F9` Optical Flow Preset Toggle & Unrecorded FPS Check:**

   * Press `F3` to see `Flow: PERF` on the `[FrameGen 2x]` line, then press `F9` to cycle between `PERFORMANCE (4-Tap Fast MX330 Mode)`, `QUALITY (12-Tap Ultra Flow Mode)`, and `OFF`.

   * **Expected:** In `PERF` mode, unrecorded FPS scales to \~160–180+ effective FPS with zero stationary tree/cloud shimmer.

2. **First-Person Hand & Weapon Swing Smoothness (Phase 3 & 4):**

   * Walk while holding an item or swing a sword/pickaxe across the screen while Frame Generation is enabled (`F8`).

   * **Expected:** First-person hand bobbing and weapon swings interpolate smoothly at 2x ($T = 0.5$) while the crosshair and hotbar remain locked and crisp.

3. **Visible vs. Underground Mob Check (Phase 2):**

   * Watch a visible walking mob in front of you vs. standing near underground caves (`EntMV > 0`).

   * **Expected:** Visible mobs interpolate smoothly at 2x, while occluded underground mobs never distort foreground trees or sky.
