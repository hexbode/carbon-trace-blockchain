package com.carbon.trace.service.impl;

import com.carbon.trace.entity.UserSettings;
import com.carbon.trace.mapper.UserSettingsMapper;
import com.carbon.trace.service.UserSettingsService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户账号设置服务实现。
 * 读取时无记录则按默认值（全部开启）补齐，保证首次访问即可返回完整设置；
 * 更新时对请求体做布尔解析，无效值沿用原值。
 */
@Service
public class UserSettingsServiceImpl implements UserSettingsService {

    private final UserSettingsMapper userSettingsMapper;

    /**
     * 构造器注入依赖，由 Spring 容器创建，业务代码不应手工实例化。
     *
     * @param userSettingsMapper 用户设置 Mapper，以 userId 为主键读取与写入设置记录
     */
    public UserSettingsServiceImpl(UserSettingsMapper userSettingsMapper) {
        this.userSettingsMapper = userSettingsMapper;
    }

    /**
     * 查询指定用户的账号设置，记录不存在时先插入一行默认值（四项开关全部开启）。
     *
     * 读取带写副作用：首次访问即补齐记录，保证前端始终拿到完整结构；
     * user_settings 以 userId 为主键，故直接按主键查询。
     *
     * 权限：控制器限制本人或 regulator / admin。
     *
     * @param userId 用户主键（即 user_settings 主键）
     * @return 含 userId 与 msgNotify/smsNotify/loginRemind/weeklyReport 的 Map
     */
    @Override
    public Map<String, Object> getSettings(Integer userId) {
        UserSettings settings = userSettingsMapper.selectById(userId);
        // 无记录则插入一行默认值（全部开启）
        if (settings == null) {
            settings = new UserSettings();
            settings.setUserId(userId);
            settings.setMsgNotify(true);
            settings.setSmsNotify(true);
            settings.setLoginRemind(true);
            settings.setWeeklyReport(true);
            settings.setUpdatedAt(LocalDateTime.now());
            userSettingsMapper.insert(settings);
        }
        return toResult(settings);
    }

    /**
     * 更新指定用户的账号设置，记录不存在时先插入默认行再按请求覆盖。
     *
     * 只有四个开关字段生效，请求体中的其他键被忽略；
     * 每个值交 boolVal 解析：布尔值直接采用，其他非空值按 Boolean.parseBoolean 转换，
     * 字段缺失时沿用原值，避免前端只提交部分开关时把其余开关误置为关闭。
     * 本次更新时间统一刷新为当前时间。
     *
     * 权限：控制器限制本人或 regulator / admin。
     *
     * @param userId 用户主键
     * @param body   请求体，键为 msgNotify/smsNotify/loginRemind/weeklyReport
     * @return 更新后的完整设置
     */
    @Override
    public Map<String, Object> updateSettings(Integer userId, Map<String, Object> body) {
        UserSettings settings = userSettingsMapper.selectById(userId);
        if (settings == null) {
            // 不存在时先落一行默认值，再按请求覆盖
            settings = new UserSettings();
            settings.setUserId(userId);
            settings.setMsgNotify(true);
            settings.setSmsNotify(true);
            settings.setLoginRemind(true);
            settings.setWeeklyReport(true);
            settings.setUpdatedAt(LocalDateTime.now());
            userSettingsMapper.insert(settings);
        }
        settings.setMsgNotify(boolVal(body.get("msgNotify"), settings.getMsgNotify()));
        settings.setSmsNotify(boolVal(body.get("smsNotify"), settings.getSmsNotify()));
        settings.setLoginRemind(boolVal(body.get("loginRemind"), settings.getLoginRemind()));
        settings.setWeeklyReport(boolVal(body.get("weeklyReport"), settings.getWeeklyReport()));
        settings.setUpdatedAt(LocalDateTime.now());
        userSettingsMapper.updateById(settings);
        return toResult(settings);
    }

    /** 组装接口响应：4 个开关以布尔值返回 */
    private Map<String, Object> toResult(UserSettings settings) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", settings.getUserId());
        result.put("msgNotify", settings.getMsgNotify());
        result.put("smsNotify", settings.getSmsNotify());
        result.put("loginRemind", settings.getLoginRemind());
        result.put("weeklyReport", settings.getWeeklyReport());
        return result;
    }

    /** 解析请求体中的开关值；无法解析时沿用原值 */
    private Boolean boolVal(Object value, Boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value != null) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        return fallback;
    }
}
