# Frame Boost 🚀
### Hardware-Agnostic 2x Frame Generation for Minecraft Java Edition

[![Build Status](https://github.com/Sayanthegamer/Frame-boost/actions/workflows/build.yml/badge.svg)](https://github.com/Sayanthegamer/Frame-boost/actions/workflows/build.yml)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-brightgreen.svg)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/ModLoader-Fabric-blue.svg)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25%2B-orange.svg)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**Frame Boost** is a lightweight, hardware-agnostic **2x Frame Generation** mod for Minecraft Java Edition (Fabric Loader, Blaze3D / OpenGL 3.3+). It doubles your perceived refresh rate and visual fluidity by synthesizing and interleaving intermediate frames ($N + 0.5$) between base engine frames using **Camera Matrix Reprojection** and **Depth Buffer Unprojection**.

Unlike proprietary vendor solutions (DLSS 3, FSR 3) or heavy neural network models, Frame Boost runs pure GLSL compute/fragment shaders with sub-millisecond overhead—working smoothly across NVIDIA, AMD, Intel, and Apple Silicon GPUs without hardware lock-in.

---

## ✨ Features

- **⚡ True 2x Visual Fluidity:** Interleaves an intermediate reprojected frame at $t = 0.5$ between every base game frame.
- **🌐 Hardware-Agnostic:** Pure OpenGL 3.3+ Core Profile shader pipeline. No tensor cores, optical flow accelerators, or specific GPU architectures required.
- **📐 3D Level Depth Reprojection:** Unprojects non-linear depth buffers to 3D world space and reprojects into previous camera coordinates with depth-dilation and edge color clamping.
- **🔄 Minecraft 26.2 Reverse-Z Architecture:** Custom-tailored for Mojang's Reverse-Z depth buffer ($1.0 = \text{near}, 0.0 = \text{far}$) with pitch singularity guards.
- **🎯 Crisp HUD & GUI Pass:** Intercepts 3D geometry before GUI compositing, guaranteeing that chat, crosshair, health bars, inventory, and menus remain 100% crisp and artifact-free.
- **⏱️ Precision Micro-Pacing Engine:** High-resolution frame pacer prevents micro-stutter and maintains consistent frame delivery.
- **📊 Real-time F3 Telemetry:** Built-in performance metrics integrated directly into the debug overlay.

---

## 🎮 Keybindings & Controls

| Default Key | Action | Description |
| :--- | :--- | :--- |
| **`G`** | **Toggle Frame Generation** | Instantly enable or disable 2x frame generation at runtime. |
| **`H`** | **Toggle Status Overlay** | Show or hide the on-screen Frame Boost active indicator. |
| **`F3`** | **Debug Telemetry** | Displays base FPS, generated frame pacing, and shader execution metrics. |

Keybindings can be customized under **Options -> Controls -> Key Binds -> Frame Boost**.

---

## ⚙️ Configuration

Configuration is automatically saved to `.minecraft/config/framegen.json`:

```json
{
  "enabled": true,
  "showStatusIndicator": true
}
```

---

## 📋 Requirements

- **Minecraft:** `26.2`
- **Mod Loader:** [Fabric Loader](https://fabricmc.net/) `0.19.3` or higher
- **Library:** [Fabric API](https://modrinth.com/mod/fabric-api)
- **Java:** `Java 25` or higher
- **Graphics:** OpenGL 3.3+ Core Profile compatible GPU

---

## 🛠️ Building from Source

To compile Frame Boost locally:

### 1. Clone the repository
```bash
git clone https://github.com/Sayanthegamer/Frame-boost.git
cd Frame-boost
```

### 2. Build with Gradle
- **Linux / macOS:**
  ```bash
  ./gradlew build
  ```
- **Windows (PowerShell / Command Prompt):**
  ```powershell
  .\gradlew.bat build
  ```

### 3. Retrieve the Mod JAR
Compiled binaries will be located in:
```
build/libs/framegen-1.0.0.jar
```

---

## 📥 Installation

1. Download and install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft `26.2`.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) for Minecraft `26.2`.
3. Place `fabric-api.jar` and `framegen-1.0.0.jar` into your `.minecraft/mods` folder.
4. Launch the game using the Fabric profile.

---

## 🤝 Contributing

Contributions, bug reports, and feature proposals are welcome!

1. Fork the project.
2. Create your feature branch (`git checkout -b feat/amazing-feature`).
3. Commit your changes (`git commit -m "feat: Add amazing feature"`).
4. Push to your branch (`git push origin feat/amazing-feature`).
5. Open a Pull Request on [GitHub](https://github.com/Sayanthegamer/Frame-boost/pulls).

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

Developed with ❤️ by **[Sayanthegamer](https://github.com/Sayanthegamer)**.
