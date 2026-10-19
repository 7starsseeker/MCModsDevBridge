package com.mcdev.probe.action;

import net.minecraft.world.level.GameType;

import java.util.Locale;

/**
 * gamemode 名字的**严格**解析。
 *
 * <p>为什么不能直接 {@code GameType.byName(raw)}：26.3 的单参重载是
 * {@code byName(raw, SURVIVAL)}（字节码实测），未知输入**静默返回 SURVIVAL** ——
 * 于是 {@code {"mode":"nonsense"}} 会把玩家切成生存模式并回 200。
 * 这正是本层存在的理由（把静默失败变响亮失败），所以这里改用显式枚举匹配。
 *
 * <p>实测证据见 {@code docs/p4a-action-layer-record.md} §12。
 */
public final class GameTypes {

    private GameTypes() {
    }

    /**
     * @param raw 请求里的 {@code mode} 原文
     * @return 四选一，永不返回 {@code null}
     * @throws ActionException {@code BAD_REQUEST} —— 名字不认识
     */
    public static GameType parse(String raw) throws ActionException {
        if (raw == null) {
            throw ActionException.badRequest("missing required field 'mode'");
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "survival" -> GameType.SURVIVAL;
            case "creative" -> GameType.CREATIVE;
            case "adventure" -> GameType.ADVENTURE;
            case "spectator" -> GameType.SPECTATOR;
            default -> throw ActionException.badRequest("unknown gamemode '" + raw
                    + "' (expected survival|creative|adventure|spectator)");
        };
    }
}
