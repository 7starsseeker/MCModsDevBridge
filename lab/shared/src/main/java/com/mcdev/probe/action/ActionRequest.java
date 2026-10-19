package com.mcdev.probe.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * {@code POST /action/*} 请求体的只读视图。
 *
 * <p>为什么不直接用 {@link JsonObject} 到处 {@code get(...).getAsXxx()}：那样每个字段的
 * 缺失/类型错都会抛 {@link UnsupportedOperationException} 或 {@link IllegalStateException}，
 * 到 HTTP 层只剩一句 500。这里把「字段缺失/类型错」统一压成 {@link ActionError#BAD_REQUEST}，
 * 错误文案带字段名，调用方（DSH 插件 / curl / 测试脚本）能直接定位。</p>
 *
 * <p>数组与对象两种形态都接受：{@code "pos": {"x":1,"y":64,"z":1}} 和 {@code "pos": [1,64,1]}
 * 等价 —— 写测试脚本时 curl 里敲数组短得多。</p>
 */
public final class ActionRequest {

    private final JsonObject body;

    public ActionRequest(JsonObject body) {
        this.body = body == null ? new JsonObject() : body;
    }

    public JsonObject raw() {
        return body;
    }

    public boolean has(String key) {
        return body.has(key) && !body.get(key).isJsonNull();
    }

    public String str(String key) throws ActionException {
        if (!has(key)) {
            throw ActionException.badRequest("missing required field '" + key + "'");
        }
        return asString(key);
    }

    public String str(String key, String def) throws ActionException {
        return has(key) ? asString(key) : def;
    }

    private String asString(String key) throws ActionException {
        JsonElement el = body.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            throw ActionException.badRequest("field '" + key + "' must be a string");
        }
        return el.getAsString();
    }

    public int i32(String key) throws ActionException {
        if (!has(key)) {
            throw ActionException.badRequest("missing required field '" + key + "'");
        }
        return asInt(key);
    }

    public int i32(String key, int def) throws ActionException {
        return has(key) ? asInt(key) : def;
    }

    /** 可空的整型，用于「不传就是选中槽位」这类参数。 */
    public Integer optI32(String key) throws ActionException {
        return has(key) ? asInt(key) : null;
    }

    private int asInt(String key) throws ActionException {
        JsonElement el = body.get(key);
        requireNumber(key, el);
        double d = el.getAsDouble();
        if (d != Math.rint(d)) {
            throw ActionException.badRequest("field '" + key + "' must be an integer");
        }
        return (int) d;
    }

    public double f64(String key, double def) throws ActionException {
        if (!has(key)) {
            return def;
        }
        JsonElement el = body.get(key);
        requireNumber(key, el);
        return el.getAsDouble();
    }

    public boolean bool(String key, boolean def) throws ActionException {
        if (!has(key)) {
            return def;
        }
        JsonElement el = body.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean()) {
            throw ActionException.badRequest("field '" + key + "' must be a boolean");
        }
        return el.getAsBoolean();
    }

    private static void requireNumber(String key, JsonElement el) throws ActionException {
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw ActionException.badRequest("field '" + key + "' must be a number");
        }
    }

    /**
     * 方块坐标：{@code {"x":1,"y":64,"z":1}} 或 {@code [1,64,1]}。
     *
     * @return 长度 3 的数组 {@code [x, y, z]}
     */
    public int[] blockPos(String key) throws ActionException {
        JsonElement el = requireObjectOrArray(key);
        int[] out = new int[3];
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            if (arr.size() != 3) {
                throw ActionException.badRequest("field '" + key + "' array must have 3 numbers [x,y,z]");
            }
            for (int i = 0; i < 3; i++) {
                requireNumber(key + "[" + i + "]", arr.get(i));
                out[i] = arr.get(i).getAsInt();
            }
            return out;
        }
        JsonObject obj = el.getAsJsonObject();
        String[] keys = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            if (!obj.has(keys[i]) || obj.get(keys[i]).isJsonNull()) {
                throw ActionException.badRequest("field '" + key + "' missing component '" + keys[i] + "'");
            }
            requireNumber(key + "." + keys[i], obj.get(keys[i]));
            out[i] = obj.get(keys[i]).getAsInt();
        }
        return out;
    }

    /**
     * 精确坐标：{@code {"x":…,"y":…,"z":…}} 或 {@code [x,y,z]}（double）。
     */
    public double[] vec3(String key) throws ActionException {
        JsonElement el = requireObjectOrArray(key);
        double[] out = new double[3];
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            if (arr.size() != 3) {
                throw ActionException.badRequest("field '" + key + "' array must have 3 numbers [x,y,z]");
            }
            for (int i = 0; i < 3; i++) {
                requireNumber(key + "[" + i + "]", arr.get(i));
                out[i] = arr.get(i).getAsDouble();
            }
            return out;
        }
        JsonObject obj = el.getAsJsonObject();
        String[] keys = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            if (!obj.has(keys[i]) || obj.get(keys[i]).isJsonNull()) {
                throw ActionException.badRequest("field '" + key + "' missing component '" + keys[i] + "'");
            }
            requireNumber(key + "." + keys[i], obj.get(keys[i]));
            out[i] = obj.get(keys[i]).getAsDouble();
        }
        return out;
    }

    /** 视角：{@code {"yaw":…,"pitch":…}} 或 {@code [yaw,pitch]}。 */
    public float[] yawPitch(String key) throws ActionException {
        JsonElement el = requireObjectOrArray(key);
        float[] out = new float[2];
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            if (arr.size() != 2) {
                throw ActionException.badRequest("field '" + key + "' array must have 2 numbers [yaw,pitch]");
            }
            for (int i = 0; i < 2; i++) {
                requireNumber(key + "[" + i + "]", arr.get(i));
                out[i] = arr.get(i).getAsFloat();
            }
            return out;
        }
        JsonObject obj = el.getAsJsonObject();
        String[] keys = {"yaw", "pitch"};
        for (int i = 0; i < 2; i++) {
            if (!obj.has(keys[i]) || obj.get(keys[i]).isJsonNull()) {
                throw ActionException.badRequest("field '" + key + "' missing component '" + keys[i] + "'");
            }
            requireNumber(key + "." + keys[i], obj.get(keys[i]));
            out[i] = obj.get(keys[i]).getAsFloat();
        }
        return out;
    }

    private JsonElement requireObjectOrArray(String key) throws ActionException {
        if (!has(key)) {
            throw ActionException.badRequest("missing required field '" + key + "'");
        }
        JsonElement el = body.get(key);
        if (!el.isJsonObject() && !el.isJsonArray()) {
            throw ActionException.badRequest("field '" + key + "' must be an object {x,y,z} or an array [x,y,z]");
        }
        return el;
    }

    /** 便于日志/回显：一个标量原样转字符串，找不到就返回 null。 */
    public String describe(String key) {
        if (!has(key)) {
            return null;
        }
        JsonElement el = body.get(key);
        return el.isJsonPrimitive() ? ((JsonPrimitive) el).getAsString() : el.toString();
    }
}
