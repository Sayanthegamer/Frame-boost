# Frame Boost 🚀
### Hardware-Agnostic 2x Frame Generation for Minecraft Java Edition

[![Build Status](https://github.com/Sayanthegamer/Frame-boost/actions/workflows/build.yml/badge.svg)](https://github.com/Sayanthegamer/Frame-boost/actions/workflows/build.yml)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.3-brightgreen.svg)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/ModLoader-Fabric-blue.svg)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25%2B-orange.svg)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**Frame Boost** is a high-performance, open-source **2x Frame Generation** mod for Minecraft Java Edition (Fabric Loader, Blaze3D / OpenGL 3.3+). It doubles perceived visual fluidity and camera motion smoothness by synthesizing and interleaving an intermediate frame ($T = 0.5$) between base engine frames using **Camera Motion Reprojection** and **Depth Buffer Unprojection**.

Unlike proprietary vendor solutions (DLSS 3, FSR 3) or heavy neural network models, Frame Boost runs pure GLSL fragment shaders with sub-millisecond overhead (~0.15–0.19 ms)—working natively across **NVIDIA, AMD, Intel, and Apple Silicon GPUs** with zero hardware lock-in.

---

## ✨ Features

- **⚡ True 2x Visual Fluidity:** Interleaves an intermediate reprojected frame at $T = 0.5$ between every base game frame.
- **🌐 Hardware-Agnostic:** Pure OpenGL 3.3+ Core Profile shader pipeline. No tensor cores, optical flow accelerators, or RTX/RDNA requirements.
- **📐 3D World Depth Reprojection:** Unprojects Blaze3D depth buffers to 3D world space and reprojects them across camera rotations and translations with iterative depth refinement.
- **🔄 Modern Reverse-Z Architecture:** Custom-tailored for Mojang's Reverse-Z depth buffer ($1.0 = \text{near}, 0.0 = \text{far}$) with far-plane sky tracking.
- **🎯 Seamless HUD & Vignette Compositing:** Dual-pass post-processing with continuous luminance tracking and smoothstep chromatic isolation. Crosshairs, hotbars, text, hearts, and screen vignettes render crisp with zero outline artifacts or flickering.
- **💡 100% Fullbright & Lightmap Compatible:** Operates downstream of terrain and block lighting, preserving all gamma tweaks, Night Vision, and lighting mods transparently.
- **🛡️ Zero DWM Desktop Bleed & Alpha Sanitization:** Hardware-masked alpha clearing (`A = 1.0`) across `mainRenderTarget` and default swapchain `FBO 0`, permanently preventing Windows 11 DWM, Windhawk, or translucent window compositors from bleeding desktop wallpapers through unrendered sky and fog.
- **🌌 Nether & End Divergence Damping:** Smoothstep chromatic divergence ramp on unprojected sky pixels eliminates 50% opacity ghosting on Nether ash particles, End portal beacons, and dynamic celestial edges.
- **🖥️ GUI-Aware Frame Pacing:** Intelligently pauses intermediate 3D frame generation while inventories, chat, or pause menus are open, keeping background menu blur stable and mouse input crisp.
- **📊 Real-time F3 Telemetry:** Displays base FPS, output refresh rate, CPU render work time, and reprojection shader duration directly in the F3 screen.

---

## 💡 How It Works & Architecture

Frame Boost implements **Camera Motion Reprojection** (conceptually similar to VR Asynchronous Timewarp and driver-level frame pacing):

1. **World & Depth Capture:** Before hand and GUI rendering, the mod intercepts the 3D world color buffer and the Reverse-Z depth buffer.
2. **Camera Quaternion Slerp:** The camera orientation between consecutive frames is smoothly interpolated at midpoint $T = 0.5$ using quaternion spherical linear interpolation (`slerp`).
3. **Midpoint Shader Reprojection:** The fragment shader reconstructs 3D world coordinates for each pixel and reprojects them into the midpoint camera view.
4. **HUD & Viewmodel Compositing:** A dedicated second pass blends the 2D interface (crosshair, chat, hotbar, text) and screen vignette over the reprojected frame.

> [!NOTE]
> **Static World vs. Mob Animations:**
> Because Frame Boost reprojects based on camera matrices and depth, the entire static 3D world (blocks, terrain, sky, lighting) renders at full 2x fluidity. Moving entities (mobs, animals, items) and their skeletal animations update at the base engine frame rate.

---

## ⚡ Recommended Settings & Low-Latency Guide

To get the most responsive, lowest-latency experience:

- **Turn V-Sync OFF in Minecraft:**  
  Set **Options $\to$ Video Settings $\to$ VSync: OFF** and **Max Framerate: Unlimited** (or 120+ FPS).  
  *Why?* With V-Sync ON (`FIFO`) on a 60Hz display, presenting both base and intermediate frames can force the engine loop down to 30 FPS, increasing mouse polling latency. Turning V-Sync off keeps mouse input fast and snappy!
- **Ideal on High-Refresh Displays:**  
  Frame Boost delivers an incredible experience on 120Hz, 144Hz, or 240Hz monitors, turning 60–75 base FPS into 120–144+ FPS fluid motion.
- **Great for Heavy Shaders:**  
  If shaders drop your GPU to 30–40 FPS, Frame Boost smooths camera panning up to 60–80 FPS visual fluidity.

---

## 🎮 Keybindings & Controls

| Default Key | Action | Description |
| :--- | :--- | :--- |
| **`G`** | **Toggle Frame Generation** | Instantly enable or disable 2x frame generation at runtime. |
| **`H`** | **Toggle Status Overlay** | Show or hide the on-screen Frame Boost active indicator. |
| **`F3`** | **Debug Telemetry** | View base FPS, effective Hz, CPU work time, and reprojection latency. |

Keybindings can be customized under **Options -> Controls -> Key Binds -> Frame Boost**.

---

## ⚙️ Configuration

Configuration is automatically saved to `.minecraft/config/framegen.json`:

```json
{
  "enabled": true,
  "interpolationFactor": 0.5,
  "debugOverlayEnabled": true,
  "depthDilationRadius": 1.0,
  "velocityGatingEnabled": true,
  "maxAngularVelocityThreshold": 720.0,
  "maxTranslationVelocityThreshold": 50.0,
  "maxAbsoluteAngleThreshold": 15.0,
  "maxFrameDurationThresholdMs": 50.0,
  "cadencePacingEnabled": false,
  "debugLadderTintEnabled": false
}
```

---

## 📋 Requirements

- **Minecraft:** `26.3`
- **Mod Loader:** [Fabric Loader](https://fabricmc.net/) `0.19.5` or higher
- **Library:** [Fabric API](https://modrinth.com/mod/fabric-api)
- **Java:** `Java 25` or higher
- **Graphics:** OpenGL 3.3+ Core Profile compatible GPU

---

## 🛠️ Building from Source

```bash
# Clone the repository
git clone https://github.com/Sayanthegamer/Frame-boost.git
cd Frame-boost

# Build with Gradle (Windows)
.\gradlew.bat build

# Build with Gradle (Linux / macOS)
./gradlew build
```

Compiled mod JAR will be located in `build/libs/framegen-1.0.4.jar`.

---

## 📥 Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft `26.2`.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) for Minecraft `26.2`.
3. Drop `fabric-api.jar` and `framegen-1.0.4.jar` into your `.minecraft/mods` folder.
4. Launch the game with the Fabric profile.

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

Developed with ❤️ by **[Sayanthegamer](https://github.com/Sayanthegamer)**.
