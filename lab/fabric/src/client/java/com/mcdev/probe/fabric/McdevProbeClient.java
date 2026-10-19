package com.mcdev.probe.fabric;

import com.mcdev.probe.ClientProbeServer;
import com.mcdev.probe.ProbeConfig;
import com.mcdev.probe.ProbeLogs;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

import java.nio.file.Path;

/**
 * Fabric 客户端入口。
 *
 * <p>只能放在 {@code src/client/java}（loom 的 {@code splitEnvironmentSourceSets()} 保证这份源码
 * 不在服务端 run/编译的 classpath 上）。这是架构 B1「端边界」在 Fabric 上的强制手段：
 * 服务端入口想引用本类会直接编译失败，而不是等到运行时才 NoClassDefFoundError。</p>
 */
public final class McdevProbeClient implements ClientModInitializer {
    /** 客户端探针默认端口；与服务端 39001 分开，因为双端可能同时在一台机器上跑。 */
    public static final int DEFAULT_CLIENT_PORT = 39002;
    public static final String DEFAULT_TOKEN = "mcdev";

    @Override
    public void onInitializeClient() {
        ProbeLogs.install();
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            // 端口/口令：config/mcdev-probe.properties 优先于内置默认，-Dmcdev.probe.* 再覆盖它。
            Path dir = client.gameDirectory.toPath();
            ClientProbeServer.setLoader("fabric");
            ClientProbeServer.start(
                    ProbeConfig.getInt(dir, "mcdev.probe.client.port", DEFAULT_CLIENT_PORT),
                    ProbeConfig.get(dir, "mcdev.probe.token", DEFAULT_TOKEN));
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClientProbeServer.stop());
    }
}
