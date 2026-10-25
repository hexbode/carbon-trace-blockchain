package com.carbon.trace.chain.support;

import com.carbon.trace.chain.model.ComplianceResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 合规位掩码 → 告警类型/级别/文案标签的统一映射。
 *
 * 判定在合约内完成，级别与中文文案由后端生成；这里保证告警落库与前端展示共用同一套口径。
 *
 * 约束：{@code rule}/{@code type}/{@code level} 是落库与前端联动的稳定标识，
 * 修改会导致历史告警与新告警口径不一致，调整前需同步数据迁移与前端映射。
 */
public final class ComplianceFlags {

    /**
     * 单条规则：rule 规则名 / type 告警类型 / typeLabel 告警类型名 / level 级别。
     *
     * @param rule      规则名（英文，稳定标识）
     * @param type      告警类型编码（落库用）
     * @param typeLabel 告警类型中文名（展示用）
     * @param level     告警级别：high / medium / low
     */
    public record Rule(String rule, String type, String typeLabel, String level) {
    }

    /**
     * 私有构造器：本类只提供位掩码到规则列表的静态映射，禁止实例化。
     */
    private ComplianceFlags() {
    }

    /**
     * 命中的规则列表。
     *
     * 位掩码可组合，因此返回的是全部命中项，顺序固定为 quota → fluctuation → activity → factor → late。
     * 未产生判定（flags &lt; 0，即 {@code FLAGS_UNKNOWN}）时返回空列表，表示「无结论」而非「合规」。
     *
     * @param flags 合约返回的位掩码
     * @return 规则列表，恒不为 null
     */
    public static List<Rule> rules(int flags) {
        List<Rule> rules = new ArrayList<>();
        if (flags < 0) {
            return rules;
        }
        if ((flags & ComplianceResult.FLAG_QUOTA) != 0) {
            rules.add(new Rule("quota", "quota_exceeded", "配额超限", "high"));
        }
        if ((flags & ComplianceResult.FLAG_FLUCTUATION) != 0) {
            rules.add(new Rule("fluctuation", "data_anomaly", "数据异常", "high"));
        }
        if ((flags & ComplianceResult.FLAG_ACTIVITY) != 0) {
            rules.add(new Rule("activity", "data_anomaly", "数据异常", "low"));
        }
        if ((flags & ComplianceResult.FLAG_FACTOR) != 0) {
            rules.add(new Rule("factor", "factor_mismatch", "因子不符", "medium"));
        }
        if ((flags & ComplianceResult.FLAG_LATE) != 0) {
            rules.add(new Rule("late", "late_report", "迟报", "medium"));
        }
        return rules;
    }

    /**
     * flags 是否为「无任何规则命中」，合规判定统一以 0 为通过口径。
     *
     * 注意 {@code FLAGS_UNKNOWN=-1} 同样返回 false：未判定不能视为合规。
     *
     * @param flags 位掩码
     * @return true 表示 flags 恰为 0
     */
    public static boolean compliant(int flags) {
        return flags == 0;
    }
}
