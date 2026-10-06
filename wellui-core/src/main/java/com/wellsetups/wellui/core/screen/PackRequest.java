package com.wellsetups.wellui.core.screen;

import java.util.UUID;

/** Correlated per-player request. Loaded is presentation readiness, never action authorization. */
public final class PackRequest {
    public enum State { SENT, ACCEPTED, DOWNLOADED, LOADED, DECLINED, FAILED, TIMED_OUT }
    private final UUID owner;
    private final UUID id;
    private final String hash;
    private final long deadline;
    private final boolean requireAcceptance;
    private State state = State.SENT;
    public PackRequest(UUID owner, UUID id, String hash, long now) {
        this(owner, id, hash, now, false);
    }
    public PackRequest(UUID owner, UUID id, String hash, long now, boolean requireAcceptance) {
        this.requireAcceptance = requireAcceptance;
        this.owner = owner;
        this.id = id;
        this.hash = hash;
        deadline = now + 90_000_000_000L;
    }
    public UUID id() { return id; }
    public String hash() { return hash; }
    public synchronized State state() { return state; }
    public synchronized boolean update(UUID player, UUID request, String result) {
        if (!owner.equals(player) || !id.equals(request) || state == State.DECLINED || state == State.FAILED || state == State.TIMED_OUT) return false;
        // Replacement uses the same stable UUID. Old terminal replies before the new ACCEPTED cannot authorize it.
        if (requireAcceptance && state == State.SENT && (result.equals("SUCCESSFULLY_LOADED") || result.equals("DISCARDED"))) return false;
        State next = switch (result) {
            case "ACCEPTED" -> State.ACCEPTED;
            case "DOWNLOADED" -> State.DOWNLOADED;
            case "SUCCESSFULLY_LOADED" -> State.LOADED;
            case "DECLINED" -> State.DECLINED;
            case "FAILED_DOWNLOAD", "INVALID_URL", "FAILED_RELOAD", "DISCARDED" -> State.FAILED;
            default -> null;
        };
        if (next == null || next.ordinal() < state.ordinal()) return false;
        state = next;
        return true;
    }
    public synchronized boolean timeout(long now) {
        if (state.ordinal() < State.LOADED.ordinal() && now - deadline >= 0) {
            state = State.TIMED_OUT;
            return true;
        }
        return false;
    }
}
