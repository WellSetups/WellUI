package com.wellsetups.wellui.platform.screen;

/** Last IO inspection, published immutably so debug/status never performs filesystem work. */
public record IntegrationReport(String generation, String sourceGeneration, String compiledGeneration, int files,
                                String source, String output, String atlas, PackProblem problem, boolean legacy,
                                String models, String pixels, int rewrites) {
    public IntegrationReport(String generation, String sourceGeneration, String compiledGeneration, int files,
                             String source, String output, String atlas, PackProblem problem, boolean legacy) {
        this(generation, sourceGeneration, compiledGeneration, files, source, output, atlas, problem, legacy, "UNKNOWN", "UNKNOWN", 0);
    }
    public IntegrationReport compilation(String modelState, String pixelState, int accepted) {
        return new IntegrationReport(generation, sourceGeneration, compiledGeneration, files, source, output, atlas, problem, legacy, modelState, pixelState, accepted);
    }
    public static IntegrationReport unknown() { return new IntegrationReport("UNKNOWN", "UNKNOWN", "UNKNOWN", 0, "UNKNOWN", "UNKNOWN", "UNKNOWN", null, false); }
}
