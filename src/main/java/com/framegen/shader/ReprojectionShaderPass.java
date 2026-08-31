package com.framegen.shader;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
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
        uniform mat4 ReprojectionMatrix;
        uniform mat4 ViewModelReprojectionMatrix;
        uniform vec2 ScreenSize;
        uniform float BlendFactor;
        uniform float DepthDilationRadius;
        void main() {
            vec2 texelSize = 1.0 / max(ScreenSize, vec2(1.0, 1.0));
            float closestDepth = texture(CurrentDepthSampler, texCoord).r;

            // Fast-path: Sky / infinite background in Reverse-Z is depth 0.0 (requires no reprojection)
            if (closestDepth <= 0.0001) {
                fragColor = texture(CurrentColorSampler, texCoord);
                return;
            }

            vec4 maskSample = texture(EntityMaskSampler, texCoord);
            bool isViewmodel = (maskSample.g > 0.5) || (maskSample.r > 0.5 && closestDepth > 0.95);
            bool isDynamicMob = (maskSample.r > 0.5 && !isViewmodel);

            if (isDynamicMob) {
                fragColor = texture(CurrentColorSampler, texCoord);
                return;
            }

            if (!isViewmodel && DepthDilationRadius > 0.0) {
                for (int y = -1; y <= 1; ++y) {
                    for (int x = -1; x <= 1; ++x) {
                        vec2 offset = vec2(float(x), float(y)) * texelSize * DepthDilationRadius;
                        float d = texture(CurrentDepthSampler, texCoord + offset).r;
                        closestDepth = max(closestDepth, d);
                    }
                }
            }

            float zNDC = 1.0 - closestDepth * 2.0;
            vec4 currClip = vec4(texCoord * 2.0 - 1.0, zNDC, 1.0);
            mat4 activeReproj = isViewmodel ? ViewModelReprojectionMatrix : ReprojectionMatrix;
            vec4 prevClip = activeReproj * currClip;
            vec3 prevNDC = prevClip.xyz / max(prevClip.w, 0.00001);
            vec2 prevUV = prevNDC.xy * 0.5 + 0.5;

            vec4 currColor = texture(CurrentColorSampler, texCoord);
            vec4 minColor = currColor;
            vec4 maxColor = currColor;
            for (int y = -1; y <= 1; ++y) {
                for (int x = -1; x <= 1; ++x) {
                    vec2 offset = vec2(float(x), float(y)) * texelSize;
                    vec4 neighbor = texture(CurrentColorSampler, texCoord + offset);
                    minColor = min(minColor, neighbor);
                    maxColor = max(maxColor, neighbor);
                }
            }

            bool validUV = (prevUV.x >= 0.0 && prevUV.x <= 1.0 && prevUV.y >= 0.0 && prevUV.y <= 1.0 && prevClip.w > 0.0);
            vec4 prevColor = currColor;
            if (validUV) {
                float actualPrevDepth = texture(PreviousDepthSampler, prevUV).r;
                float expectedPrevDepth = (1.0 - prevNDC.z) * 0.5;
                float depthDiff = abs(actualPrevDepth - expectedPrevDepth);
                float depthThreshold = isViewmodel ? 0.08 : max(0.02 * max(expectedPrevDepth, 0.001), 0.005);

                if (depthDiff <= depthThreshold) {
                    vec4 rawPrev = texture(PreviousColorSampler, prevUV);
                    prevColor = clamp(rawPrev, minColor, maxColor);
                } else {
                    float radius = max(DepthDilationRadius, 1.0);
                    vec2 o0 = vec2(-1.5, 0.0) * texelSize * radius;
                    vec2 o1 = vec2( 1.5, 0.0) * texelSize * radius;
                    vec2 o2 = vec2(0.0, -1.5) * texelSize * radius;
                    vec2 o3 = vec2(0.0,  1.5) * texelSize * radius;

                    vec2 uv0 = clamp(prevUV + o0, 0.0, 1.0);
                    vec2 uv1 = clamp(prevUV + o1, 0.0, 1.0);
                    vec2 uv2 = clamp(prevUV + o2, 0.0, 1.0);
                    vec2 uv3 = clamp(prevUV + o3, 0.0, 1.0);

                    float d0 = texture(PreviousDepthSampler, uv0).r;
                    float d1 = texture(PreviousDepthSampler, uv1).r;
                    float d2 = texture(PreviousDepthSampler, uv2).r;
                    float d3 = texture(PreviousDepthSampler, uv3).r;

                    float invTwoSigmaSq = 1.0 / (2.0 * max(depthThreshold * depthThreshold * 2.25, 0.0001));
                    float diff0 = d0 - expectedPrevDepth;
                    float diff1 = d1 - expectedPrevDepth;
                    float diff2 = d2 - expectedPrevDepth;
                    float diff3 = d3 - expectedPrevDepth;

                    float w0 = exp(-(diff0 * diff0) * invTwoSigmaSq);
                    float w1 = exp(-(diff1 * diff1) * invTwoSigmaSq);
                    float w2 = exp(-(diff2 * diff2) * invTwoSigmaSq);
                    float w3 = exp(-(diff3 * diff3) * invTwoSigmaSq);

                    vec4 c0 = texture(PreviousColorSampler, uv0);
                    vec4 c1 = texture(PreviousColorSampler, uv1);
                    vec4 c2 = texture(PreviousColorSampler, uv2);
                    vec4 c3 = texture(PreviousColorSampler, uv3);

                    float sumWeights = w0 + w1 + w2 + w3;
                    vec4 infilledColor = (c0 * w0 + c1 * w1 + c2 * w2 + c3 * w3) / max(sumWeights, 0.0001);

                    vec4 neighborhoodAvg = (minColor + maxColor) * 0.5;
                    float confidence = clamp(sumWeights * 0.5, 0.0, 1.0);
                    vec4 blendedPrev = mix(neighborhoodAvg, infilledColor, confidence);
                    prevColor = clamp(blendedPrev, minColor, maxColor);
                }
            } else {
                prevColor = currColor;
            }
            fragColor = mix(currColor, prevColor, BlendFactor);
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
            vec3 diff = abs(mainColor.rgb - worldColor.rgb);
            float maxDiff = max(diff.r, max(diff.g, diff.b));
            float isHud = step(0.08, maxDiff);
            fragColor = mix(interColor, mainColor, isHud);
        }
        """;

    private int programId = 0;
    private int hudProgramId = 0;
    private int vaoId = 0;
    private int vboId = 0;
    private int fboId = 0;
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
            int hudFsh = compileShader(GL20.GL_FRAGMENT_SHADER, DEFAULT_HUD_FSH);
            hudProgramId = GL20.glCreateProgram();
            GL20.glAttachShader(hudProgramId, vsh);
            GL20.glAttachShader(hudProgramId, hudFsh);
            GL20.glLinkProgram(hudProgramId);

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

            // Query uniform locations - HUD Composite
            uHudIntermediate = GL20.glGetUniformLocation(hudProgramId, "IntermediateSampler");
            uHudMain = GL20.glGetUniformLocation(hudProgramId, "MainColorSampler");
            uHudWorldOnly = GL20.glGetUniformLocation(hudProgramId, "WorldOnlySampler");

            setupFullscreenQuad();
            fboId = GL30.glGenFramebuffers();
            initialized = true;
            LOGGER.info("ReprojectionShaderPass and HUD Composite Shader initialized successfully.");
        } catch (Throwable t) {
            LOGGER.error("Failed to initialize ReprojectionShaderPass", t);
            destroy();
        }
    }

    private void setupFullscreenQuad() {
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
    }

    public synchronized void render(
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
        render(currentColor, previousColor, currentDepth, previousDepth, null, outputColor, reprojectMatrix, reprojectMatrix, width, height, blendFactor, depthDilation);
    }

    public synchronized void render(
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
        render(currentColor, previousColor, currentDepth, previousDepth, entityMask, outputColor, reprojectMatrix, reprojectMatrix, width, height, blendFactor, depthDilation);
    }

    public synchronized void render(
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
        if (!initialized) {
            initialize();
            if (!initialized) return;
        }

        int targetTexId = getGlTextureId(outputColor != null ? outputColor : currentColor);
        if (targetTexId == 0) return;

        // Bind destination FBO
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, targetTexId, 0);
        GL11.glViewport(0, 0, width, height);

        GL20.glUseProgram(programId);

        // Bind Textures
        bindTexture(GL13.GL_TEXTURE0 + ShaderConstants.TEXTURE_UNIT_CURRENT_COLOR, currentColor, uCurrentColor, ShaderConstants.TEXTURE_UNIT_CURRENT_COLOR);
        bindTexture(GL13.GL_TEXTURE0 + ShaderConstants.TEXTURE_UNIT_PREVIOUS_COLOR, previousColor, uPreviousColor, ShaderConstants.TEXTURE_UNIT_PREVIOUS_COLOR);
        bindTexture(GL13.GL_TEXTURE0 + ShaderConstants.TEXTURE_UNIT_CURRENT_DEPTH, currentDepth, uCurrentDepth, ShaderConstants.TEXTURE_UNIT_CURRENT_DEPTH);
        bindTexture(GL13.GL_TEXTURE0 + ShaderConstants.TEXTURE_UNIT_PREVIOUS_DEPTH, previousDepth, uPreviousDepth, ShaderConstants.TEXTURE_UNIT_PREVIOUS_DEPTH);
        bindTexture(GL13.GL_TEXTURE0 + ShaderConstants.TEXTURE_UNIT_ENTITY_MASK, entityMask, uEntityMask, ShaderConstants.TEXTURE_UNIT_ENTITY_MASK);

        // Upload Uniforms
        if (uReprojectionMatrix != -1 && reprojectMatrix != null) {
            matrixBuffer.clear();
            reprojectMatrix.get(matrixBuffer);
            GL20.glUniformMatrix4fv(uReprojectionMatrix, false, matrixBuffer);
        }

        if (uViewModelReprojectionMatrix != -1) {
            Matrix4f vmMat = viewmodelReprojectMatrix != null ? viewmodelReprojectMatrix : reprojectMatrix;
            if (vmMat != null) {
                vmMatrixBuffer.clear();
                vmMat.get(vmMatrixBuffer);
                GL20.glUniformMatrix4fv(uViewModelReprojectionMatrix, false, vmMatrixBuffer);
            }
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

        // Draw Fullscreen Quad
        GL30.glBindVertexArray(vaoId);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(0);

        GL20.glUseProgram(0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    public synchronized void compositeHud(
            GpuTexture intermediateTexture,
            GpuTexture mainTextureWithHud,
            GpuTexture worldTextureOnly,
            GpuTexture outputTexture,
            int width,
            int height
    ) {
        if (!initialized) {
            initialize();
            if (!initialized) return;
        }

        int targetTexId = getGlTextureId(outputTexture != null ? outputTexture : intermediateTexture);
        if (targetTexId == 0) return;

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, targetTexId, 0);
        GL11.glViewport(0, 0, width, height);

        GL20.glUseProgram(hudProgramId);

        bindTexture(GL13.GL_TEXTURE0, intermediateTexture, uHudIntermediate, 0);
        bindTexture(GL13.GL_TEXTURE1, mainTextureWithHud, uHudMain, 1);
        bindTexture(GL13.GL_TEXTURE2, worldTextureOnly, uHudWorldOnly, 2);

        GL30.glBindVertexArray(vaoId);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(0);

        GL20.glUseProgram(0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private void bindTexture(int textureUnit, GpuTexture texture, int uniformLocation, int unitIndex) {
        if (texture != null && uniformLocation != -1) {
            GL13.glActiveTexture(textureUnit);
            int glId = getGlTextureId(texture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x812F); // GL_CLAMP_TO_EDGE
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x812F); // GL_CLAMP_TO_EDGE
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
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }
}

