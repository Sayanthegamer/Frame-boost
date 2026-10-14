package com.framegen.shader;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import com.framegen.config.FrameGenConfig;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class ReprojectionShaderPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("ReprojectionShaderPass");

    private static final String DEFAULT_VSH = """
        #version 330 core
        layout (location = 0) in vec2 Position;
        layout (location = 1) in vec2 UV;
        out vec2 texCoord;
        void main() {
            texCoord = UV;
            gl_Position = vec4(Position, 0.0, 1.0);
        }
        """;

    private static final String DEFAULT_FSH = """
        #version 330 core
        in vec2 texCoord;
        out vec4 fragColor;
        uniform sampler2D CurrentColorSampler;
        uniform sampler2D PreviousColorSampler;
        uniform sampler2D CurrentDepthSampler;
        uniform sampler2D PreviousDepthSampler;
        uniform sampler2D EntityMaskSampler;
        uniform mat4 MidToPrevMatrix;
        uniform mat4 MidToCurrMatrix;
        uniform mat4 MidToPrevSkyMatrix;
        uniform mat4 MidToCurrSkyMatrix;
        uniform vec2 ScreenSize;
        uniform float BlendFactor;
        uniform float DepthDilationRadius;
        uniform int uDebugLadderMode;

        void main() {
            // 1. Initial depth seeds from both frames at current midpoint screen coordinate
            float dN = texture(CurrentDepthSampler, texCoord).r;
            float dP = texture(PreviousDepthSampler, texCoord).r;

            // Iterative refinement pass 1 & 2 for Frame N candidate
            // In Blaze3D with glClipControl(GL_ZERO_TO_ONE), zNDC == depth in [0, 1]
            for (int i = 0; i < 2; ++i) {
                float zN = dN;
                vec4 pN = vec4(texCoord * 2.0 - 1.0, zN, 1.0);
                vec4 cN = MidToCurrMatrix * pN;
                if (cN.w > 0.0) {
                    vec2 uvN = (cN.xy / max(cN.w, 0.00001)) * 0.5 + 0.5;
                    if (uvN.x >= 0.0 && uvN.x <= 1.0 && uvN.y >= 0.0 && uvN.y <= 1.0) {
                        dN = texture(CurrentDepthSampler, uvN).r;
                    }
                }
            }

            // Iterative refinement pass 1 & 2 for Frame N-1 candidate
            for (int i = 0; i < 2; ++i) {
                float zP = dP;
                vec4 pP = vec4(texCoord * 2.0 - 1.0, zP, 1.0);
                vec4 cP = MidToPrevMatrix * pP;
                if (cP.w > 0.0) {
                    vec2 uvP = (cP.xy / max(cP.w, 0.00001)) * 0.5 + 0.5;
                    if (uvP.x >= 0.0 && uvP.x <= 1.0 && uvP.y >= 0.0 && uvP.y <= 1.0) {
                        dP = texture(PreviousDepthSampler, uvP).r;
                    }
                }
            }

            // Arbitrate Single Midpoint Surface:
            // In Reverse-Z, larger depth is nearer surface: dMid = max(dN, dP)
            float dMid = max(dN, dP);

            // Sky Decision: Sky (dMid <= 0.0001) is evaluated strictly after refinement
            // In GL_ZERO_TO_ONE Reverse-Z, far sky plane is z = 0.0
            if (dMid <= 0.0001) {
                vec4 skyPoint = vec4(texCoord * 2.0 - 1.0, 0.0, 1.0);
                vec4 cSkyCurr = MidToCurrSkyMatrix * skyPoint;
                vec4 cSkyPrev = MidToPrevSkyMatrix * skyPoint;

                vec2 uvSkyCurr = (cSkyCurr.xy / max(cSkyCurr.w, 0.00001)) * 0.5 + 0.5;
                vec2 uvSkyPrev = (cSkyPrev.xy / max(cSkyPrev.w, 0.00001)) * 0.5 + 0.5;

                bool skyCurrValid = (uvSkyCurr.x >= 0.0 && uvSkyCurr.x <= 1.0 && uvSkyCurr.y >= 0.0 && uvSkyCurr.y <= 1.0 && cSkyCurr.w > 0.0);
                bool skyPrevValid = (uvSkyPrev.x >= 0.0 && uvSkyPrev.x <= 1.0 && uvSkyPrev.y >= 0.0 && uvSkyPrev.y <= 1.0 && cSkyPrev.w > 0.0);

                if (skyCurrValid && skyPrevValid) {
                    vec4 colCurr = texture(CurrentColorSampler, uvSkyCurr);
                    vec4 colPrev = texture(PreviousColorSampler, uvSkyPrev);
                    vec3 diff = abs(colCurr.rgb - colPrev.rgb);
                    float deltaC = max(diff.r, max(diff.g, diff.b));
                    float ramp = smoothstep(0.05, 0.25, deltaC);
                    float wPrev = BlendFactor * (1.0 - ramp);
                    fragColor = vec4(mix(colCurr.rgb, colPrev.rgb, wPrev), 1.0);
                } else if (skyCurrValid) {
                    fragColor = vec4(texture(CurrentColorSampler, uvSkyCurr).rgb, 1.0);
                    if (uDebugLadderMode == 1) fragColor = mix(fragColor, vec4(0.0, 1.0, 0.0, 1.0), 0.7);
                } else if (skyPrevValid) {
                    fragColor = vec4(texture(PreviousColorSampler, uvSkyPrev).rgb, 1.0);
                    if (uDebugLadderMode == 1) fragColor = mix(fragColor, vec4(0.0, 0.0, 1.0, 1.0), 0.7);
                } else {
                    fragColor = vec4(texture(CurrentColorSampler, texCoord).rgb, 1.0);
                    if (uDebugLadderMode == 1) fragColor = mix(fragColor, vec4(1.0, 0.0, 0.0, 1.0), 0.7);
                }
                return;
            }

            // 2. Validate BOTH Sources against single mid-surface point P_mid
            // In GL_ZERO_TO_ONE, zNDC == dMid
            float zNDC = dMid;
            vec4 pMid = vec4(texCoord * 2.0 - 1.0, zNDC, 1.0);

            vec4 clipN = MidToCurrMatrix * pMid;
            vec3 ndcN = clipN.xyz / max(clipN.w, 0.00001);
            vec2 uvN = ndcN.xy * 0.5 + 0.5;
            float expectedDepthN = ndcN.z;

            vec4 clipP = MidToPrevMatrix * pMid;
            vec3 ndcP = clipP.xyz / max(clipP.w, 0.00001);
            vec2 uvP = ndcP.xy * 0.5 + 0.5;
            float expectedDepthP = ndcP.z;

            bool uvInBoundsN = (uvN.x >= 0.0 && uvN.x <= 1.0 && uvN.y >= 0.0 && uvN.y <= 1.0 && clipN.w > 0.0);
            bool uvInBoundsP = (uvP.x >= 0.0 && uvP.x <= 1.0 && uvP.y >= 0.0 && uvP.y <= 1.0 && clipP.w > 0.0);

            float tauN = max(0.05 * max(expectedDepthN, 0.001), 0.005);
            float tauP = max(0.05 * max(expectedDepthP, 0.001), 0.005);

            bool validN = false;
            if (uvInBoundsN) {
                float actualDepthN = texture(CurrentDepthSampler, uvN).r;
                validN = abs(actualDepthN - expectedDepthN) <= tauN;
            }

            bool validP = false;
            if (uvInBoundsP) {
                float actualDepthP = texture(PreviousDepthSampler, uvP).r;
                validP = abs(actualDepthP - expectedDepthP) <= tauP;
            }

            // 3. Synthesis Ladder
            if (validN && validP) {
                vec4 currColor = texture(CurrentColorSampler, uvN);
                vec4 prevColor = texture(PreviousColorSampler, uvP);

                vec3 diff = abs(currColor.rgb - prevColor.rgb);
                float deltaC = max(diff.r, max(diff.g, diff.b));
                float ramp = smoothstep(0.05, 0.25, deltaC);

                float wBase = BlendFactor;
                float wPrev = wBase * (1.0 - ramp);
                float wCurr = 1.0 - wPrev;

                vec3 synth = wCurr * currColor.rgb + wPrev * prevColor.rgb;
                fragColor = vec4(synth, 1.0);
            } else if (validN) {
                vec4 currColor = texture(CurrentColorSampler, uvN);
                if (uDebugLadderMode == 1) {
                    fragColor = vec4(mix(currColor.rgb, vec3(0.0, 1.0, 0.0), 0.7), 1.0);
                } else {
                    fragColor = vec4(currColor.rgb, 1.0);
                }
            } else if (validP) {
                vec4 prevColor = texture(PreviousColorSampler, uvP);
                if (uDebugLadderMode == 1) {
                    fragColor = vec4(mix(prevColor.rgb, vec3(0.0, 0.0, 1.0), 0.7), 1.0);
                } else {
                    fragColor = vec4(prevColor.rgb, 1.0);
                }
            } else {
                vec4 fallbackColor = texture(CurrentColorSampler, texCoord);
                if (uDebugLadderMode == 1) {
                    fragColor = vec4(mix(fallbackColor.rgb, vec3(1.0, 0.0, 0.0), 0.7), 1.0);
                } else {
                    fragColor = vec4(fallbackColor.rgb, 1.0);
                }
            }
        }
        """;

    private static final String DEFAULT_HUD_FSH = """
        #version 330 core
        in vec2 texCoord;
        out vec4 fragColor;
        uniform sampler2D IntermediateSampler;
        uniform sampler2D MainColorSampler;
        uniform sampler2D WorldOnlySampler;
        void main() {
            vec4 interColor = texture(IntermediateSampler, texCoord);
            vec4 mainColor = texture(MainColorSampler, texCoord);
            vec4 worldColor = texture(WorldOnlySampler, texCoord);

            // Compute luminance for world and main
            float lumWorld = dot(worldColor.rgb, vec3(0.299, 0.587, 0.114));
            float lumMain = dot(mainColor.rgb, vec3(0.299, 0.587, 0.114));

            // Continuous Vignette Transmission Factor V:
            // In vanilla Minecraft, the vignette is purely subtractive/multiplicative: V <= 1.0.
            // Using smoothstep transition near pitch black avoids any 8-bit division noise.
            float vRatio = lumMain / max(lumWorld, 0.0001);
            float vClamped = clamp(vRatio, 0.0, 1.0);
            float lumWeight = smoothstep(0.001, 0.01, lumWorld);
            float V = mix(1.0, vClamped, lumWeight);

            // Intermediate world with continuous vignette applied:
            vec3 interVignetted = interColor.rgb * V;

            // Detect HUD / Viewmodel elements:
            // 1. Any channel brightening beyond world color (vignette NEVER brightens)
            float brighten = max(0.0, max(mainColor.r - worldColor.r, max(mainColor.g - worldColor.g, mainColor.b - worldColor.b)));
            float hudBright = smoothstep(0.02, 0.05, brighten);

            // 2. Chromatic divergence from expected scalar vignette darkening
            vec3 expectedVignette = worldColor.rgb * V;
            vec3 diffV = abs(mainColor.rgb - expectedVignette);
            float maxDiffV = max(diffV.r, max(diffV.g, diffV.b));
            float hudDiff = smoothstep(0.03, 0.07, maxDiffV);

            float hudFactor = clamp(max(hudBright, hudDiff), 0.0, 1.0);

            fragColor = vec4(mix(interVignetted, mainColor.rgb, hudFactor), 1.0);
        }
        """;

    private int programId = 0;
    private int hudProgramId = 0;
    private int vaoId = 0;
    private int vboId = 0;
    private int fboId = 0;
    private int colorSamplerId = 0;
    private int depthSamplerId = 0;
    private boolean initialized = false;

    // Uniform locations - Reprojection
    private int uCurrentColor = -1;
    private int uPreviousColor = -1;
    private int uCurrentDepth = -1;
    private int uPreviousDepth = -1;
    private int uEntityMask = -1;
    private int uReprojectionMatrix = -1;
    private int uViewModelReprojectionMatrix = -1;
    private int uScreenSize = -1;
    private int uBlendFactor = -1;
    private int uDepthDilation = -1;
    private int uMidToPrevMatrix = -1;
    private int uMidToCurrMatrix = -1;
    private int uMidToPrevSkyMatrix = -1;
    private int uMidToCurrSkyMatrix = -1;
    private int uDebugLadderMode = -1;

    // Uniform locations - HUD Composite
    private int uHudIntermediate = -1;
    private int uHudMain = -1;
    private int uHudWorldOnly = -1;

    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer vmMatrixBuffer = BufferUtils.createFloatBuffer(16);

    public ReprojectionShaderPass() {
    }

    public synchronized void initialize() {
        if (initialized) return;

        try {
            String vshSource = loadShaderResource("/assets/framegen/shaders/reproject.vsh", DEFAULT_VSH);
            String fshSource = loadShaderResource("/assets/framegen/shaders/reproject.fsh", DEFAULT_FSH);

            int vsh = compileShader(GL20.GL_VERTEX_SHADER, vshSource);
            int fsh = compileShader(GL20.GL_FRAGMENT_SHADER, fshSource);

            programId = GL20.glCreateProgram();
            GL20.glAttachShader(programId, vsh);
            GL20.glAttachShader(programId, fsh);
            GL20.glLinkProgram(programId);

            if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(programId);
                throw new RuntimeException("Failed to link reprojection shader program: " + log);
            }

            GL20.glDeleteShader(fsh);

            // Compile HUD Composite Shader
            String hudFshSource = loadShaderResource("/assets/framegen/shaders/hud_composite.fsh", DEFAULT_HUD_FSH);
            int hudFsh = compileShader(GL20.GL_FRAGMENT_SHADER, hudFshSource);
            hudProgramId = GL20.glCreateProgram();
            GL20.glAttachShader(hudProgramId, vsh);
            GL20.glAttachShader(hudProgramId, hudFsh);
            GL20.glLinkProgram(hudProgramId);

            if (GL20.glGetProgrami(hudProgramId, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(hudProgramId);
                throw new RuntimeException("Failed to link HUD composite shader program: " + log);
            }

            GL20.glDeleteShader(vsh);
            GL20.glDeleteShader(hudFsh);

            // Query uniform locations - Reprojection
            uCurrentColor = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_CURRENT_COLOR);
            uPreviousColor = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_PREVIOUS_COLOR);
            uCurrentDepth = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_CURRENT_DEPTH);
            uPreviousDepth = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_PREVIOUS_DEPTH);
            uEntityMask = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_ENTITY_MASK);
            uReprojectionMatrix = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_REPROJECTION_MATRIX);
            uViewModelReprojectionMatrix = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_VIEWMODEL_REPROJECTION_MATRIX);
            uScreenSize = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_SCREEN_SIZE);
            uBlendFactor = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_BLEND_FACTOR);
            uDepthDilation = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_DEPTH_DILATION);
            uMidToPrevMatrix = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_MID_TO_PREV_MATRIX);
            uMidToCurrMatrix = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_MID_TO_CURR_MATRIX);
            uMidToPrevSkyMatrix = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_MID_TO_PREV_SKY_MATRIX);
            uMidToCurrSkyMatrix = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_MID_TO_CURR_SKY_MATRIX);
            uDebugLadderMode = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_DEBUG_LADDER_MODE);

            // Query uniform locations - HUD Composite
            uHudIntermediate = GL20.glGetUniformLocation(hudProgramId, ShaderConstants.UNIFORM_HUD_INTERMEDIATE);
            uHudMain = GL20.glGetUniformLocation(hudProgramId, ShaderConstants.UNIFORM_HUD_MAIN);
            uHudWorldOnly = GL20.glGetUniformLocation(hudProgramId, ShaderConstants.UNIFORM_HUD_WORLD_ONLY);

            // OpenGL 3.3 Sampler Objects (Zero texture parameter mutation)
            colorSamplerId = GL33.glGenSamplers();
            GL33.glSamplerParameteri(colorSamplerId, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL33.glSamplerParameteri(colorSamplerId, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL33.glSamplerParameteri(colorSamplerId, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL33.glSamplerParameteri(colorSamplerId, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            depthSamplerId = GL33.glGenSamplers();
            GL33.glSamplerParameteri(depthSamplerId, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL33.glSamplerParameteri(depthSamplerId, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL33.glSamplerParameteri(depthSamplerId, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL33.glSamplerParameteri(depthSamplerId, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL33.glSamplerParameteri(depthSamplerId, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);

            setupFullscreenQuad();
            fboId = GL30.glGenFramebuffers();
            initialized = true;
            LOGGER.info("ReprojectionShaderPass, HUD Composite Shader, and Samplers initialized successfully.");
        } catch (Throwable t) {
            LOGGER.error("Failed to initialize ReprojectionShaderPass", t);
            destroy();
        }
    }

    private void setupFullscreenQuad() {
        try (GlStateSnapshot ignored = GlStateSnapshot.capture()) {
            float[] quadVertices = {
                    -1.0f, -1.0f,  0.0f, 0.0f,
                     1.0f, -1.0f,  1.0f, 0.0f,
                     1.0f,  1.0f,  1.0f, 1.0f,
                    -1.0f, -1.0f,  0.0f, 0.0f,
                     1.0f,  1.0f,  1.0f, 1.0f,
                    -1.0f,  1.0f,  0.0f, 1.0f
            };

            vaoId = GL30.glGenVertexArrays();
            vboId = GL15.glGenBuffers();

            GL30.glBindVertexArray(vaoId);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);

            FloatBuffer buffer = BufferUtils.createFloatBuffer(quadVertices.length);
            buffer.put(quadVertices).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);

            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 0);
            GL20.glEnableVertexAttribArray(0);

            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
            GL20.glEnableVertexAttribArray(1);

            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }
    }

    public synchronized boolean render(
            GpuTexture currentColor,
            GpuTexture previousColor,
            GpuTexture currentDepth,
            GpuTexture previousDepth,
            GpuTexture outputColor,
            Matrix4f reprojectMatrix,
            int width,
            int height,
            float blendFactor,
            float depthDilation
    ) {
        return render(currentColor, previousColor, currentDepth, previousDepth, null, outputColor, reprojectMatrix, reprojectMatrix, width, height, blendFactor, depthDilation);
    }

    public synchronized boolean render(
            GpuTexture currentColor,
            GpuTexture previousColor,
            GpuTexture currentDepth,
            GpuTexture previousDepth,
            GpuTexture entityMask,
            GpuTexture outputColor,
            Matrix4f reprojectMatrix,
            int width,
            int height,
            float blendFactor,
            float depthDilation
    ) {
        return render(currentColor, previousColor, currentDepth, previousDepth, entityMask, outputColor, reprojectMatrix, reprojectMatrix, width, height, blendFactor, depthDilation);
    }

    public synchronized boolean render(
            GpuTexture currentColor,
            GpuTexture previousColor,
            GpuTexture currentDepth,
            GpuTexture previousDepth,
            GpuTexture entityMask,
            GpuTexture outputColor,
            Matrix4f reprojectMatrix,
            Matrix4f viewmodelReprojectMatrix,
            int width,
            int height,
            float blendFactor,
            float depthDilation
    ) {
        return render(currentColor, previousColor, currentDepth, previousDepth, entityMask, outputColor,
                reprojectMatrix, reprojectMatrix, reprojectMatrix, reprojectMatrix,
                width, height, blendFactor, depthDilation, false);
    }

    public synchronized boolean render(
            GpuTexture currentColor,
            GpuTexture previousColor,
            GpuTexture currentDepth,
            GpuTexture previousDepth,
            GpuTexture entityMask,
            GpuTexture outputColor,
            Matrix4f midToPrevMatrix,
            Matrix4f midToCurrMatrix,
            Matrix4f midToPrevSkyMatrix,
            Matrix4f midToCurrSkyMatrix,
            int width,
            int height,
            float blendFactor,
            float depthDilation,
            boolean debugLadderMode
    ) {
        if (!initialized) {
            initialize();
            if (!initialized) return false;
        }

        int targetTexId = getGlTextureId(outputColor != null ? outputColor : currentColor);
        if (targetTexId == 0) return false;

        GlStateProbe probeBefore = (FrameGenConfig.getInstance() != null && FrameGenConfig.getInstance().isDebugLogGlDrift()) ? GlStateProbe.capture() : null;

        try (GlStateSnapshot state = GlStateSnapshot.capture()) {
            // Establish clean quad pipeline state
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glColorMask(true, true, true, true);

            // Bind destination FBO
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, targetTexId, 0);

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
                LOGGER.warn("Intermediate framebuffer incomplete: 0x{}", Integer.toHexString(status));
                return false;
            }

            GL11.glViewport(0, 0, width, height);
            GL20.glUseProgram(programId);

            // Bind Textures & Sampler Objects (Zero texture mutation)
            bindTexture(GL13.GL_TEXTURE0 + ShaderConstants.TEXTURE_UNIT_CURRENT_COLOR, currentColor, uCurrentColor, ShaderConstants.TEXTURE_UNIT_CURRENT_COLOR, colorSamplerId);
            bindTexture(GL13.GL_TEXTURE0 + ShaderConstants.TEXTURE_UNIT_PREVIOUS_COLOR, previousColor, uPreviousColor, ShaderConstants.TEXTURE_UNIT_PREVIOUS_COLOR, colorSamplerId);
            bindTexture(GL13.GL_TEXTURE0 + ShaderConstants.TEXTURE_UNIT_CURRENT_DEPTH, currentDepth, uCurrentDepth, ShaderConstants.TEXTURE_UNIT_CURRENT_DEPTH, depthSamplerId);
            bindTexture(GL13.GL_TEXTURE0 + ShaderConstants.TEXTURE_UNIT_PREVIOUS_DEPTH, previousDepth, uPreviousDepth, ShaderConstants.TEXTURE_UNIT_PREVIOUS_DEPTH, depthSamplerId);
            bindTexture(GL13.GL_TEXTURE0 + ShaderConstants.TEXTURE_UNIT_ENTITY_MASK, entityMask, uEntityMask, ShaderConstants.TEXTURE_UNIT_ENTITY_MASK, colorSamplerId);

            // Upload Midpoint & Sky Uniforms
            if (uMidToPrevMatrix != -1 && midToPrevMatrix != null) {
                matrixBuffer.clear();
                midToPrevMatrix.get(matrixBuffer);
                GL20.glUniformMatrix4fv(uMidToPrevMatrix, false, matrixBuffer);
            }

            if (uMidToCurrMatrix != -1 && midToCurrMatrix != null) {
                matrixBuffer.clear();
                midToCurrMatrix.get(matrixBuffer);
                GL20.glUniformMatrix4fv(uMidToCurrMatrix, false, matrixBuffer);
            }

            if (uMidToPrevSkyMatrix != -1 && midToPrevSkyMatrix != null) {
                matrixBuffer.clear();
                midToPrevSkyMatrix.get(matrixBuffer);
                GL20.glUniformMatrix4fv(uMidToPrevSkyMatrix, false, matrixBuffer);
            }

            if (uMidToCurrSkyMatrix != -1 && midToCurrSkyMatrix != null) {
                matrixBuffer.clear();
                midToCurrSkyMatrix.get(matrixBuffer);
                GL20.glUniformMatrix4fv(uMidToCurrSkyMatrix, false, matrixBuffer);
            }

            // Backwards-compatible legacy uniforms
            if (uReprojectionMatrix != -1 && midToPrevMatrix != null) {
                matrixBuffer.clear();
                midToPrevMatrix.get(matrixBuffer);
                GL20.glUniformMatrix4fv(uReprojectionMatrix, false, matrixBuffer);
            }

            if (uViewModelReprojectionMatrix != -1 && midToPrevMatrix != null) {
                vmMatrixBuffer.clear();
                midToPrevMatrix.get(vmMatrixBuffer);
                GL20.glUniformMatrix4fv(uViewModelReprojectionMatrix, false, vmMatrixBuffer);
            }

            if (uScreenSize != -1) {
                GL20.glUniform2f(uScreenSize, (float) width, (float) height);
            }

            if (uBlendFactor != -1) {
                GL20.glUniform1f(uBlendFactor, blendFactor);
            }

            if (uDepthDilation != -1) {
                GL20.glUniform1f(uDepthDilation, depthDilation);
            }

            if (uDebugLadderMode != -1) {
                GL20.glUniform1i(uDebugLadderMode, debugLadderMode ? 1 : 0);
            }

            // Draw Fullscreen Quad
            GL30.glBindVertexArray(vaoId);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);

            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
            return true;
        } catch (Throwable t) {
            LOGGER.error("Error executing ReprojectionShaderPass.render", t);
            return false;
        } finally {
            if (probeBefore != null) {
                GlStateProbe probeAfter = GlStateProbe.capture();
                probeBefore.compareAndLog("ReprojectionShaderPass.render", probeAfter);
            }
        }
    }

    public synchronized boolean compositeHud(
            GpuTexture intermediateTexture,
            GpuTexture mainTextureWithHud,
            GpuTexture worldTextureOnly,
            GpuTexture outputTexture,
            int width,
            int height
    ) {
        if (!initialized) {
            initialize();
            if (!initialized) return false;
        }

        int targetTexId = getGlTextureId(outputTexture != null ? outputTexture : intermediateTexture);
        if (targetTexId == 0) return false;

        GlStateProbe probeBefore = (FrameGenConfig.getInstance() != null && FrameGenConfig.getInstance().isDebugLogGlDrift()) ? GlStateProbe.capture() : null;

        try (GlStateSnapshot state = GlStateSnapshot.capture()) {
            // Establish clean quad pipeline state
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glColorMask(true, true, true, true);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, targetTexId, 0);

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
                LOGGER.warn("HUD composite framebuffer incomplete: 0x{}", Integer.toHexString(status));
                return false;
            }

            GL11.glViewport(0, 0, width, height);
            GL20.glUseProgram(hudProgramId);

            bindTexture(GL13.GL_TEXTURE0, intermediateTexture, uHudIntermediate, 0, colorSamplerId);
            bindTexture(GL13.GL_TEXTURE1, mainTextureWithHud, uHudMain, 1, colorSamplerId);
            bindTexture(GL13.GL_TEXTURE2, worldTextureOnly, uHudWorldOnly, 2, colorSamplerId);

            GL30.glBindVertexArray(vaoId);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);

            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
            return true;
        } catch (Throwable t) {
            LOGGER.error("Error executing ReprojectionShaderPass.compositeHud", t);
            return false;
        } finally {
            if (probeBefore != null) {
                GlStateProbe probeAfter = GlStateProbe.capture();
                probeBefore.compareAndLog("ReprojectionShaderPass.compositeHud", probeAfter);
            }
        }
    }

    /**
     * Forces the alpha channel of the given color texture to 1.0 (fully opaque) without modifying RGB values.
     * Prevents Windows DWM / translucent window compositors from bleeding desktop wallpaper through sky/fog pixels
     * where Minecraft clears or renders fog with alpha = 0.0, and eliminates opaque-vs-transparent frame strobing.
     */
    public synchronized boolean sanitizeAlpha(GpuTexture targetTexture, int width, int height) {
        if (targetTexture == null || width <= 0 || height <= 0) {
            return false;
        }
        if (!initialized) {
            initialize();
            if (!initialized) return false;
        }

        int targetTexId = getGlTextureId(targetTexture);
        if (targetTexId == 0) return false;

        try (GlStateSnapshot state = GlStateSnapshot.capture()) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, targetTexId, 0);

            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
                return false;
            }

            GL11.glColorMask(false, false, false, true);
            GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
            return true;
        } catch (Throwable t) {
            LOGGER.error("Error executing ReprojectionShaderPass.sanitizeAlpha", t);
            return false;
        }
    }

    /**
     * Forces the alpha channel of the default window backbuffer (FBO 0) to 1.0 immediately prior to presentation.
     */
    public synchronized void sanitizeDefaultFramebufferAlpha() {
        try (GlStateSnapshot state = GlStateSnapshot.capture()) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);

            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
            GL11.glColorMask(false, false, false, true);
            GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        } catch (Throwable t) {
            LOGGER.error("Error executing ReprojectionShaderPass.sanitizeDefaultFramebufferAlpha", t);
        }
    }

    private void bindTexture(int textureUnit, GpuTexture texture, int uniformLocation, int unitIndex, int samplerId) {
        if (texture != null && uniformLocation != -1) {
            GL13.glActiveTexture(textureUnit);
            int glId = getGlTextureId(texture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
            if (samplerId != 0) {
                GL33.glBindSampler(unitIndex, samplerId);
            }
            GL20.glUniform1i(uniformLocation, unitIndex);
        }
    }

    private int getGlTextureId(GpuTexture texture) {
        if (texture instanceof GlTexture glTexture) {
            return glTexture.glId();
        }
        return 0;
    }

    private int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new RuntimeException("Shader compilation error (" + type + "): " + log);
        }
        return shader;
    }

    private String loadShaderResource(String path, String fallback) {
        try {
            InputStream in = getClass().getResourceAsStream(path);
            if (in == null) {
                in = ReprojectionShaderPass.class.getClassLoader().getResourceAsStream(path.startsWith("/") ? path.substring(1) : path);
            }
            if (in != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load shader resource from {}, using built-in fallback: {}", path, e.getMessage());
        }
        return fallback;
    }

    public synchronized void destroy() {
        if (programId != 0) {
            GL20.glDeleteProgram(programId);
            programId = 0;
        }
        if (hudProgramId != 0) {
            GL20.glDeleteProgram(hudProgramId);
            hudProgramId = 0;
        }
        if (vboId != 0) {
            GL15.glDeleteBuffers(vboId);
            vboId = 0;
        }
        if (vaoId != 0) {
            GL30.glDeleteVertexArrays(vaoId);
            vaoId = 0;
        }
        if (fboId != 0) {
            GL30.glDeleteFramebuffers(fboId);
            fboId = 0;
        }
        if (colorSamplerId != 0) {
            GL33.glDeleteSamplers(colorSamplerId);
            colorSamplerId = 0;
        }
        if (depthSamplerId != 0) {
            GL33.glDeleteSamplers(depthSamplerId);
            depthSamplerId = 0;
        }
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public static class GlStateSnapshot implements AutoCloseable {
        private final int drawFbo;
        private final int readFbo;
        private final int program;
        private final int vao;
        private final int vbo;
        private final int activeTexture;
        private final int[] textureBindings = new int[5];
        private final int[] samplerBindings = new int[5];
        private final int[] viewport = new int[4];
        private final float[] clearColor = new float[4];
        private final boolean colorMaskR;
        private final boolean colorMaskG;
        private final boolean colorMaskB;
        private final boolean colorMaskA;
        private final boolean scissor;
        private final boolean blend;
        private final boolean depthTest;
        private final boolean cullFace;
        private final boolean stencilTest;
        private final boolean srgb;

        public static GlStateSnapshot capture() {
            return new GlStateSnapshot();
        }

        private GlStateSnapshot() {
            this.drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            this.readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            this.program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            this.vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            this.vbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            this.activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, this.viewport);
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, this.clearColor);

            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                ByteBuffer maskBuf = stack.malloc(4);
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, maskBuf);
                this.colorMaskR = maskBuf.get(0) != 0;
                this.colorMaskG = maskBuf.get(1) != 0;
                this.colorMaskB = maskBuf.get(2) != 0;
                this.colorMaskA = maskBuf.get(3) != 0;
            }

            for (int i = 0; i < 5; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                this.textureBindings[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                try {
                    this.samplerBindings[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
                } catch (Throwable ignored) {
                }
            }
            GL13.glActiveTexture(this.activeTexture);

            this.scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            this.blend = GL11.glIsEnabled(GL11.GL_BLEND);
            this.depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            this.cullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            this.stencilTest = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
            boolean srgbVal = false;
            try {
                srgbVal = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
            } catch (Throwable ignored) {
            }
            this.srgb = srgbVal;
        }

        @Override
        public void close() {
            // Restore framebuffer bindings
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.drawFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.readFbo);

            // Restore shader program and buffer/VAO bindings
            GL20.glUseProgram(this.program);
            GL30.glBindVertexArray(this.vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);

            // Restore per-unit texture and sampler bindings
            for (int i = 0; i < 5; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.textureBindings[i]);
                try {
                    GL33.glBindSampler(i, this.samplerBindings[i]);
                } catch (Throwable ignored) {
                }
            }
            GL13.glActiveTexture(this.activeTexture);

            // Restore viewport, clear color & rasterizer state
            GL11.glViewport(this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3]);
            GL11.glClearColor(this.clearColor[0], this.clearColor[1], this.clearColor[2], this.clearColor[3]);
            GL11.glColorMask(
                    this.colorMaskR,
                    this.colorMaskG,
                    this.colorMaskB,
                    this.colorMaskA
            );

            if (this.scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST); else GL11.glDisable(GL11.GL_SCISSOR_TEST);
            if (this.blend) GL11.glEnable(GL11.GL_BLEND); else GL11.glDisable(GL11.GL_BLEND);
            if (this.depthTest) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
            if (this.cullFace) GL11.glEnable(GL11.GL_CULL_FACE); else GL11.glDisable(GL11.GL_CULL_FACE);
            if (this.stencilTest) GL11.glEnable(GL11.GL_STENCIL_TEST); else GL11.glDisable(GL11.GL_STENCIL_TEST);
            try {
                if (this.srgb) GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB); else GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            } catch (Throwable ignored) {
            }
        }
    }

    public static class GlStateProbe {
        public final int drawFbo;
        public final int readFbo;
        public final int program;
        public final int vao;
        public final int vbo;
        public final int activeTexture;
        public final int[] textureBindings = new int[5];
        public final int[] samplerBindings = new int[5];
        public final boolean scissor;
        public final boolean blend;
        public final boolean depthTest;
        public final boolean cullFace;

        public static GlStateProbe capture() {
            int drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int vbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            int activeTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            int[] texUnits = new int[5];
            int[] samplers = new int[5];

            for (int i = 0; i < 5; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                texUnits[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                try {
                    samplers[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
                } catch (Throwable ignored) {
                }
            }
            GL13.glActiveTexture(activeTex);

            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean cullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);

            return new GlStateProbe(drawFbo, readFbo, program, vao, vbo, activeTex, texUnits, samplers, scissor, blend, depthTest, cullFace);
        }

        public GlStateProbe(int drawFbo, int readFbo, int program, int vao, int vbo, int activeTexture, int[] texUnits, int[] samplers, boolean scissor, boolean blend, boolean depthTest, boolean cullFace) {
            this.drawFbo = drawFbo;
            this.readFbo = readFbo;
            this.program = program;
            this.vao = vao;
            this.vbo = vbo;
            this.activeTexture = activeTexture;
            System.arraycopy(texUnits, 0, this.textureBindings, 0, 5);
            System.arraycopy(samplers, 0, this.samplerBindings, 0, 5);
            this.scissor = scissor;
            this.blend = blend;
            this.depthTest = depthTest;
            this.cullFace = cullFace;
        }

        public void compareAndLog(String passName, GlStateProbe after) {
            if (after == null) return;
            StringBuilder sb = new StringBuilder();
            if (this.drawFbo != after.drawFbo) sb.append(String.format(" drawFBO: %d -> %d;", this.drawFbo, after.drawFbo));
            if (this.readFbo != after.readFbo) sb.append(String.format(" readFBO: %d -> %d;", this.readFbo, after.readFbo));
            if (this.program != after.program) sb.append(String.format(" program: %d -> %d;", this.program, after.program));
            if (this.vao != after.vao) sb.append(String.format(" vao: %d -> %d;", this.vao, after.vao));
            if (this.vbo != after.vbo) sb.append(String.format(" vbo: %d -> %d;", this.vbo, after.vbo));
            if (this.activeTexture != after.activeTexture) sb.append(String.format(" activeTex: 0x%X -> 0x%X;", this.activeTexture, after.activeTexture));
            for (int i = 0; i < 5; i++) {
                if (this.textureBindings[i] != after.textureBindings[i]) {
                    sb.append(String.format(" texUnit%d: %d -> %d;", i, this.textureBindings[i], after.textureBindings[i]));
                }
                if (this.samplerBindings[i] != after.samplerBindings[i]) {
                    sb.append(String.format(" sampler%d: %d -> %d;", i, this.samplerBindings[i], after.samplerBindings[i]));
                }
            }
            if (this.scissor != after.scissor) sb.append(String.format(" scissor: %b -> %b;", this.scissor, after.scissor));
            if (this.blend != after.blend) sb.append(String.format(" blend: %b -> %b;", this.blend, after.blend));
            if (this.depthTest != after.depthTest) sb.append(String.format(" depthTest: %b -> %b;", this.depthTest, after.depthTest));
            if (this.cullFace != after.cullFace) sb.append(String.format(" cullFace: %b -> %b;", this.cullFace, after.cullFace));

            if (sb.length() > 0) {
                LOGGER.warn("[GL State Drift Probe] State mutation detected in {}:{}", passName, sb.toString());
            }
        }
    }

    /**
     * Mathematical model of the HUD/Vignette composite fragment shader for headless verification.
     * Computes the blended RGB output and hudFactor given intermediate world, full main with HUD, and base world-only color.
     */
    public static float[] evaluateHudCompositeMath(float[] interRgb, float[] mainRgb, float[] worldRgb) {
        float lumWorld = 0.299f * worldRgb[0] + 0.587f * worldRgb[1] + 0.114f * worldRgb[2];
        float lumMain = 0.299f * mainRgb[0] + 0.587f * mainRgb[1] + 0.114f * mainRgb[2];

        float vRatio = lumMain / Math.max(lumWorld, 0.0001f);
        float vClamped = Math.clamp(vRatio, 0.0f, 1.0f);
        float lumWeight = smoothstep(0.001f, 0.01f, lumWorld);
        float V = (1.0f - lumWeight) * 1.0f + lumWeight * vClamped;

        float[] interVignetted = new float[] {
                interRgb[0] * V,
                interRgb[1] * V,
                interRgb[2] * V
        };

        float brighten = Math.max(0.0f, Math.max(
                mainRgb[0] - worldRgb[0],
                Math.max(mainRgb[1] - worldRgb[1], mainRgb[2] - worldRgb[2])
        ));
        float hudBright = smoothstep(0.02f, 0.05f, brighten);

        float[] expectedVignette = new float[] {
                worldRgb[0] * V,
                worldRgb[1] * V,
                worldRgb[2] * V
        };
        float maxDiffV = Math.max(
                Math.abs(mainRgb[0] - expectedVignette[0]),
                Math.max(Math.abs(mainRgb[1] - expectedVignette[1]), Math.abs(mainRgb[2] - expectedVignette[2]))
        );
        float hudDiff = smoothstep(0.03f, 0.07f, maxDiffV);

        float hudFactor = Math.clamp(Math.max(hudBright, hudDiff), 0.0f, 1.0f);

        return new float[] {
                (1.0f - hudFactor) * interVignetted[0] + hudFactor * mainRgb[0],
                (1.0f - hudFactor) * interVignetted[1] + hudFactor * mainRgb[1],
                (1.0f - hudFactor) * interVignetted[2] + hudFactor * mainRgb[2],
                hudFactor
        };
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
