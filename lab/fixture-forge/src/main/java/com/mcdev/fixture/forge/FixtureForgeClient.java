package com.mcdev.fixture.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.TickEvent;

/**
 * 客户端那一半 —— 与 {@code lab/fixture} 的 {@code FixtureClient} 对位。
 *
 * <p>只在 {@code dist == CLIENT} 时由 {@link FixtureForge} 的构造器调用，
 * 因此专用服务器上这个类（以及它引用的 {@code net.minecraft.client.*}）永远不会被加载。
 */
final class FixtureForgeClient {
    /** 进世界后多少 tick 自动发握手命令（1 s）。 */
    private static final int SEND_AFTER_TICKS = 20;
    /** 发过命令后多少 tick 没等到 ack 就报「服务端那一半没来」。 */
    private static final int ACK_TIMEOUT_TICKS = 120;

    private static int ticks;
    private static boolean sent;
    private static boolean acked;
    private static boolean reported;

    private FixtureForgeClient() {
    }

    static void install() {
        FixtureForge.LOGGER.info("{} client side active", FixtureForge.LOG_PREFIX);
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> onClientTick());
        ClientChatReceivedEvent.BUS.addListener(event -> {
            if (!acked && event.getMessage().getString()
                    .contains(FixtureForge.LOG_PREFIX + " ack")) {
                acked = true;
                FixtureForge.LOGGER.info("{} client got ack", FixtureForge.LOG_PREFIX);
            }
        });
    }

    private static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null) {
            // 退出世界/还没连上：状态归零，下次进世界重新握手。
            if (ticks != 0 || sent || acked) {
                ticks = 0;
                sent = false;
                acked = false;
                reported = false;
            }
            return;
        }
        ticks++;
        if (!sent && ticks >= SEND_AFTER_TICKS) {
            player.connection.sendCommand(FixtureForge.COMMAND);
            sent = true;
            FixtureForge.LOGGER.info("{} client sent hello", FixtureForge.LOG_PREFIX);
        }
        if (sent && !acked && !reported && ticks > SEND_AFTER_TICKS + ACK_TIMEOUT_TICKS) {
            reported = true;
            FixtureForge.LOGGER.info("{} server missing (no ack in {} ticks)",
                    FixtureForge.LOG_PREFIX, ACK_TIMEOUT_TICKS);
        }
    }
}
