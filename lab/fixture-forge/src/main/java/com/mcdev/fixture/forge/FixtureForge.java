package com.mcdev.fixture.forge;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「两端都必须」的最小样本 —— Forge 版，与 {@code lab/fixture}（Fabric 版）语义对位。
 *
 * <p>它要证明的不是「mod 能跑」，而是「装没装上、生效没生效」能不能被断言：
 * 客户端进世界 20 tick 后自己发一条命令，服务端收到后回一句 ack；
 * 于是两端的日志里各留下一条只可能由「两端都在场」产生的话。
 *
 * <p>文本刻意沿用 Fabric 版：
 * {@code client sent hello} / {@code server got hello from} / {@code client got ack} /
 * {@code client missing for} / {@code server missing (no ack in}。
 *
 * <p>Forge 26.3 是 EventBus 7：没有 {@code MinecraftForge.EVENT_BUS}，每个事件类自带静态
 * {@code BUS}。客户端类（{@link FixtureForgeClient} 及其引用的 {@code net.minecraft.client.*}）
 * 只在 {@code dist == CLIENT} 时加载，专用服务器上不会被触碰。
 */
@Mod(FixtureForge.MODID)
public final class FixtureForge {
    /** modId，必须与 {@code META-INF/mods.toml} 的 {@code modId} 一致。 */
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

    public FixtureForge() {
        RegisterCommandsEvent.BUS.addListener(FixtureForge::onRegisterCommands);
        TickEvent.ServerTickEvent.Post.BUS.addListener(FixtureForge::onServerTick);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            FixtureForgeClient.install();
        }
        LOGGER.info("{} mod loaded (dist={})", LOG_PREFIX, FMLEnvironment.dist);
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

    private static void onServerTick(TickEvent.ServerTickEvent.Post event) {
        MinecraftServer server = event.server();
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
