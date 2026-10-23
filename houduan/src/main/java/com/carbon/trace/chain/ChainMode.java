package com.carbon.trace.chain;

/**
 * 链运行模式。
 *
 * WEBASE：真实 FISCO BCOS 联盟链（通过 WeBASE-Front REST 接口调用合约），生产/演示使用。
 * STUB  ：离线开发桩，仅用于本机没有 WeBASE 时保证应用可启动、接口回归可跑，不具备任何链特性。
 */
public enum ChainMode {
    WEBASE,
    STUB;

    /** 字符串 → 枚举；空白或未知值一律回退 STUB，宁可降级为离线桩也不误选真实链触发连锁异常 */
    public static ChainMode from(String value) {
        if (value == null || value.isBlank()) {
            return STUB;
        }
        try {
            return ChainMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return STUB;
        }
    }
}
