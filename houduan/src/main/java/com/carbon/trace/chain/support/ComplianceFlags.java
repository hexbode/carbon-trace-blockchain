package com.carbon.trace.chain.support;

import com.carbon.trace.chain.model.ComplianceResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 合规位掩码 → 告警类型/级别/文案标签的统一映射。
 *
 * 判定在合约内完成，级别与中文文案由后端生成；这里保证告警落库与前端展示共用同一套口径。
 */
public final class ComplianceFlags {

    /** 单条规则：rule 规则名 / type 告警类型 / typeLabel 告警类型名 / level 级别 */
    public record Rule(String rule, String type, String typeLabel, String level) {
    }

    private ComplianceFlags() {
    }

    /** 命中的规则列表；未产生判定（flags < 0）时返回空列表 */
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

    public static boolean compliant(int flags) {
        return flags == 0;
    }
}
