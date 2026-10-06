package com.wellsetups.wellui.platform.screen;

import com.wellsetups.wellui.core.ScreenGenerationBuilder.Generation;
import java.io.IOException;

/** Safe detection boundary for providers without a supported adapter. Never sends or claims readiness. */
public final class UnmanagedPackIntegration implements ResourcePackIntegration {
    private final String name;
    public UnmanagedPackIntegration(String name) { this.name = name; }
    public String owner() { return "External " + name; }
    public String pendingReason() { return "screen.external_owner"; }
    public void export(Generation generation) { }
    public String verify(Generation generation) throws IOException { throw new IOException("screen.external_owner"); }
    public String verifiedHash(String generation) { return ""; }
}
