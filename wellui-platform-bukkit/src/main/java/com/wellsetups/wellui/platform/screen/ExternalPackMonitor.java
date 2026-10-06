package com.wellsetups.wellui.platform.screen;

import com.wellsetups.wellui.platform.scheduling.PlatformScheduler;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Two-second metadata checks off gameplay threads. Hash/semantic verification only after a settled change. */
public final class ExternalPackMonitor implements AutoCloseable {
    private final ScreenAssets assets;
    private final PlatformScheduler scheduler;
    private final LongSupplier clock;
    private final AtomicBoolean queued = new AtomicBoolean();
    private volatile boolean closed;
    private long nextCheck;
    private String observed = "";
    private String checked = "";
    private String metadataFailure = "";
    public ExternalPackMonitor(ScreenAssets assets, PlatformScheduler scheduler) { this(assets, scheduler, System::nanoTime); }
    public ExternalPackMonitor(ScreenAssets assets, PlatformScheduler scheduler, LongSupplier clock) {
        this.assets = assets;
        this.scheduler = scheduler;
        this.clock = clock;
    }
    public void tick() {
        if (closed || !assets.external() || clock.getAsLong() - nextCheck < 0 || !queued.compareAndSet(false, true)) return;
        nextCheck = clock.getAsLong() + 2_000_000_000L;
        try {
            scheduler.async(() -> {
            try { check(); }
            finally { queued.set(false); }
            });
        } catch (RuntimeException failure) { queued.set(false); }
    }
    private void check() {
        if (closed || assets.generation() == null) return;
        try {
            String stamp = assets.integration().compiledStamp();
            if (stamp.isEmpty()) return;
            metadataFailure = "";
            String key = assets.generation().sha256() + ":" + assets.verificationRevision() + ":" + stamp;
            if (!key.equals(observed)) {
                observed = key;
                assets.providerChanged();
                return;
            }
            if (key.equals(checked) || closed) return;
            checked = key;
            try { assets.verifyExternal(); }
            catch (IOException pending) { /* Classified state is published; retry only when metadata/generation changes. */ }
        } catch (IOException unsafe) {
            String failure = assets.generation().sha256() + ":" + assets.verificationRevision() + ":" + unsafe.getMessage();
            if (failure.equals(metadataFailure)) return;
            metadataFailure = failure;
            observed = "";
            checked = "";
            // Verification publishes the classified path/source error; never follow an unsafe output path.
            if (!closed) try { assets.verifyExternal(); }
            catch (IOException pending) { /* Admin status owns the diagnostic. */ }
        }
    }
    public void close() { closed = true; }
}
