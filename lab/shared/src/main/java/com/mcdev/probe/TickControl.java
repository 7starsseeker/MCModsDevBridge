package com.mcdev.probe;

import net.minecraft.server.MinecraftServer;

/**
 * Tick 冻结与步进状态。
 *
 * <p>原探针（1.20.1）是自己在 {@code ServerLevel#tick} 上打 Mixin 来掐断 tick 的，还需要
 * {@code refmap} / {@code remap=false} / {@code compatibilityLevel=JAVA_17} 三处妥协。
 * 26.3 的原版 {@link net.minecraft.server.ServerTickRateManager} 已经原生提供
 * {@code setFrozen} / {@code stepGameIfPaused} / {@code frozenTicksToRun}，
 * 也就是 {@code /tick freeze} 与 {@code /tick step} 用的同一套机制 ——
 * 所以共享层<b>不需要任何 Mixin</b>，这也顺带把「mixin 注入 Java 25 字节码」这个问题
 * 从服务端侧彻底移除了。
 *
 * <p>只做转发，不自己维护状态：状态全在 {@code TickRateManager} 里，
 * 于是 {@code /tick} 命令与探针看到的是同一份真相，不会出现两边不一致。
 */
public final class TickControl {
    private static volatile MinecraftServer server;

    private TickControl() {
    }

    /** 服务端启动时由加载器入口调用。 */
    public static void attach(MinecraftServer serverIn) {
        server = serverIn;
    }

    /** 服务端停止时由加载器入口调用。 */
    public static void detach() {
        server = null;
    }

    public static boolean isFrozen() {
        MinecraftServer s = server;
        return s != null && s.tickRateManager().isFrozen();
    }

    /** 剩余步进预算（tick 数）。 */
    public static int getStepBudget() {
        MinecraftServer s = server;
        return s == null ? 0 : s.tickRateManager().frozenTicksToRun();
    }

    public static void setFrozen(boolean value) {
        MinecraftServer s = server;
        if (s != null) {
            s.tickRateManager().setFrozen(value);
        }
    }

    /** 冻结状态下前进 {@code ticks} 个 tick。 */
    public static void step(int ticks) {
        MinecraftServer s = server;
        if (s != null) {
            s.tickRateManager().stepGameIfPaused(Math.max(1, ticks));
        }
    }
}
