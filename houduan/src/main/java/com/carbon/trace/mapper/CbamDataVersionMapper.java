package com.carbon.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.trace.entity.CbamDataVersion;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * CBAM 排放数据版本 Mapper。
 * bumpVersion 使用 INSERT ... ON DUPLICATE KEY UPDATE 原子自增，避免并发下读改写丢失。
 */
@Mapper
public interface CbamDataVersionMapper extends BaseMapper<CbamDataVersion> {

    @Select("SELECT version FROM cbam_data_versions WHERE enterprise_id = #{enterpriseId} AND report_year = #{reportYear}")
    Long selectVersion(@Param("enterpriseId") String enterpriseId, @Param("reportYear") Integer reportYear);

    @Insert("""
            INSERT INTO cbam_data_versions (enterprise_id, report_year, version, updated_at)
            VALUES (#{enterpriseId}, #{reportYear}, 1, NOW())
            ON DUPLICATE KEY UPDATE version = version + 1, updated_at = NOW()
            """)
    int bumpVersion(@Param("enterpriseId") String enterpriseId, @Param("reportYear") Integer reportYear);
}
