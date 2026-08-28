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

    private int programId = 0;
    private int vaoId = 0;
    private int vboId = 0;
    private boolean initialized = false;

    // Uniform locations
    private int uCurrentColor = -1;
    private int uPreviousColor = -1;
    private int uCurrentDepth = -1;
    private int uPreviousDepth = -1;
    private int uReprojectionMatrix = -1;
    private int uScreenSize = -1;
    private int uBlendFactor = -1;
    private int uDepthDilation = -1;

    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    public ReprojectionShaderPass() {
    }

    public synchronized void initialize() {
        if (initialized) return;

        try {
            String vshSource = loadShaderResource("/assets/framegen/shaders/reproject.vsh");
            String fshSource = loadShaderResource("/assets/framegen/shaders/reproject.fsh");

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

            GL20.glDeleteShader(vsh);
            GL20.glDeleteShader(fsh);

            // Query uniform locations
            uCurrentColor = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_CURRENT_COLOR);
            uPreviousColor = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_PREVIOUS_COLOR);
            uCurrentDepth = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_CURRENT_DEPTH);
            uPreviousDepth = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_PREVIOUS_DEPTH);
            uReprojectionMatrix = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_REPROJECTION_MATRIX);
            uScreenSize = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_SCREEN_SIZE);
            uBlendFactor = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_BLEND_FACTOR);
            uDepthDilation = GL20.glGetUniformLocation(programId, ShaderConstants.UNIFORM_DEPTH_DILATION);

            setupFullscreenQuad();
            initialized = true;
            LOGGER.info("ReprojectionShaderPass initialized successfully.");
        } catch (Throwable t) {
            LOGGER.error("Failed to initialize ReprojectionShaderPass", t);
            destroy();
        }
    }

    private void setupFullscreenQuad() {
        // Vertex data: [posX, posY, u, v]
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

        // Position: location 0, size 2, stride 4 * 4, offset 0
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);

        // UV: location 1, size 2, stride 4 * 4, offset 2 * 4
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);

        GL30.glBindVertexArray(0);
    }

    public synchronized void render(
            GpuTexture currentColor,
            GpuTexture previousColor,
            GpuTexture currentDepth,
            GpuTexture previousDepth,
            Matrix4f reprojectMatrix,
            int width,
            int height,
            float blendFactor,
            float depthDilation
    ) {
        if (!initialized) {
            initialize();
            if (!initialized) return;
        }

        GL20.glUseProgram(programId);

        // Bind Textures
        bindTexture(GL13.GL_TEXTURE0 + ShaderConstants.TEXTURE_UNIT_CURRENT_COLOR, currentColor, uCurrentColor, ShaderConstants.TEXTURE_UNIT_CURRENT_COLOR);
        bindTexture(GL13.GL_TEXTURE0 + ShaderConstants.TEXTURE_UNIT_PREVIOUS_COLOR, previousColor, uPreviousColor, ShaderConstants.TEXTURE_UNIT_PREVIOUS_COLOR);
        bindTexture(GL13.GL_TEXTURE0 + ShaderConstants.TEXTURE_UNIT_CURRENT_DEPTH, currentDepth, uCurrentDepth, ShaderConstants.TEXTURE_UNIT_CURRENT_DEPTH);
        bindTexture(GL13.GL_TEXTURE0 + ShaderConstants.TEXTURE_UNIT_PREVIOUS_DEPTH, previousDepth, uPreviousDepth, ShaderConstants.TEXTURE_UNIT_PREVIOUS_DEPTH);

        // Upload Uniforms
        if (uReprojectionMatrix != -1 && reprojectMatrix != null) {
            matrixBuffer.clear();
            reprojectMatrix.get(matrixBuffer);
            GL20.glUniformMatrix4fv(uReprojectionMatrix, false, matrixBuffer);
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
    }

    private void bindTexture(int textureUnit, GpuTexture texture, int uniformLocation, int unitIndex) {
        if (texture != null && uniformLocation != -1) {
            GL13.glActiveTexture(textureUnit);
            int glId = getGlTextureId(texture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
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

    private String loadShaderResource(String path) {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("Shader resource not found: " + path);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read shader resource: " + path, e);
        }
    }

    public synchronized void destroy() {
        if (programId != 0) {
            GL20.glDeleteProgram(programId);
            programId = 0;
        }
        if (vboId != 0) {
            GL15.glDeleteBuffers(vboId);
            vboId = 0;
        }
        if (vaoId != 0) {
            GL30.glDeleteVertexArrays(vaoId);
            vaoId = 0;
        }
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
