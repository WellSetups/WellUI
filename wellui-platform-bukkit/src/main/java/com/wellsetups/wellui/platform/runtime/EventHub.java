package com.wellsetups.wellui.platform.runtime;

import com.wellsetups.wellui.api.event.UiEvent;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Small typed event boundary with listener fault isolation. */
public final class EventHub implements AutoCloseable {
    private final CopyOnWriteArrayList<Subscription<?>> subscriptions = new CopyOnWriteArrayList<>();
    private final Consumer<RuntimeException> errors;
    private boolean closed;
    public EventHub(Consumer<RuntimeException> errors) { this.errors = errors; }

    public synchronized <E extends UiEvent> AutoCloseable subscribe(Class<E> type, Consumer<E> consumer) {
        if (closed || subscriptions.size() >= 1024) throw new IllegalStateException("event.subscription_limit");
        java.util.Objects.requireNonNull(type);
        java.util.Objects.requireNonNull(consumer);
        Subscription<E> subscription = new Subscription<>(type, consumer);
        subscriptions.add(subscription);
        return () -> subscriptions.remove(subscription);
    }

    public void fire(UiEvent event) {
        for (Subscription<?> subscription : subscriptions) {
            try { subscription.accept(event); }
            catch (RuntimeException error) { errors.accept(error); }
        }
    }
    public synchronized void close() {
        closed = true;
        subscriptions.clear();
    }

    private record Subscription<E extends UiEvent>(Class<E> type, Consumer<E> listener) {
        void accept(UiEvent event) { if (type.isInstance(event)) listener.accept(type.cast(event)); }
    }
}
