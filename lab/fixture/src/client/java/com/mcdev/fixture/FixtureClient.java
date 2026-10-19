package com.mcdev.fixture;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 客户端那一半：进世界后敲一次 {@code /mcdevfixture}，等服务端的回执。
 *
 * <p>三句日志（前缀 {@code [mcdev_fixture]}）：</p>
 * <ul>
 *   <li>{@code client sent hello} —— 这一半被加载了，而且真的发出了握手</li>
 *   <li>{@code client got ack} —— 服务端那一半也在</li>
 *   <li>{@code server missing (no ack in N ticks)} —— 服务端没有这一半（命令不存在时
 *       MC 只会给玩家一句「未知命令」，那句话不一定是稳定的英文，所以判定落在这一行上）</li>
 * </ul>
 */
public final class FixtureClient implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("mcdev_fixture");

    private static final int SEND_AFTER_TICKS = 20;
    private static final int TIMEOUT_TICKS = 120;

    private static int ticks;
    private static boolean sent;
    private static boolean acked;
    private static boolean reportedMissing;
    private static boolean joined;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[mcdev_fixture] client side active");

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            joined = true;
            ticks = 0;
            sent = false;
            acked = false;
            reportedMissing = false;
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) {
                return; // actionbar 不算回执
            }
            if (message.getString().contains("mcdev_fixture ack")) {
                acked = true;
                LOGGER.info("[mcdev_fixture] client got ack");
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!joined || client.player == null) {
                return;
            }
            ticks++;
            if (!sent && ticks >= SEND_AFTER_TICKS) {
                sent = true;
                client.player.connection.sendCommand("mcdevfixture");
                LOGGER.info("[mcdev_fixture] client sent hello");
            }
            if (sent && !acked && !reportedMissing && ticks >= TIMEOUT_TICKS) {
                reportedMissing = true;
                LOGGER.info("[mcdev_fixture] server missing (no ack in {} ticks)", TIMEOUT_TICKS);
            }
        });
    }
}
