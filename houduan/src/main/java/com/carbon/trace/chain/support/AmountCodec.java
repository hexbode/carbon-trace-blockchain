package com.carbon.trace.chain.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额定点编解码：链上使用整数存储（Solidity 无浮点）。
 *
 * 排放量 ×100（2 位小数）、排放因子 ×10000（4 位小数）、报告期转整数 YYYYMM。
 *
 * 全部转换均使用 {@link RoundingMode#HALF_UP}（四舍五入），链上与本地的判定必须在同一舍入口径下，
 * 否则会出现「界面显示未超限、链上判定超限」的偏差。
 * null 一律按 0 处理，保证空字段不会导致 NPE 或编码失败。
 *
 * 溢出约束：转链上整数使用 {@code longValueExact()}，超过 long 范围会抛
 * {@link ArithmeticException}；业务量级（吨、因子）远低于该上限，出现即说明入参异常。
 */
public final class AmountCodec {

    /** 排放量缩放因子（2 位小数） */
    private static final BigDecimal AMOUNT_SCALE = new BigDecimal("100");
    /** 排放因子缩放因子（4 位小数） */
    private static final BigDecimal FACTOR_SCALE = new BigDecimal("10000");

    /**
     * 私有构造器：本类只提供无状态的静态编解码方法，禁止实例化。
     */
    private AmountCodec() {
    }

    /**
     * 排放量 → 链上定点整数（×100）。
     *
     * 先缩放再四舍五入到整数，因此输入超过两位小数的值会被舍入，而不是截断。
     *
     * @param amount 排放量十进制值，可为 null（按 0 处理）
     * @return 放大 100 倍后的整数
     * @throws ArithmeticException 结果超出 long 表示范围时抛出（属于入参异常，不应静默截断）
     */
    public static long toScaled(BigDecimal amount) {
        if (amount == null) {
            return 0L;
        }
        return amount.multiply(AMOUNT_SCALE).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * 链上定点整数（×100）→ 排放量。
     *
     * 固定保留 2 位小数，与合约的定点精度一致，避免前端出现 1.005 这类超出精度的展示值。
     *
     * @param scaled 链上整数，可为 null（按 0 处理）
     * @return 还原后的排放量，恒不为 null
     */
    public static BigDecimal fromScaled(Long scaled) {
        if (scaled == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(scaled).divide(AMOUNT_SCALE, 2, RoundingMode.HALF_UP);
    }

    /**
     * 排放因子 → 链上定点整数（×10000）。
     *
     * 因子通常有 3~4 位小数，因此使用比排放量更高的精度，不能与 {@link #toScaled(BigDecimal)} 混用。
     *
     * @param factor 排放因子十进制值，可为 null（按 0 处理）
     * @return 放大 10000 倍后的整数
     * @throws ArithmeticException 结果超出 long 表示范围时抛出
     */
    public static long factorToScaled(BigDecimal factor) {
        if (factor == null) {
            return 0L;
        }
        return factor.multiply(FACTOR_SCALE).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * 链上定点整数（×10000）→ 排放因子。
     *
     * @param scaled 链上整数，可为 null（按 0 处理）
     * @return 还原后固定 4 位小数的因子，恒不为 null
     */
    public static BigDecimal factorFromScaled(Long scaled) {
        if (scaled == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(scaled).divide(FACTOR_SCALE, 4, RoundingMode.HALF_UP);
    }

    /**
     * 报告期 YYYY-MM → 整数 YYYYMM。
     *
     * 兼容带短横线（2024-01）与已去横线（202401）两种写法：先去掉全部短横线再校验长度。
     * 长度不足/超出或非数字均返回 0，表示「报告期无效」，由调用方决定是否按迟报处理。
     *
     * @param period 报告期字符串，可为 null
     * @return YYYYMM 整数；非法输入返回 0
     */
    public static long toYyyymm(String period) {
        if (period == null) {
            return 0L;
        }
        String value = period.trim().replace("-", "");
        // 只接受 6 位（YYYYMM）；长度不符直接判定非法，避免把 "2024-1" 这类输入误解析
        if (value.length() != 6) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
