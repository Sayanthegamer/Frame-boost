package com.framegen.shader;

public final class ShaderConstants {
    public static final String UNIFORM_CURRENT_COLOR = "CurrentColorSampler";
    public static final String UNIFORM_PREVIOUS_COLOR = "PreviousColorSampler";
    public static final String UNIFORM_CURRENT_DEPTH = "CurrentDepthSampler";
    public static final String UNIFORM_PREVIOUS_DEPTH = "PreviousDepthSampler";
    public static final String UNIFORM_REPROJECTION_MATRIX = "ReprojectionMatrix";
    public static final String UNIFORM_VIEWMODEL_REPROJECTION_MATRIX = "ViewModelReprojectionMatrix";
    public static final String UNIFORM_SCREEN_SIZE = "ScreenSize";
    public static final String UNIFORM_BLEND_FACTOR = "BlendFactor";
    public static final String UNIFORM_DEPTH_DILATION = "DepthDilationRadius";
    public static final String UNIFORM_ENTITY_MASK = "EntityMaskSampler";
    public static final String UNIFORM_MID_TO_PREV_MATRIX = "MidToPrevMatrix";
    public static final String UNIFORM_MID_TO_CURR_MATRIX = "MidToCurrMatrix";
    public static final String UNIFORM_MID_TO_PREV_SKY_MATRIX = "MidToPrevSkyMatrix";
    public static final String UNIFORM_MID_TO_CURR_SKY_MATRIX = "MidToCurrSkyMatrix";
    public static final String UNIFORM_DEBUG_LADDER_MODE = "uDebugLadderMode";

    public static final String UNIFORM_HUD_INTERMEDIATE = "IntermediateSampler";
    public static final String UNIFORM_HUD_MAIN = "MainColorSampler";
    public static final String UNIFORM_HUD_WORLD_ONLY = "WorldOnlySampler";

    public static final int TEXTURE_UNIT_CURRENT_COLOR = 0;
    public static final int TEXTURE_UNIT_PREVIOUS_COLOR = 1;
    public static final int TEXTURE_UNIT_CURRENT_DEPTH = 2;
    public static final int TEXTURE_UNIT_PREVIOUS_DEPTH = 3;
    public static final int TEXTURE_UNIT_ENTITY_MASK = 4;

    private ShaderConstants() {
    }
}
