package com.mcdev.probe.fabric;

import com.mcdev.probe.ClientProbeServer;
import com.mcdev.probe.ProbeLogs;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

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
            int port = Integer.getInteger("mcdev.probe.client.port", DEFAULT_CLIENT_PORT);
            String token = System.getProperty("mcdev.probe.token", DEFAULT_TOKEN);
            ClientProbeServer.setLoader("fabric");
            ClientProbeServer.start(port, token);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClientProbeServer.stop());
    }
}
