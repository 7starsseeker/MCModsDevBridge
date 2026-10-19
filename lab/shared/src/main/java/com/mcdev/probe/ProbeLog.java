package com.mcdev.probe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 共享层唯一的日志出口。
 *
 * <p>不放进加载器入口类：入口是每个加载器各一份的，共享层不能依赖它。
 */
public final class ProbeLog {
    public static final Logger LOGGER = LoggerFactory.getLogger("mcdev-probe");

    private ProbeLog() {
    }
}