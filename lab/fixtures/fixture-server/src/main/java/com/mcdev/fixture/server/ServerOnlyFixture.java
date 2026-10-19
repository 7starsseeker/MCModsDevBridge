package com.mcdev.fixture.server;

import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「只有服务端一半」的最小 fixture（{@code "environment": "server"}、只有 server 入口）。
 *
 * <p>它证明的是「任意一端即可」这一类 mod 的服务端形态：
 * <ol>
 *   <li>只装服务端、客户端完全没有它时，服务端这一半照样加载并跑起来；</li>
 *   <li>玩家（客户端没有它）进服时它照样收到 JOIN、照样在 tick —— 不需要对端有任何一半；</li>
 *   <li>客户端探针的 {@code /mods} 里根本没有它 ⇒ 「有没有」两侧各自可断言。</li>
 * </ol>
 *
 * <p>日志前缀固定为 {@code [mcdev_fixture_server]}，场景里对服务端 {@code /log/tail} 做子串断言即可。
 * 不提供任何玩法功能，也不该被当成示例 mod。
 */
public final class ServerOnlyFixture implements DedicatedServerModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcdev_fixture_server");

    /** 每个玩家只记一次「tick 也活着」，避免刷屏。 */
    private static final AtomicBoolean TICK_REPORTED = new AtomicBoolean(false);

    @Override
    public void onInitializeServer() {
        LOGGER.info("[mcdev_fixture_server] server side active");

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            LOGGER.info("[mcdev_fixture_server] joined (server-only half) {}", handler.getPlayer().getName().getString());
            TICK_REPORTED.set(false);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (TICK_REPORTED.get() || server.getPlayerList().getPlayerCount() == 0) {
                return;
            }
            if (server.getTickCount() % 20 == 0 && server.getTickCount() >= 20) {
                TICK_REPORTED.set(true);
                LOGGER.info("[mcdev_fixture_server] tick probe alive (server-only)");
            }
        });
    }
}
