package com.mcdev.probe.neoforge;

import com.mcdev.probe.ClientProbeServer;
import com.mcdev.probe.ProbeConfig;
import com.mcdev.probe.ProbeLog;
import com.mcdev.probe.ProbeLogs;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.lifecycle.ClientStartedEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;

/**
 * NeoForge 客户端探针入口 —— 与 Fabric 侧 {@code McdevProbeClient} 对位。
 *
 * <p>NeoForge 有与 Fabric 完全对位的两个客户端生命周期事件
 * （{@code ClientStartedEvent} / {@code ClientStoppingEvent}，都是游戏总线事件），
 * 所以这里不需要像 Forge 那样退回「第一个 tick」。
 *
 * <p>Iron law（架构 §I1）：加载器 API 只许出现在 platform 包内。
 */
public final class NeoForgeClientProbe {
    /** 客户端探针默认端口；与服务端 39001 分开，因为双端可能同时在一台机器上跑。 */
    public static final int DEFAULT_CLIENT_PORT = 39002;
    /** 与 Fabric 侧一致；可用 {@code -Dmcdev.probe.token=} 覆盖。 */
    public static final String DEFAULT_TOKEN = "mcdev";

    private NeoForgeClientProbe() {
    }

    /** 只在 {@code dist == Dist.CLIENT} 时由 {@link NeoForgeProbe} 调用。 */
    static void install() {
        ProbeLogs.install();
        NeoForge.EVENT_BUS.addListener((ClientStartedEvent e) -> {
            ClientProbeServer.setLoader("neoforge");
            // 端口/口令：config/mcdev-probe.properties 优先于内置默认，-Dmcdev.probe.* 再覆盖它。
            Path dir = Minecraft.getInstance().gameDirectory.toPath();
            ClientProbeServer.start(
                    ProbeConfig.getInt(dir, "mcdev.probe.client.port", DEFAULT_CLIENT_PORT),
                    ProbeConfig.get(dir, "mcdev.probe.token", DEFAULT_TOKEN));
        });
        NeoForge.EVENT_BUS.addListener((ClientStoppingEvent e) -> ClientProbeServer.stop());
        ProbeLog.LOGGER.info("Mcdev Probe (NeoForge) 客户端入口已加载");
    }
}
