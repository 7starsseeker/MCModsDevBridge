package com.mcdev.probe.neoforge;

import com.mcdev.probe.ProbeCommands;
import com.mcdev.probe.ProbeConfig;
import com.mcdev.probe.ProbeLog;
import com.mcdev.probe.ProbeLogs;
import com.mcdev.probe.ProbeServer;
import com.mcdev.probe.RunRecorder;
import com.mcdev.probe.TickControl;

import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.nio.file.Path;

/**
 * NeoForge 平台适配层：探针自身的 {@code @Mod} 入口，外加平台四样东西里的入口与命令两项。
 *
 * <p>P4 步骤 0 之前本类刻意不带 {@code @Mod}，靠宿主 examplemod 在构造器里调 {@code install()}
 * 挂进来；那样「探针是独立 mod」只是文档里的说法。现在探针自己是 mod（modId
 * {@value #MODID}），examplemod 脚手架已删除。
 *
 * <p>Iron law（架构 §I1）：加载器 API 只许出现在 platform 包内。本类是
 * {@code net.neoforged.*} 的唯一落点。
 */
@Mod(NeoForgeProbe.MODID)
public final class NeoForgeProbe {
    /** modId，必须与 {@code META-INF/neoforge.mods.toml} 的 {@code modId} 一致。 */
    public static final String MODID = "mcdev_probe";
    /** 与 Fabric 侧一致；可用 {@code -Dmcdev.probe.port=} 覆盖。 */
    public static final int DEFAULT_PORT = 39001;
    /** 与 Fabric 侧一致；可用 {@code -Dmcdev.probe.token=} 覆盖。 */
    public static final String DEFAULT_TOKEN = "mcdev";

    public NeoForgeProbe(IEventBus modEventBus) {
        ProbeLogs.install();
        // 游戏事件总线（NeoForge.EVENT_BUS），不是 mod 事件总线 —— 这三个都是游戏事件。
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> ProbeCommands.register(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> onServerStarted(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> onServerStopping());
        // GameTest 注册属 mod 事件总线（RegisterGameTestsEvent）
        modEventBus.addListener(NeoForgeGameTests::register);
        // 客户端探针：专用服务器上 dist 不是 CLIENT，本分支不执行，
        // 因此 NeoForgeClientProbe（及其引用的 net.minecraft.client.*）在服务端永不被加载。
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            NeoForgeClientProbe.install();
        }
        ProbeLog.LOGGER.info("Mcdev Probe (NeoForge) 入口已加载");
    }

    private static void onServerStarted(MinecraftServer server) {
        // 原探针语义：服务端探针只在专用服务器起。客户端内嵌服务器由客户端探针负责。
        if (FMLEnvironment.getDist() != Dist.DEDICATED_SERVER) {
            return;
        }
        TickControl.attach(server);
        RunRecorder.start(server);
        // 端口/口令：config/mcdev-probe.properties 优先于内置默认，-Dmcdev.probe.* 再覆盖它。
        Path dir = server.getServerDirectory();
        ProbeServer.setLoader("neoforge");
        ProbeServer.start(
                server,
                ProbeConfig.getInt(dir, "mcdev.probe.port", DEFAULT_PORT),
                ProbeConfig.get(dir, "mcdev.probe.token", DEFAULT_TOKEN));
    }

    private static void onServerStopping() {
        ProbeServer.stop();
        RunRecorder.finish();
        TickControl.detach();
    }
}
