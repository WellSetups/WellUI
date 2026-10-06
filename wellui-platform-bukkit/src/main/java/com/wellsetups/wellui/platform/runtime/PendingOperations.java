package com.wellsetups.wellui.platform.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Bounds queued API requests and completes them when their scheduler is shutting down. */
final class PendingOperations implements AutoCloseable {
    private final Map<CompletableFuture<?>, Runnable> pending = new HashMap<>();
    private boolean closed;

    synchronized <T> CompletableFuture<T> create(T unavailable) {
        if (closed || pending.size() >= 1024) return CompletableFuture.completedFuture(unavailable);
        var future = new CompletableFuture<T>();
        pending.put(future, () -> future.complete(unavailable));
        future.whenComplete((result, failure) -> remove(future));
        return future;
    }

    private synchronized void remove(CompletableFuture<?> future) { pending.remove(future); }

    @Override public void close() {
        java.util.List<Runnable> completions;
        synchronized (this) {
            closed = true;
            completions = java.util.List.copyOf(pending.values());
            pending.clear();
        }
        completions.forEach(Runnable::run);
    }
}
