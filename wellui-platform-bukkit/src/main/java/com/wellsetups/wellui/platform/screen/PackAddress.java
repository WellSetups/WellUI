package com.wellsetups.wellui.platform.screen;

import java.net.InetSocketAddress;
import java.net.URI;
import org.bukkit.entity.Player;

/** Public Paper virtual-host adapter. Never resolves DNS or makes an outbound HTTP request. */
final class PackAddress {
    private PackAddress() { }
    static String origin(Player player, int port) {
        try {
            var api = Class.forName("com.destroystokyo.paper.network.NetworkClient");
            if (!api.isInstance(player)) return "";
            return origin((InetSocketAddress) api.getMethod("getVirtualHost").invoke(player), port);
        } catch (ReflectiveOperationException | LinkageError unavailable) { return ""; }
    }
    static String origin(InetSocketAddress address, int port) {
        if (address == null || port < 1 || port > 65535) return "";
        String host = address.getHostString();
        if (host.length() > 253 || !host.matches("[a-zA-Z0-9.:-]+")) return "";
        try {
            var uri = new URI("http", null, host, port, null, null, null);
            return uri.getHost() == null ? "" : uri.toASCIIString();
        } catch (java.net.URISyntaxException invalid) { return ""; }
    }
}
