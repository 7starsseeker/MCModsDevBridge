package com.mcdev.probe.forge;

import com.mcdev.probe.ModCatalog;
import com.mcdev.probe.ProbeCommands;
import com.mcdev.probe.ProbeConfig;
import com.mcdev.probe.ProbeLog;
import com.mcdev.probe.ProbeLogs;
import com.mcdev.probe.ProbeServer;
import com.mcdev.probe.RunRecorder;
import com.mcdev.probe.TickControl;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.nio.file.Path;

/**
 * Forge 平台适配层：探针自身的 {@code @Mod} 入口，外加平台四样东西里的入口与命令两项。
 *
 * <p>P4 步骤 0 之前本类刻意不带 {@code @Mod}，靠宿主 examplemod 在构造器里调 {@code install()}
 * 挂进来；那样「探针是独立 mod」只是文档里的说法。现在探针自己是 mod（modId
 * {@value #MODID}），examplemod 脚手架已删除。
 *
 * <p><b>Forge 26.3 的事件模型与 1.20.1 完全不同（EventBus 7）</b>：
 * 没有 {@code MinecraftForge.EVENT_BUS} 可用（那是迁移壳 {@code EventBusMigrationHelper}），
 * 每个事件类自己挂一个静态 {@code BUS} 字段，直接 {@code XxxEvent.BUS.addListener(...)}。
 * 游戏事件不需要 {@code getModBusGroup()}，那是 mod 生命周期的总线。
 *
 * <p>Iron law（架构 §I1）：加载器 API 只许出现在 platform 包内。
 */
@Mod(ForgeProbe.MODID)
public final class ForgeProbe {
    /** modId，必须与 {@code META-INF/mods.toml} 的 {@code modId} 一致。 */
    public static final String MODID = "mcdev_probe";
    /** 与 Fabric 侧一致；可用 {@code -Dmcdev.probe.port=} 覆盖。 */
    public static final int DEFAULT_PORT = 39001;
    /** 与 Fabric 侧一致；可用 {@code -Dmcdev.probe.token=} 覆盖。 */
    public static final String DEFAULT_TOKEN = "mcdev";

    public ForgeProbe() {
        ProbeLogs.install();
        RegisterCommandsEvent.BUS.addListener((RegisterCommandsEvent e) -> ProbeCommands.register(e.getDispatcher()));
        ServerStartedEvent.BUS.addListener((ServerStartedEvent e) -> onServerStarted(e.getServer()));
        ServerStoppingEvent.BUS.addListener((ServerStoppingEvent e) -> onServerStopping());
        // 客户端探针：专用服务器上 FMLEnvironment.dist 不是 CLIENT，本分支不执行，
        // 因此 ForgeClientProbe（及其引用的 net.minecraft.client.*）在服务端永不被加载。
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ForgeClientProbe.install();
        }
        ProbeLog.LOGGER.info("Mcdev Probe (Forge) 入口已加载");
    }

    private static void onServerStarted(MinecraftServer server) {
        // 原探针语义：服务端探针只在专用服务器起。客户端内嵌服务器由客户端探针负责。
        if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) {
            return;
        }
        TickControl.attach(server);
        RunRecorder.start(server);
        // 端口/口令：config/mcdev-probe.properties 优先于内置默认，-Dmcdev.probe.* 再覆盖它。
        Path dir = server.getServerDirectory();
        // 已加载 mod 清单：/mods 的权威来源（ModList 是加载器 API，只出现在 platform 包内）。
        ModCatalog.setProvider(() -> ModList.getMods().stream()
                .map(m -> new ModCatalog.Entry(
                        m.getModId(),
                        m.getVersion().toString(),
                        m.getDisplayName()))
                .toList());
        ProbeServer.setLoader("forge");
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
