package com.mcdev.probe.action;

/**
 * {@code /action/*} 的固定错误码集合（架构 §7.1）。
 *
 * <p>为什么要有这个枚举：P3 之前的错误处理是各处理器自己写
 * {@code error(ex, 501, "…")}，调用方只能按文案分支 —— 文案随时会改，code 不会。
 * 这里把「code → HTTP 状态」的映射收在一处，新增 code 必须先改这个文件。</p>
 *
 * <p>HTTP 状态沿用架构 §7.1 的定稿值，不用随手拍的 400/500。</p>
 */
public enum ActionError {
    /** 参数缺失 / 类型错。 */
    BAD_REQUEST(400),
    /** 动作要求的游戏模式与玩家当前模式不符（三段式校验的第二段）。 */
    GAMEMODE_MISMATCH(400),
    /** 玩家不存在 / 客户端不在世界中。 */
    PLAYER_NOT_FOUND(404),
    /** 目标方块 / 实体 / 槽位不存在。 */
    TARGET_NOT_FOUND(404),
    /** 本实例没有这个能力（服务端实例上的 /action/* 就走这里）。 */
    TRAIT_UNAVAILABLE(501),
    /** 世界被冻结，动作已排队或拒绝。 */
    FROZEN(409),
    /** 等待 tick / 条件超时（例如挖了 3 秒方块还在）。 */
    TIMEOUT(504),
    /** 未分类异常。 */
    INTERNAL(500);

    private final int httpStatus;

    ActionError(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
