package com.mcdev.probe;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 已加载 mod 清单的最小共享视图。
 *
 * <p>三个加载器的查询 API 各不相同（Fabric {@code FabricLoader}、Forge/NeoForge
 * {@code ModList}），共享代码里不能直接引用任何一个，所以由各加载器入口在初始化时
 * 注册一个 provider，探针的 {@code /mods} 只读这里。
 *
 * <p>没有 provider 时返回空表而不是抛异常：探针本身能在「清单读不到」的加载器上活着，
 * 比为了清单崩掉整个探针有用；{@code /mods} 的 {@code source} 字段会说明这一点。
 */
public final class ModCatalog {

    /** 单个 mod 的可见信息（id/版本/显示名都是加载器元数据，不做归一化）。 */
    public record Entry(String id, String version, String name) {
    }

    private static volatile Supplier<List<Entry>> provider;

    private ModCatalog() {
    }

    /** 由各加载器入口注册；传 {@code null} 可清空（测试用）。 */
    public static void setProvider(Supplier<List<Entry>> supplier) {
        provider = supplier;
    }

    /** 当前 provider 是否已注册。 */
    public static boolean hasProvider() {
        return provider != null;
    }

    /** 已加载 mod 列表；provider 抛异常时记日志并返回空表，不让探针跟着挂。 */
    public static List<Entry> list() {
        Supplier<List<Entry>> p = provider;
        if (p == null) {
            return List.of();
        }
        try {
            List<Entry> out = p.get();
            return out == null ? List.of() : new ArrayList<>(out);
        } catch (RuntimeException e) {
            ProbeLog.LOGGER.warn("failed to read the loaded mod list", e);
            return List.of();
        }
    }
}
