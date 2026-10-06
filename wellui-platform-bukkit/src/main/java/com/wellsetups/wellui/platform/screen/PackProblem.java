package com.wellsetups.wellui.platform.screen;

import java.io.IOException;
import java.util.Locale;

/** Classified verification failures; only sanitized resource-relative context crosses into messages. */
public final class PackProblem extends IOException {
    public enum Code {
        WELLUI_SOURCE_MODIFIED, WELLUI_SOURCE_MISSING, SOURCE_MANIFEST_INVALID, SOURCE_GENERATION_STALE,
        WELLUI_RESOURCE_SHADOWED, GENERATED_OUTPUT_NOT_BUILT, ITEMSADDER_BUILD_REQUIRED,
        ITEMSADDER_OUTPUT_UNREADABLE, OUTPUT_PROFILE_UNSUPPORTED, ATLAS_UNSUPPORTED, ATLAS_SPRITE_MISSING,
        EXTERNAL_NAMESPACE_COLLISION, UNSAFE_PATH, PROVIDER_BUILD_REQUIRED, PROVIDER_OUTPUT_UNREADABLE
    }
    private final Code code;
    private final String resource;
    private final String detail;
    public PackProblem(Code code, String resource) {
        this(code, resource, "");
    }
    public PackProblem(Code code, String resource, String detail) {
        super("screen.verify." + code.name().toLowerCase(Locale.ROOT));
        this.code = code;
        String clean = resource == null ? "" : resource.replaceAll("[^a-zA-Z0-9_./:# -]", "?");
        this.resource = clean.substring(0, Math.min(240, clean.length()));
        String safeDetail = detail.replaceAll("[^a-zA-Z0-9_./:# =,;()\\[\\]-]", "?");
        this.detail = safeDetail.substring(0, Math.min(1000, safeDetail.length()));
    }
    public Code code() { return code; }
    public String resource() { return resource; }
    public String detail() { return detail; }
    public boolean buildRequired() {
        return code == Code.ITEMSADDER_BUILD_REQUIRED || code == Code.PROVIDER_BUILD_REQUIRED || code == Code.GENERATED_OUTPUT_NOT_BUILT || code == Code.SOURCE_GENERATION_STALE;
    }
    public boolean conflict() {
        return switch (code) {
            case WELLUI_SOURCE_MODIFIED, WELLUI_SOURCE_MISSING, SOURCE_MANIFEST_INVALID, WELLUI_RESOURCE_SHADOWED,
                    EXTERNAL_NAMESPACE_COLLISION, UNSAFE_PATH -> true;
            default -> false;
        };
    }
}
