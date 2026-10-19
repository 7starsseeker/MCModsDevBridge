package com.mcdev.fixture.neoforge;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「两端都必须」的最小样本 —— NeoForge 版，与 {@code lab/fixture}（Fabric 版）语义对位。
 *
 * <p>客户端进世界 20 tick 后自己发一条握手命令，服务端收到后回一句 ack；
 * 两端日志各留一条只有「两端都在场」才会出现的话，于是「装没装上、生效没生效」
 * 变成可以直接 grep 的一行。
 *
 * <p>文本刻意沿用 Fabric 版：{@code client sent hello} / {@code server got hello from} /
 * {@code client got ack} / {@code client missing for} / {@code server missing (no ack in}。
 *
 * <p>NeoForge 的游戏事件走 {@code NeoForge.EVENT_BUS}（不是 mod 事件总线）；
 * 客户端类只在 {@code dist == CLIENT} 时加载。
 */
@Mod(FixtureNeoForge.MODID)
public final class FixtureNeoForge {
    /** modId，必须与 {@code META-INF/neoforge.mods.toml} 的 {@code modId} 一致。 */
    public static final String MODID = "mcdev_fixture";
    /** 日志前缀，与 Fabric 版共用同一串，方便同一个断言跨加载器复用。 */
    public static final String LOG_PREFIX = "[mcdev_fixture]";
    /** 客户端自动发握手命令用的命令名。 */
    public static final String COMMAND = "mcdevfixture";
    /** 进世界后多久没收到握手就报「客户端那一半没来」。 */
    static final int TIMEOUT_TICKS = 100;

    static final Set<UUID> HELLO = ConcurrentHashMap.newKeySet();
    static final Map<UUID, Integer> SEEN_AT = new ConcurrentHashMap<>();
    static final Set<UUID> REPORTED = ConcurrentHashMap.newKeySet();
    static final AtomicBoolean SERVER_TICK_LOGGED = new AtomicBoolean();

    static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public FixtureNeoForge(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(FixtureNeoForge::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(FixtureNeoForge::onServerTick);
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            FixtureNeoForgeClient.install();
        }
        LOGGER.info("{} mod loaded (dist={})", LOG_PREFIX, FMLEnvironment.getDist());
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(COMMAND).executes(ctx -> {
            var source = ctx.getSource();
            ServerPlayer player = source.getPlayer();
            if (player != null) {
                HELLO.add(player.getUUID());
            }
            String name = source.getTextName();
            LOGGER.info("{} server got hello from {}", LOG_PREFIX, name);
            source.sendSuccess(() -> Component.literal(LOG_PREFIX + " ack from " + name), false);
            return 1;
        }));
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int tick = server.getTickCount();
        var players = server.getPlayerList().getPlayers();
        if (!players.isEmpty() && SERVER_TICK_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("{} server tick active", LOG_PREFIX);
        }
        for (ServerPlayer player : players) {
            UUID id = player.getUUID();
            // 第一次看见这名玩家 = 他进世界了；之后按 tick 差算超时。
            if (SEEN_AT.putIfAbsent(id, tick) == null) {
                LOGGER.info("{} joined {} (both-side fixture)", LOG_PREFIX, player.getName().getString());
            }
            Integer seen = SEEN_AT.get(id);
            if (seen != null && tick - seen >= TIMEOUT_TICKS
                    && !HELLO.contains(id) && REPORTED.add(id)) {
                LOGGER.info("{} client missing for {} (no hello in {} ticks)",
                        LOG_PREFIX, player.getName().getString(), TIMEOUT_TICKS);
            }
        }
    }
}
