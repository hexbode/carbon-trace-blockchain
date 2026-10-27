package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.CbamReconciliationLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** CBAM 对账日志 Mapper。 */
@Mapper
public interface CbamReconciliationLogMapper extends BaseMapper<CbamReconciliationLog> {

    /** 删除 cutoff 之前的日志，用于 90 天归档清理。 */
    @Delete("DELETE FROM cbam_reconciliation_logs WHERE checked_at < #{cutoff}")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);

    /** 把 cutoff 之前的日志复制到归档表（log_id 唯一，重复执行幂等）。 */
    @org.apache.ibatis.annotations.Insert("""
            INSERT IGNORE INTO cbam_reconciliation_logs_archive
              (log_id, declaration_id, declaration_no, enterprise_id, report_year,
               snapshot_version, live_version, snapshot_total, live_total, status, detail, checked_at, archived_at)
            SELECT id, declaration_id, declaration_no, enterprise_id, report_year,
                   snapshot_version, live_version, snapshot_total, live_total, status, detail, checked_at, NOW()
            FROM cbam_reconciliation_logs
            WHERE checked_at < #{cutoff}
            """)
    int archiveBefore(@Param("cutoff") LocalDateTime cutoff);
}
