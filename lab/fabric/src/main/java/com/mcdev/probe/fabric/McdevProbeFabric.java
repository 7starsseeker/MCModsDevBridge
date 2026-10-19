package com.mcdev.probe.fabric;

import com.mcdev.probe.ProbeCommands;
import com.mcdev.probe.ProbeConfig;
import com.mcdev.probe.ProbeLog;
import com.mcdev.probe.ProbeLogs;
import com.mcdev.probe.ProbeServer;
import com.mcdev.probe.RunRecorder;
import com.mcdev.probe.TickControl;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;

/**
 * Fabric 侧的加载器入口 —— 平台适配面四样东西里的第一样（入口）。
 *
 * <p>只做三件事：装日志环形缓冲、把 {@code /mcdev} 命令树挂上去、在服务端起停时
 * attach/detach {@link TickControl} 并开关 {@link ProbeServer}。
 * 除这几行之外，探测逻辑一行都不在 Fabric 侧 —— 全在 {@code com.mcdev.probe} 共享层。
 *
 * <p>用 {@code DedicatedServerModInitializer}（{@code fabric.mod.json} 的 {@code server} 入口）
 * 而不是 {@code ModInitializer}：Fabric 的三个入口键（{@code main} / {@code client} / {@code server}）
 * 天然按端分流，这是 Fabric 独有的机制，Forge/NeoForge 没有对应物、只能运行时判 dist。
 */
public final class McdevProbeFabric implements DedicatedServerModInitializer {

    private static final int DEFAULT_PORT = 39001;
    private static final String DEFAULT_TOKEN = "mcdev";

    @Override
    public void onInitializeServer() {
        ProbeLogs.install();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ProbeCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ProbeLog.LOGGER.info("Mcdev Probe (Fabric) 入口已加载");
    }

    private void onServerStarted(MinecraftServer server) {
        TickControl.attach(server);
        RunRecorder.start(server);
        // 端口/口令：config/mcdev-probe.properties 优先于内置默认，-Dmcdev.probe.* 再覆盖它。
        Path dir = server.getServerDirectory();
        ProbeServer.setLoader("fabric");
        ProbeServer.start(
                server,
                ProbeConfig.getInt(dir, "mcdev.probe.port", DEFAULT_PORT),
                ProbeConfig.get(dir, "mcdev.probe.token", DEFAULT_TOKEN));
    }

    private void onServerStopping(MinecraftServer server) {
        ProbeServer.stop();
        RunRecorder.finish();
        TickControl.detach();
    }
}
