package com.mcdev.fixture.client;

import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「只有客户端一半」的最小 fixture。
 *
 * <p>它证明的是三件事，全部通过日志变成可断言的事实：
 * <ol>
 *   <li>客户端装上它，探针的 {@code /mods} 里就有它（服务端没有时，服务端 {@code /mods} 里就没有）；</li>
 *   <li>单人世界（客户端自己起内置服务端）里它照样被加载并跑起来 —— environment 是 {@code client}，
 *       内置服务端那一半根本不加载它，这正是「仅客户端 mod」的语义；</li>
 *   <li>连一个**没有**它的专用服时，客户端的它仍然活跃（{@code joined world (client-only half)}），
 *       所以「服务端必须也装」对这类 mod 不成立。</li>
 * </ol>
 *
 * <p>日志前缀固定为 {@code [mcdev_fixture_client]}，场景里直接对客户端 {@code /log/tail} 做子串断言即可。
 * 这个类不提供任何玩法功能，也不该被当成示例 mod。
 */
public final class ClientOnlyFixture implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcdev_fixture_client");

    /** 每次进世界只记一次「tick 也活着」，避免刷屏。 */
    private static final AtomicBoolean TICK_REPORTED = new AtomicBoolean(false);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[mcdev_fixture_client] client side active");

        // 进世界（单人世界的内置服务端、或专用服）都会走这里 —— 无论对端有没有这一半。
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            TICK_REPORTED.set(false);
            LOGGER.info("[mcdev_fixture_client] joined world (client-only half)");
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (TICK_REPORTED.get() || client.level == null || client.player == null) {
                return;
            }
            if (client.player.tickCount % 20 == 0 && client.player.tickCount >= 20) {
                TICK_REPORTED.set(true);
                LOGGER.info("[mcdev_fixture_client] tick probe alive (client-only)");
            }
        });
    }
}
