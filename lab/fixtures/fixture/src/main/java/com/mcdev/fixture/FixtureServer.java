package com.mcdev.fixture;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端那一半：等客户端主动打招呼，等不到就说出来。
 *
 * <p>为什么用「玩家敲一条命令」当握手，而不是自定义 payload：命令注册是 fabric-api 里最稳的一层
 * （{@code CommandRegistrationCallback}），命令回执又会进服务端日志，于是「客户端有没有这一半」
 * 变成一条可以直接 grep 的日志 —— 不需要任何网络编解码，也不依赖某个版本的 payload API。</p>
 *
 * <p>三句日志就是全部可观测面（前缀 {@code [mcdev_fixture]}，与套件的能力无关）：</p>
 * <ul>
 *   <li>{@code server got hello from <player>} —— 客户端那一半在，且命令跑通了</li>
 *   <li>{@code client missing for <player> (no hello in 100 ticks)} —— 进服 5 s 内没等到</li>
 *   <li>{@code server side active} —— 这一半自己被加载了（{@code /mods} 之外的第二个证据）</li>
 * </ul>
 */
public final class FixtureServer implements DedicatedServerModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("mcdev_fixture");

    /** 已经打过招呼的玩家。 */
    private static final Set<UUID> HELLO = ConcurrentHashMap.newKeySet();
    /** 进服 tick：用来判「100 tick 内有没有等到」。 */
    private static final Map<UUID, Integer> JOINED_AT = new ConcurrentHashMap<>();
    /** 已经报过 missing 的玩家，避免每 tick 刷一行。 */
    private static final Set<UUID> REPORTED = ConcurrentHashMap.newKeySet();

    private static final int TIMEOUT_TICKS = 100;

    @Override
    public void onInitializeServer() {
        LOGGER.info("[mcdev_fixture] server side active");

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerHello(dispatcher));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            JOINED_AT.put(player.getUUID(), server.getTickCount());
            LOGGER.info("[mcdev_fixture] player joined: {}", player.getName().getString());
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int now = server.getTickCount();
            for (Map.Entry<UUID, Integer> entry : JOINED_AT.entrySet()) {
                UUID id = entry.getKey();
                if (HELLO.contains(id) || REPORTED.contains(id)) {
                    continue;
                }
                if (now - entry.getValue() < TIMEOUT_TICKS) {
                    continue;
                }
                REPORTED.add(id);
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                String name = player == null ? id.toString() : player.getName().getString();
                LOGGER.info("[mcdev_fixture] client missing for {} (no hello in {} ticks)", name, TIMEOUT_TICKS);
            }
        });
    }

    private static void registerHello(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mcdevfixture").executes(ctx -> {
            CommandSourceStack source = ctx.getSource();
            ServerPlayer player = source.getPlayer();
            String name = player == null ? "console" : player.getName().getString();
            if (player != null) {
                HELLO.add(player.getUUID());
            }
            LOGGER.info("[mcdev_fixture] server got hello from {}", name);
            // 广播给执行者：客户端那一半靠这句回执确认「服务端那一半在」
            source.sendSuccess(() -> Component.literal("mcdev_fixture ack from " + name), false);
            return 1;
        }));
    }
}
