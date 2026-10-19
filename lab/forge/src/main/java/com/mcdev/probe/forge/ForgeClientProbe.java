package com.mcdev.probe.forge;

import com.mcdev.probe.ClientProbeServer;
import com.mcdev.probe.ProbeLog;
import com.mcdev.probe.ProbeLogs;

import net.minecraftforge.event.TickEvent;

/**
 * Forge 客户端探针入口 —— 与 Fabric 侧 {@code McdevProbeClient} 对位。
 *
 * <p><b>为什么用「第一个客户端 tick」而不是 client-started 事件</b>：Forge 26.3 没有与
 * Fabric {@code ClientLifecycleEvents.CLIENT_STARTED} / NeoForge {@code ClientStartedEvent}
 * 对位的游戏总线事件（{@code FMLClientSetupEvent} 在 mod 总线上，要 {@code BusGroup}；
 * 而 EventBus 7 已删除 {@code @Mod.EventBusSubscriber} / {@code @SubscribeEvent}，
 * 拿 BusGroup 得给 {@code @Mod} 构造器加 {@code ModContainer} 参数，为一个启动钩子不值）。
 * 客户端 tick 是游戏总线事件、按类自带 {@code BUS}，用它做一次性启动最省。
 *
 * <p>{@link ClientProbeServer#start} 本身幂等（已启动则直接返回），所以即使重复触发也无害；
 * 这里用 {@code started} 标志只是为了只挂一次监听逻辑。
 *
 * <p>Iron law（架构 §I1）：加载器 API 只许出现在 platform 包内。
 */
public final class ForgeClientProbe {
    /** 客户端探针默认端口；与服务端 39001 分开，因为双端可能同时在一台机器上跑。 */
    public static final int DEFAULT_CLIENT_PORT = 39002;
    /** 与 Fabric 侧一致；可用 {@code -Dmcdev.probe.token=} 覆盖。 */
    public static final String DEFAULT_TOKEN = "mcdev";

    private static boolean started;

    private ForgeClientProbe() {
    }

    /** 只在 {@code dist == Dist.CLIENT} 时由 {@link ForgeProbe} 调用。 */
    public static void install() {
        ProbeLogs.install();
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> {
            if (started) {
                return;
            }
            started = true;
            ClientProbeServer.setLoader("forge");
            ClientProbeServer.start(
                    Integer.getInteger("mcdev.probe.client.port", DEFAULT_CLIENT_PORT),
                    System.getProperty("mcdev.probe.token", DEFAULT_TOKEN));
        });
        ProbeLog.LOGGER.info("Mcdev Probe (Forge) 客户端入口已加载");
    }
}
