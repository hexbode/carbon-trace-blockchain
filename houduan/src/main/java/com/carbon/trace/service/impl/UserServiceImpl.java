package com.carbon.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.JwtUtil;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.PasswordHasher;
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

    /** 登录失败锁定：同一账号连续失败上限 */
    private static final int MAX_LOGIN_FAILURES = 5;
    /** 达到上限后的锁定时长（毫秒） */
    private static final long LOCK_MILLIS = 5 * 60_000L;

    private final Map<String, LoginFail> loginFails = new java.util.concurrent.ConcurrentHashMap<>();

    private record LoginFail(int count, long lockedUntil) {
    }

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
            user.setPassword(PasswordHasher.encode(DEFAULT_PASSWORD));
        } else {
            user.setPassword(PasswordHasher.encode(user.getPassword()));
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
        User current = getById(id);
        LoginUser operator = UserContext.get();
        boolean manager = operator != null
                && ("admin".equals(operator.getRole()) || "regulator".equals(operator.getRole()));

        // 白名单更新，防止通过本接口提权或篡改账号凭据：
        //  - 任何角色只能改资料展示字段；
        //  - 仅监管/管理员可改 role 与 status；
        //  - username / password / enterpriseId / orgId 一律不可通过本接口修改（密码走 changePassword）。
        current.setName(user.getName());
        current.setPhone(user.getPhone());
        current.setEmail(user.getEmail());
        current.setSignature(user.getSignature());
        current.setAvatar(user.getAvatar());
        if (manager) {
            if (user.getRole() != null) {
                current.setRole(user.getRole());
            }
            if (user.getStatus() != null) {
                current.setStatus(user.getStatus());
            }
            if (user.getEnterpriseId() != null) {
                current.setEnterpriseId(user.getEnterpriseId());
            }
            if (user.getOrgId() != null) {
                current.setOrgId(user.getOrgId());
            }
        }
        userMapper.updateById(current);
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
        user.setPassword(PasswordHasher.encode(DEFAULT_PASSWORD));
        userMapper.updateById(user);
        loginFails.remove(user.getUsername());
        return user;
    }

    @Override
    public void changePassword(Integer id, String oldPassword, String newPassword) {
        User user = getById(id);
        // 旧密码需与数据库当前值一致（兼容迁移前的明文与迁移后的 BCrypt）
        if (!PasswordHasher.matches(oldPassword, user.getPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "当前密码不正确");
        }
        if (!StringUtils.hasText(newPassword) || newPassword.trim().length() < 6) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "新密码不能为空且长度不能少于 6 位");
        }
        user.setPassword(PasswordHasher.encode(newPassword));
        userMapper.updateById(user);
        loginFails.remove(user.getUsername());
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
        // 登录失败锁定：同一账号连续失败 5 次锁 5 分钟（内存计数，重启即解锁）
        LoginFail fail = loginFails.get(username);
        if (fail != null && fail.lockedUntil() > System.currentTimeMillis()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "失败次数过多，账号已临时锁定，请稍后再试");
        }

        User user = findByUsername(username);
        // 账号 + 密码 + 角色 + 状态 四者都需匹配，失败返回 400（不能用 401）
        if (user == null
                || !PasswordHasher.matches(password, user.getPassword())
                || !Objects.equals(user.getRole(), role)
                || !"active".equals(user.getStatus())) {
            recordLoginFailure(username, fail);
            throw new ApiException(HttpStatus.BAD_REQUEST, "账号或密码错误，或角色不匹配");
        }
        loginFails.remove(username);

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

    private void recordLoginFailure(String username, LoginFail fail) {
        if (username == null) {
            return;
        }
        int count = fail == null ? 1 : fail.count() + 1;
        long lockedUntil = count >= MAX_LOGIN_FAILURES ? System.currentTimeMillis() + LOCK_MILLIS : 0L;
        loginFails.put(username, new LoginFail(count, lockedUntil));
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
