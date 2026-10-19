package com.mcdev.probe.action;

import com.google.gson.JsonObject;

/**
 * 一个带结构化错误码的动作失败。
 *
 * <p>与 {@code IllegalArgumentException} 的区别：这个异常**知道自己是哪一类失败**，
 * 所以 HTTP 层能回 400/404/409/504 而不是一律 500。架构 §8.1 的「把静默失败变成
 * 响亮失败」就落在这里 —— 挖不动方块时返回 {@code GAMEMODE_MISMATCH}，而不是
 * {@code {"ok":true}} 让调用方以为成了。</p>
 */
public final class ActionException extends Exception {

    private static final long serialVersionUID = 1L;

    private final ActionError code;
    /** 附加上下文（目标坐标、当前模式、超时值……），序列化进 error.detail。 */
    private final transient JsonObject detail;

    public ActionException(ActionError code, String message) {
        this(code, message, null);
    }

    public ActionException(ActionError code, String message, JsonObject detail) {
        super(message);
        this.code = code;
        this.detail = detail;
    }

    public ActionError code() {
        return code;
    }

    public JsonObject detail() {
        return detail;
    }

    public static ActionException badRequest(String message) {
        return new ActionException(ActionError.BAD_REQUEST, message);
    }

    public static ActionException gamemodeMismatch(String message) {
        return new ActionException(ActionError.GAMEMODE_MISMATCH, message);
    }

    public static ActionException playerNotFound(String message) {
        return new ActionException(ActionError.PLAYER_NOT_FOUND, message);
    }

    public static ActionException targetNotFound(String message) {
        return new ActionException(ActionError.TARGET_NOT_FOUND, message);
    }

    public static ActionException notSupported(String message) {
        return new ActionException(ActionError.TRAIT_UNAVAILABLE, message);
    }

    public static ActionException timeout(String message, JsonObject detail) {
        return new ActionException(ActionError.TIMEOUT, message, detail);
    }

    public static ActionException internal(String message, Throwable cause) {
        JsonObject detail = new JsonObject();
        if (cause != null) {
            detail.addProperty("exception", cause.getClass().getName());
            detail.addProperty("stack", stackOf(cause, 8));
        }
        return new ActionException(ActionError.INTERNAL, message, detail);
    }

    private static String stackOf(Throwable t, int frames) {
        StackTraceElement[] trace = t.getStackTrace();
        StringBuilder sb = new StringBuilder();
        int n = Math.min(frames, trace.length);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(" <- ");
            }
            sb.append(trace[i].getClassName()).append('.').append(trace[i].getMethodName())
                    .append(':').append(trace[i].getLineNumber());
        }
        return sb.toString();
    }
}
