package com.wellsetups.wellui.core.screen;

/** Shared provider/client vocabulary. A client load alone never authorizes an unverified generation. */
public final class PackReadiness {
    private PackReadiness() { }
    public enum State {
        NOT_PREPARED, SOURCE_READY, PROVIDER_BUILD_REQUIRED, PROVIDER_BUILDING, VERIFYING, VERIFIED,
        PENDING_EXTERNAL_VERIFICATION, OFFERED, ACCEPTED, DOWNLOADED, LOADED, VERIFIED_LOADED, DECLINED, FAILED, STALE
    }
    public record Provider(State state, String generation, String sha1, String reason) {
        public boolean verified() { return state == State.VERIFIED && !sha1.isEmpty(); }
    }
}
