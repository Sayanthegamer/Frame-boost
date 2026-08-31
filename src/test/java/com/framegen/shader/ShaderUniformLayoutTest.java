package com.framegen.shader;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShaderUniformLayoutTest {

    @Test
    void testShaderUniformConstants() {
        assertThat(ShaderConstants.UNIFORM_CURRENT_COLOR).isEqualTo("CurrentColorSampler");
        assertThat(ShaderConstants.UNIFORM_PREVIOUS_COLOR).isEqualTo("PreviousColorSampler");
        assertThat(ShaderConstants.UNIFORM_CURRENT_DEPTH).isEqualTo("CurrentDepthSampler");
        assertThat(ShaderConstants.UNIFORM_PREVIOUS_DEPTH).isEqualTo("PreviousDepthSampler");
        assertThat(ShaderConstants.UNIFORM_REPROJECTION_MATRIX).isEqualTo("ReprojectionMatrix");
        assertThat(ShaderConstants.UNIFORM_SCREEN_SIZE).isEqualTo("ScreenSize");
        assertThat(ShaderConstants.UNIFORM_BLEND_FACTOR).isEqualTo("BlendFactor");
        assertThat(ShaderConstants.UNIFORM_DEPTH_DILATION).isEqualTo("DepthDilationRadius");
        assertThat(ShaderConstants.UNIFORM_ENTITY_MASK).isEqualTo("EntityMaskSampler");
    }

    @Test
    void testTextureUnits() {
        assertThat(ShaderConstants.TEXTURE_UNIT_CURRENT_COLOR).isEqualTo(0);
        assertThat(ShaderConstants.TEXTURE_UNIT_PREVIOUS_COLOR).isEqualTo(1);
        assertThat(ShaderConstants.TEXTURE_UNIT_CURRENT_DEPTH).isEqualTo(2);
        assertThat(ShaderConstants.TEXTURE_UNIT_PREVIOUS_DEPTH).isEqualTo(3);
        assertThat(ShaderConstants.TEXTURE_UNIT_ENTITY_MASK).isEqualTo(4);
    }
}
