package com.mcdev.probe;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 探针的运行时配置：<b>系统属性优先于配置文件，配置文件优先于内置默认</b>。
 *
 * <p>为什么要读文件而不只认 {@code -D}：真实客户端/服务端往往由启动器拉起，用户不方便、
 * 也不应该为了探针去改启动器的 JVM 参数。把端口和服务端地址写进游戏目录下的
 * {@code config/mcdev-probe.properties}，启动器那边就一个字都不用改。
 * {@code -D} 保留为「这一次临时覆盖」的口子，两者键名完全相同。
 *
 * <p>键名（与 {@code -D} 一致）：
 * <ul>
 *   <li>{@code mcdev.probe.port} —— 服务端探针端口（默认 39001）</li>
 *   <li>{@code mcdev.probe.client.port} —— 客户端探针端口（默认 39002）</li>
 *   <li>{@code mcdev.probe.server.url} —— 客户端跨端调用服务端探针的基址</li>
 *   <li>{@code mcdev.probe.token} —— 探针口令（默认 {@code mcdev}）</li>
 * </ul>
 *
 * <p>文件按 路径+修改时间+大小 缓存，改完配置重启游戏即生效，不需要重新打包探针。
 * 读不出来时记一条 warn 并退回默认值 —— 不静默，也不因此拒绝启动。
 */
public final class ProbeConfig {
    /** 配置文件相对游戏目录的位置；两侧（客户端/服务端）同名同键。 */
    public static final String FILE_NAME = "mcdev-probe.properties";

    private static final String CONFIG_DIR = "config";

    private static final ConcurrentHashMap<Path, Cached> CACHE = new ConcurrentHashMap<>();

    private ProbeConfig() {
    }

    /** 配置文件路径；{@code gameDir} 为空时返回 {@code null}。 */
    public static Path file(Path gameDir) {
        return gameDir == null ? null : gameDir.resolve(CONFIG_DIR).resolve(FILE_NAME);
    }

    /** 按 系统属性 → 配置文件 → {@code fallback} 的顺序取值；空串视同未设置。 */
    public static String get(Path gameDir, String key, String fallback) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) {
            return sys.trim();
        }
        String fromFile = load(gameDir).getProperty(key);
        return fromFile == null || fromFile.isBlank() ? fallback : fromFile.trim();
    }

    /** 整数版的 {@link #get}；值不是整数时记 warn 并退回 {@code fallback}。 */
    public static int getInt(Path gameDir, String key, int fallback) {
        String raw = get(gameDir, key, null);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            ProbeLog.LOGGER.warn("探针配置 {} 的值 '{}' 不是整数，改用默认值 {}", key, raw, fallback);
            return fallback;
        }
    }

    /** 读取（并缓存）配置文件内容；不存在或读失败时返回空 Properties。 */
    public static Properties load(Path gameDir) {
        Path path = file(gameDir);
        if (path == null) {
            return new Properties();
        }
        try {
            if (!Files.isRegularFile(path)) {
                CACHE.remove(path);
                return new Properties();
            }
            long modified = Files.getLastModifiedTime(path).toMillis();
            long size = Files.size(path);
            Cached cached = CACHE.get(path);
            if (cached != null && cached.modified == modified && cached.size == size) {
                return cached.props;
            }
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            CACHE.put(path, new Cached(modified, size, props));
            ProbeLog.LOGGER.info("已读取探针配置 {}", path);
            return props;
        } catch (IOException e) {
            ProbeLog.LOGGER.warn("探针配置读取失败 {}", path, e);
            return new Properties();
        }
    }

    private record Cached(long modified, long size, Properties props) {
    }
}
