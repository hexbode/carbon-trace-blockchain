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

    public UserSettingsServiceImpl(UserSettingsMapper userSettingsMapper) {
        this.userSettingsMapper = userSettingsMapper;
    }

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
