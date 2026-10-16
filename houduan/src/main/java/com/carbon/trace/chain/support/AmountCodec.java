package com.carbon.trace.chain.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额定点编解码：链上使用整数存储（Solidity 无浮点）。
 *
 * 排放量 ×100（2 位小数）、排放因子 ×10000（4 位小数）、报告期转整数 YYYYMM。
 */
public final class AmountCodec {

    private static final BigDecimal AMOUNT_SCALE = new BigDecimal("100");
    private static final BigDecimal FACTOR_SCALE = new BigDecimal("10000");

    private AmountCodec() {
    }

    /** 排放量 → 链上定点（×100），null 视为 0 */
    public static long toScaled(BigDecimal amount) {
        if (amount == null) {
            return 0L;
        }
        return amount.multiply(AMOUNT_SCALE).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 链上定点（×100）→ 排放量 */
    public static BigDecimal fromScaled(Long scaled) {
        if (scaled == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(scaled).divide(AMOUNT_SCALE, 2, RoundingMode.HALF_UP);
    }

    /** 排放因子 → 链上定点（×10000），null 视为 0 */
    public static long factorToScaled(BigDecimal factor) {
        if (factor == null) {
            return 0L;
        }
        return factor.multiply(FACTOR_SCALE).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 链上定点（×10000）→ 排放因子 */
    public static BigDecimal factorFromScaled(Long scaled) {
        if (scaled == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(scaled).divide(FACTOR_SCALE, 4, RoundingMode.HALF_UP);
    }

    /** 报告期 YYYY-MM → 整数 YYYYMM（非法输入返回 0） */
    public static long toYyyymm(String period) {
        if (period == null) {
            return 0L;
        }
        String value = period.trim().replace("-", "");
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
