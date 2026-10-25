package com.carbon.trace.chain;

/**
 * 链运行模式。
 *
 * WEBASE：真实 FISCO BCOS 联盟链（通过 WeBASE-Front REST 接口调用合约），生产/演示使用。
 * STUB  ：离线开发桩，仅用于本机没有 WeBASE 时保证应用可启动、接口回归可跑，不具备任何链特性。
 *
 * 模式的唯一来源是配置项 {@code carbon.chain.mode}，由 {@code ChainProperties#chainMode()}
 * 解析后用于两侧的 {@code @ConditionalOnProperty} 装配。不要在其他位置自行比较字符串，
 * 以免大小写/空白差异导致真实链与桩被同时或错误装配。
 */
public enum ChainMode {
    WEBASE,
    STUB;

    /**
     * 字符串 → 枚举。
     *
     * 空白或未知值一律回退 STUB：宁可降级为离线桩，也不误选真实链触发连锁异常。
     * 解析前会去掉首尾空白并转大写，因此 {@code "Webase"}、{@code " webase "} 均可识别。
     *
     * @param value 配置中的模式字符串，可为 null
     * @return 对应枚举，无法识别时返回 {@link #STUB}
     */
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
