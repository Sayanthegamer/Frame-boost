# Technology Stack: Universal Frame Generation Mod

## Runtime & Toolchain
- **Language:** Java 25+
- **Build System:** Gradle with Fabric Loom
- **Mod Loader:** Fabric Loader
- **Target Platform:** Minecraft Java Edition 26.2 (Official Mojang Mappings)
- **CI / CD:** GitHub Actions (Java 25 Temurin, Gradle Build & Check, Artifact Upload)

## Graphics & Rendering Architecture
- **Rendering Engine:** Mojang Blaze3D (`com.mojang.blaze3d.pipeline.RenderTarget`)
- **Graphics API:** OpenGL 3.3+ Core Profile (LWJGL 3)
- **Shader Pipeline:** GLSL post-processing fragment shaders (`reproject.fsh`)
- **Math & Linear Algebra:** JOML (`org.joml.Matrix4f`, `org.joml.Vector4f`)

## Dependencies & Utilities
- **Fabric Ecosystem:** Fabric API (Resource Loader, KeyBinding API, Client Lifecycle Events)
- **Configuration:** Google Gson (`com.google.gson`)
- **Logging:** SLF4J / Apache Log4j 2
- **Mixin Framework:** SpongePowered Mixin 0.8+
