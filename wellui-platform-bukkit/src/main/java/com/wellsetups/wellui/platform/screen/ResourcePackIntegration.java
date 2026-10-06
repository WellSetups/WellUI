package com.wellsetups.wellui.platform.screen;

import com.wellsetups.wellui.core.ScreenGenerationBuilder.Generation;
import java.io.IOException;
import java.util.Set;

/** External owner boundary. No provider may send a standalone WellUI pack. IO methods run off gameplay threads. */
public interface ResourcePackIntegration {
    String owner();
    default String pendingReason() { return "screen.merge_pending"; }
    void export(Generation generation) throws IOException;
    String verify(Generation generation) throws IOException;
    String verifiedHash(String generation);
    default IntegrationReport report() { return IntegrationReport.unknown(); }
    default String sourceRoot() { return ""; }
    /** IO-only cheap metadata identity; empty means this provider has no supported output monitor. */
    default String compiledStamp() throws IOException { return ""; }
    default String compiledPack() { return ""; }
    default void repair(Generation generation) throws IOException { throw new IOException("screen.external_owner"); }
    static Set<String> detect(java.util.function.Predicate<String> present) {
        var found = new java.util.TreeSet<String>();
        for (String name : java.util.List.of("ItemsAdder", "Oraxen", "Nexo", "CraftEngine")) if (present.test(name)) found.add(name);
        return Set.copyOf(found);
    }
}
