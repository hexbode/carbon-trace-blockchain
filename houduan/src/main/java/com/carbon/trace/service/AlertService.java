package com.carbon.trace.service;

import com.carbon.trace.entity.Alert;
import com.carbon.trace.entity.EmissionRecord;

import java.util.List;

/**
 * 异常告警服务
 */
public interface AlertService {

    List<Alert> list(String level, String status);

    Alert getById(String id);

    Alert resolve(String id);

    /**
     * 写入时判定：企业新增排放记录后，按规则检查是否产生异常告警。
     * 规则覆盖告警类型：数据异常（data_anomaly）、因子不符（factor_mismatch）、迟报（late_report）。
     */
    void evaluateOnEmissionCreate(EmissionRecord record);

    /** 删除排放记录时，清理该企业该报告期下由规则产生的告警，避免残留失效告警 */
    void removeByEnterpriseAndPeriod(String enterpriseId, String period);
}
