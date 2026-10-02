package com.carbon.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.JwtUtil;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.entity.User;
import com.carbon.trace.entity.UserSettings;
import com.carbon.trace.mapper.UserMapper;
import com.carbon.trace.mapper.UserSettingsMapper;
import com.carbon.trace.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 用户服务实现（含登录）
 */
@Service
public class UserServiceImpl implements UserService {

    private static final String DEFAULT_PASSWORD = "123456";

    private final UserMapper userMapper;
    private final UserSettingsMapper userSettingsMapper;
    private final JwtUtil jwtUtil;

    public UserServiceImpl(UserMapper userMapper, UserSettingsMapper userSettingsMapper, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.userSettingsMapper = userSettingsMapper;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public List<User> list(String username, String role) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(username)) {
            wrapper.like(User::getUsername, username);
        }
        if (StringUtils.hasText(role)) {
            wrapper.eq(User::getRole, role);
        }
        wrapper.orderByAsc(User::getId);
        return userMapper.selectList(wrapper);
    }

    @Override
    public User getById(Integer id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    @Override
    public User create(User user) {
        if (!StringUtils.hasText(user.getPassword())) {
            user.setPassword(DEFAULT_PASSWORD);
        }
        if (!StringUtils.hasText(user.getStatus())) {
            user.setStatus("active");
        }
        // 注册日期由后端补齐：created_at 为 NOT NULL 且无默认值，前端表单不提交该字段
        if (user.getCreatedAt() == null) {
            user.setCreatedAt(LocalDate.now());
        }
        userMapper.insert(user);
        return user;
    }

    @Override
    public User update(Integer id, User user) {
        user.setId(id);
        userMapper.updateById(user);
        return getById(id);
    }

    @Override
    @Transactional
    public void delete(Integer id) {
        User user = getById(id);
        if ("admin".equalsIgnoreCase(user.getUsername())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "系统管理员账号不可删除");
        }
        // 先清理个人中心的账号设置，否则会被外键约束挡住导致删除失败
        userSettingsMapper.delete(new LambdaQueryWrapper<UserSettings>()
                .eq(UserSettings::getUserId, id));
        userMapper.deleteById(id);
    }

    @Override
    public User resetPassword(Integer id) {
        User user = getById(id);
        user.setPassword(DEFAULT_PASSWORD);
        userMapper.updateById(user);
        return user;
    }

    @Override
    public void changePassword(Integer id, String oldPassword, String newPassword) {
        User user = getById(id);
        // 旧密码需与数据库当前值一致
        if (!Objects.equals(user.getPassword(), oldPassword)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "当前密码不正确");
        }
        user.setPassword(newPassword);
        userMapper.updateById(user);
    }

    @Override
    public User findByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username).last("limit 1"));
    }

    @Override
    public Map<String, Object> login(String username, String password, String role) {
        User user = findByUsername(username);
        // 账号 + 密码 + 角色 + 状态 四者都需匹配，失败返回 400（不能用 401）
        if (user == null
                || !Objects.equals(user.getPassword(), password)
                || !Objects.equals(user.getRole(), role)
                || !"active".equals(user.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "账号或密码错误，或角色不匹配");
        }
        String token = jwtUtil.generateToken(user);

        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("username", user.getUsername());
        userInfo.put("name", user.getName());
        userInfo.put("role", user.getRole());
        userInfo.put("enterpriseId", user.getEnterpriseId());
        userInfo.put("orgId", user.getOrgId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("user", userInfo);
        return result;
    }

    @Override
    public Map<String, Object> currentUser() {
        LoginUser loginUser = UserContext.get();
        if (loginUser == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("username", loginUser.getUsername());
        userInfo.put("name", loginUser.getName());
        userInfo.put("role", loginUser.getRole());
        userInfo.put("enterpriseId", loginUser.getEnterpriseId());
        userInfo.put("orgId", loginUser.getOrgId());
        return userInfo;
    }
}
