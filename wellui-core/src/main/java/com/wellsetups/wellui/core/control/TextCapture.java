package com.wellsetups.wellui.core.control;

import java.util.UUID;

/** Bounded cross-thread native-modal mailbox. Tokens are one-use and belong to a single SCREEN input session. */
public final class TextCapture implements AutoCloseable {
    public record Request(UUID token, String component, String label, String value, int maxLength) { }
    public record Reply(String component, String value, boolean cancelled) { }
    private Request pending;
    private Reply reply;
    private boolean closed;
    public synchronized Request begin(String component, String label, String value, int maxLength) {
        if (closed || maxLength < 1 || maxLength > 512 || value.length() > maxLength || label.length() > 256) throw new IllegalArgumentException("control.input_request");
        pending = new Request(UUID.randomUUID(), component, label, value, maxLength);
        reply = null;
        return pending;
    }
    public synchronized boolean accept(String action, String value) {
        if (closed || pending == null) return false;
        String prefix = "wellui:input/" + pending.token() + "/";
        if (!action.startsWith(prefix)) return false;
        String operation = action.substring(prefix.length());
        if (!java.util.Set.of("save", "cancel", "clear").contains(operation)) return false;
        if (operation.equals("save") && (value == null || value.length() > pending.maxLength() || value.codePoints().anyMatch(Character::isISOControl))) return false;
        reply = new Reply(pending.component(), operation.equals("save") ? value : "", operation.equals("cancel"));
        pending = null;
        return true;
    }
    public synchronized Reply poll() { var result = reply;
        reply = null;
        return result;
    }
    public synchronized void cancel() { pending = null;
        reply = null;
    }
    public synchronized void close() { closed = true;
        cancel();
    }
}
