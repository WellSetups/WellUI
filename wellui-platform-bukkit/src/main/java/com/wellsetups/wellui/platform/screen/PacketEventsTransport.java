package com.wellsetups.wellui.platform.screen;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResourcePackSend;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemModel;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResourcePackRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.wellsetups.wellui.core.screen.ScreenInput;
import com.wellsetups.wellui.core.screen.Vec3;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.bukkit.entity.Player;

/** Optional adapter, loaded only after platform/dependency detection. No copied upstream implementation. */
public final class PacketEventsTransport implements ScreenTransport {
    private final Map<UUID, ScreenInput> inputs = new ConcurrentHashMap<>();
    private final LongAdder sends = new LongAdder();
    private volatile boolean closed;
    private final PackDelivery delivery;
    private final java.util.function.Function<String, String> clearLabel;
    private final PacketListenerAbstract listener = new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
        @Override public void onPacketSend(PacketSendEvent event) {
            if (closed || event.isCancelled()) return;
            if (event.getPacketType() == PacketType.Play.Server.RESOURCE_PACK_SEND) {
                var packet = new WrapperPlayServerResourcePackSend(event);
                delivery.offered(event.getUser().getUUID(), packet.getPackId(), packet.getHash());
            } else if (event.getPacketType() == PacketType.Play.Server.RESOURCE_PACK_REMOVE) {
                var packet = new WrapperPlayServerResourcePackRemove(event);
                delivery.removed(event.getUser().getUUID(), packet.getPackId());
            }
        }
        @Override public void onPacketReceive(PacketReceiveEvent event) {
            UUID owner = event.getUser().getUUID();
            ScreenInput input = owner == null ? null : inputs.get(owner);
            if (input == null || closed) return;
            var type = event.getPacketType();
            if (type == PacketType.Play.Client.CUSTOM_CLICK_ACTION) {
                var packet = new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCustomClickAction(event);
                if (packet.getId().toString().startsWith("wellui:input/")) {
                    event.setCancelled(true);
                    if (packet.getPayload() instanceof com.github.retrooper.packetevents.protocol.nbt.NBTCompound payload && payload.size() <= 1) {
                        input.text().accept(packet.getId().toString(), payload.getStringTagValueOrNull("value"));
                    } else if (packet.getPayload() == null) input.text().accept(packet.getId().toString(), null);
                }
                return;
            }
            if (WrapperPlayClientPlayerFlying.isFlying(type)) {
                var flying = new WrapperPlayClientPlayerFlying(event);
                if (flying.hasRotationChanged()) input.look(flying.getLocation().getYaw(), flying.getLocation().getPitch());
            } else if (type == PacketType.Play.Client.ANIMATION) {
                event.setCancelled(true);
                if (new WrapperPlayClientAnimation(event).getHand() == InteractionHand.MAIN_HAND) input.click(System.nanoTime());
            } else if (type == PacketType.Play.Client.INTERACT_ENTITY || type == PacketType.Play.Client.PLAYER_DIGGING
                    || type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT || type == PacketType.Play.Client.USE_ITEM) {
                event.setCancelled(true);
            }
        }
    };
    public PacketEventsTransport(PackDelivery delivery) {
        this(delivery, locale -> "Clear");
    }
    public PacketEventsTransport(PackDelivery delivery, java.util.function.Function<String, String> clearLabel) {
        this.delivery = delivery;
        this.clearLabel = clearLabel;
        if (!PacketEvents.getAPI().isInitialized()) throw new IllegalStateException("screen.dependency");
        PacketEvents.getAPI().getEventManager().registerListener(listener);
    }
    public Viewer viewer(Player player) {
        if (closed) throw new IllegalStateException("screen.dependency");
        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        if (user == null) throw new IllegalStateException("screen.dependency");
        return new Channel(user, player.getLocale());
    }
    public long sends() { return sends.sum(); }
    public void close() {
        closed = true;
        inputs.values().forEach(ScreenInput::close);
        inputs.clear();
        PacketEvents.getAPI().getEventManager().unregisterListener(listener);
    }
    private final class Channel implements Viewer {
        private final User user;
        private final String locale;
        Channel(User user, String locale) { this.user = user;
            this.locale = locale;
        }
        public String clientVersion() { return user.getClientVersion().getReleaseName(); }
        public int allocateEntityId() { return SpigotReflectionUtil.generateEntityId(); }
        public void listen(ScreenInput input) {
            if (closed) throw new IllegalStateException("screen.dependency");
            inputs.put(user.getUUID(), input);
        }
        public void unlisten(ScreenInput input) {
            input.close();
            inputs.remove(user.getUUID(), input);
        }
        public void spawn(int id, Vec3 center, double width, double height, float yaw, float pitch, String model) {
            send(new WrapperPlayServerSpawnEntity(id, Optional.of(UUID.randomUUID()), EntityTypes.ITEM_DISPLAY,
                    vector(center), 0, 0, 0, 0, Optional.empty()));
            var metadata = new java.util.ArrayList<>(ItemDisplayMetadata.transform(width, height, yaw, pitch));
            metadata.add(new EntityData<>(23, EntityDataTypes.ITEMSTACK, item(model)));
            send(new WrapperPlayServerEntityMetadata(id, metadata));
        }
        public void textSpawn(int id, Vec3 center, double scale, float yaw, float pitch, String value, String color, double opacity) {
            send(new WrapperPlayServerSpawnEntity(id, Optional.of(UUID.randomUUID()), EntityTypes.TEXT_DISPLAY,
                    vector(center), 0, 0, 0, 0, Optional.empty()));
            var metadata = new java.util.ArrayList<>(TextDisplayMetadata.transform(scale, yaw, pitch));
            metadata.addAll(TextDisplayMetadata.content(value, color, opacity));
            send(new WrapperPlayServerEntityMetadata(id, metadata));
        }
        public void text(int id, String value, String color, double opacity) {
            send(new WrapperPlayServerEntityMetadata(id, TextDisplayMetadata.content(value, color, opacity)));
        }
        public void model(int id, String model) {
            send(new WrapperPlayServerEntityMetadata(id, List.of(new EntityData<>(23, EntityDataTypes.ITEMSTACK, item(model)))));
        }
        public void input(com.wellsetups.wellui.core.control.TextCapture.Request request) {
            send(new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerShowDialog(NativeTextInput.dialog(request, clearLabel.apply(locale))));
        }
        public void clearInput() { send(new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerClearDialog()); }
        public void transform(int id, DisplayTransform pose, int ticks) {
            send(new WrapperPlayServerEntityMetadata(id, AnimationDisplayMetadata.transform(pose, ticks)));
        }
        public void move(int id, Vec3 center) {
            send(new WrapperPlayServerEntityTeleport(id, vector(center), 0, 0, false));
        }
        public void destroy(int... ids) { send(new WrapperPlayServerDestroyEntities(ids)); }
        public void removePack(UUID id) { send(new WrapperPlayServerResourcePackRemove(id)); }
        private ItemStack item(String model) {
            return ItemStack.builder().user(user).type(ItemTypes.PAPER)
                    .component(ComponentTypes.ITEM_MODEL, new ItemModel(new ResourceLocation(model))).build();
        }
        private void send(PacketWrapper<?> packet) {
            sends.increment();
            user.sendPacket(packet);
        }
    }
    private static Vector3d vector(Vec3 v) { return new Vector3d(v.x(), v.y(), v.z()); }
}
