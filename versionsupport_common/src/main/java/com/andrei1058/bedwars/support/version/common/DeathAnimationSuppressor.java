package com.andrei1058.bedwars.support.version.common;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class DeathAnimationSuppressor implements Listener {

    private static final String HANDLER_NAME = "bedwars1058-death-animation";
    private static final String PACKET_HANDLER_NAME = "packet_handler";
    private static final int DEATH_STATUS = 3;
    private static DeathAnimationSuppressor instance;

    private final Plugin plugin;
    private final Set<Integer> playerEntityIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean packetShapeWarningLogged = new AtomicBoolean();
    private final AtomicBoolean channelWarningLogged = new AtomicBoolean();
    private boolean running;
    private int refreshTaskId = -1;

    private DeathAnimationSuppressor(Plugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public static void initialize(Plugin plugin) {
        if (instance == null) {
            instance = new DeathAnimationSuppressor(plugin);
        }
    }

    public static void start() {
        if (instance != null) {
            instance.startSuppressing();
        }
    }

    public static void shutdown() {
        if (instance != null) {
            instance.stopSuppressing();
            instance = null;
        }
    }

    private void startSuppressing() {
        if (running) {
            return;
        }
        running = true;
        refreshPlayerEntityIds();
        for (Player player : Bukkit.getOnlinePlayers()) {
            inject(player);
        }
        refreshTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshPlayerEntityIds, 20L, 20L).getTaskId();
    }

    private void stopSuppressing() {
        if (!running) {
            return;
        }
        running = false;
        if (refreshTaskId != -1) {
            Bukkit.getScheduler().cancelTask(refreshTaskId);
            refreshTaskId = -1;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeHandler(player);
        }
        playerEntityIds.clear();
    }

    private void refreshPlayerEntityIds() {
        Set<Integer> currentIds = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            currentIds.add(player.getEntityId());
        }
        playerEntityIds.retainAll(currentIds);
        playerEntityIds.addAll(currentIds);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        playerEntityIds.add(event.getPlayer().getEntityId());
        if (running) {
            inject(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        playerEntityIds.remove(event.getPlayer().getEntityId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        playerEntityIds.add(event.getEntity().getEntityId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> playerEntityIds.add(event.getPlayer().getEntityId()));
    }

    private void inject(Player player) {
        final Channel channel;
        try {
            channel = findChannel(player);
        } catch (ReflectiveOperationException | SecurityException exception) {
            logChannelWarning("Unable to access the network channel for player " + player.getName() + ".", exception);
            return;
        }

        channel.eventLoop().execute(() -> {
            if (!channel.isActive()) {
                return;
            }
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null) {
                return;
            }
            if (pipeline.get(PACKET_HANDLER_NAME) == null) {
                logChannelWarning("The packet_handler pipeline entry was not found for player " + player.getName() + ".", null);
                return;
            }
            pipeline.addBefore(PACKET_HANDLER_NAME, HANDLER_NAME, new DeathStatusHandler(this));
        });
    }

    private void removeHandler(Player player) {
        final Channel channel;
        try {
            channel = findChannel(player);
        } catch (ReflectiveOperationException | SecurityException exception) {
            logChannelWarning("Unable to remove the network handler for player " + player.getName() + ".", exception);
            return;
        }

        channel.eventLoop().execute(() -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null) {
                pipeline.remove(HANDLER_NAME);
            }
        });
    }

    private Channel findChannel(Player player) throws ReflectiveOperationException {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object playerConnection = getNamedFieldValue(handle, "playerConnection", "connection");
        if (playerConnection == null) {
            throw new NoSuchFieldException("Player connection");
        }

        Object networkConnection = getNamedFieldValue(playerConnection, "networkManager", "connection");
        if (networkConnection == null) {
            throw new NoSuchFieldException("Network connection");
        }

        for (Class<?> type = networkConnection.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Channel.class.isAssignableFrom(field.getType()) || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(networkConnection);
                if (value instanceof Channel) {
                    return (Channel) value;
                }
            }
        }
        throw new NoSuchFieldException("Netty channel");
    }

    private Object getNamedFieldValue(Object object, String... names) throws ReflectiveOperationException {
        for (String name : names) {
            for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(object);
                } catch (NoSuchFieldException ignored) {
                    // Continue through the supported field names and class hierarchy.
                }
            }
        }
        return null;
    }

    private boolean isPlayerDeathStatus(Object packet) {
        String className = packet.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        if (!className.contains("entity") || !(className.contains("status") || className.contains("event"))) {
            return false;
        }

        Integer entityId = null;
        Byte status = null;
        try {
            for (Class<?> type = packet.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (entityId != null && status != null) {
                        break;
                    }
                    if ((field.getType() == int.class && entityId == null) || (field.getType() == byte.class && status == null)) {
                        field.setAccessible(true);
                        if (field.getType() == int.class) {
                            entityId = field.getInt(packet);
                        } else {
                            status = field.getByte(packet);
                        }
                    }
                }
            }
        } catch (IllegalAccessException | SecurityException exception) {
            logPacketShapeWarning("Unable to inspect the entity status packet.", exception);
            return false;
        }

        if (entityId == null || status == null) {
            logPacketShapeWarning("The entity status packet has an unsupported field layout.", null);
            return false;
        }
        return status == DEATH_STATUS && playerEntityIds.contains(entityId);
    }

    private void logPacketShapeWarning(String message, Exception exception) {
        if (packetShapeWarningLogged.compareAndSet(false, true)) {
            if (exception == null) {
                plugin.getLogger().warning(message + " Death animation suppression may not work on this server version.");
            } else {
                plugin.getLogger().log(Level.WARNING, message + " Death animation suppression may not work on this server version.", exception);
            }
        }
    }

    private void logChannelWarning(String message, Exception exception) {
        if (channelWarningLogged.compareAndSet(false, true)) {
            if (exception == null) {
                plugin.getLogger().warning(message + " Death animation suppression could not be installed.");
            } else {
                plugin.getLogger().log(Level.WARNING, message + " Death animation suppression could not be installed.", exception);
            }
        }
    }

    private static final class DeathStatusHandler extends ChannelOutboundHandlerAdapter {

        private final DeathAnimationSuppressor suppressor;

        private DeathStatusHandler(DeathAnimationSuppressor suppressor) {
            this.suppressor = suppressor;
        }

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            if (suppressor.isPlayerDeathStatus(message)) {
                promise.setSuccess();
                return;
            }
            super.write(context, message, promise);
        }
    }
}
