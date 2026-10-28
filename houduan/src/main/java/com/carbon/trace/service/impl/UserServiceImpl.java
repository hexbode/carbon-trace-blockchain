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
 * 用户服务实现（含登录）。
 * 事务边界：仅删除用户开启事务（先清账号设置避免外键约束，再删用户）；其余单表写操作依赖 MyBatis-Plus 默认自动提交。
 * 安全设计：更新走白名单防止提权；登录校验「账号+密码+角色+状态」四要素，失败按内存计数锁定（重启即解锁）；
 * 密码统一走 BCrypt（兼容迁移前的明文），响应不回显密码。
 */
@Service
public class UserServiceImpl implements UserService {

    private static final String DEFAULT_PASSWORD = "123456";

    /** 新密码最小长度：强制改密策略要求 ≥ 8 位，并同时包含大小写字母与数字 */
    private static final int MIN_PASSWORD_LENGTH = 8;

    /** 登录失败锁定：同一账号连续失败上限 */
    private static final int MAX_LOGIN_FAILURES = 5;
    /** 达到上限后的锁定时长（毫秒） */
    private static final long LOCK_MILLIS = 5 * 60_000L;

    /** 账号 → 登录失败计数与锁定截止时间（内存态，重启清空） */
    private final Map<String, LoginFail> loginFails = new java.util.concurrent.ConcurrentHashMap<>();

    /** 单个账号的登录失败计数与锁定截止时间快照 */
    private record LoginFail(int count, long lockedUntil) {
    }

    private final UserMapper userMapper;
    private final UserSettingsMapper userSettingsMapper;
    private final JwtUtil jwtUtil;

    /**
     * 构造器注入依赖，由 Spring 容器创建，业务代码不应手工实例化。
     *
     * @param userMapper         用户 Mapper，负责账号查询、密码更新与状态维护
     * @param userSettingsMapper 用户设置 Mapper，新增用户时同步初始化其默认设置
     * @param jwtUtil            JWT 工具，登录成功时签发、校验令牌
     */
    public UserServiceImpl(UserMapper userMapper, UserSettingsMapper userSettingsMapper, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.userSettingsMapper = userSettingsMapper;
        this.jwtUtil = jwtUtil;
    }

    /**
     * 按账号、角色条件查询用户列表（账号模糊、角色精确，均按主键升序）。
     *
     * 权限：服务层不做鉴权，控制器仅允许 regulator / admin 调用本查询。
     *
     * @param username 账号关键字；空白时不参与过滤
     * @param role     角色编码；空白时不参与过滤
     * @return 用户列表；密码字段因 WRITE_ONLY 不会序列化到响应
     */
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

    /**
     * 按主键查询用户，作为其余写操作的统一存在性校验入口。
     *
     * @param id 用户主键
     * @return 用户实体
     * @throws ApiException 用户不存在时抛 404（NOT_FOUND）
     */
    @Override
    public User getById(Integer id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    /**
     * 新增用户：补齐密码、状态与注册日期后落库。
     *
     * 未提交密码时写入统一初始密码 {@value #DEFAULT_PASSWORD} 的 BCrypt 哈希；
     * 状态为空默认 active；createdAt 为 NOT NULL 且无默认值，由后端补当日。
     * 账号唯一性由数据库唯一键（uk_user_username）保证，本方法不预检，重复时插入异常上抛。
     *
     * 权限：控制器仅允许 regulator / admin 调用。
     *
     * @param user 用户实体（username、role 必填）
     * @return 落库后的用户实体，id 为数据库自增主键
     */
    @Override
    public User create(User user) {
        // 密码统一以 BCrypt（含 {bcrypt} 前缀）入库，历史明文由启动迁移处理
        boolean defaultPassword = !StringUtils.hasText(user.getPassword());
        if (defaultPassword) {
            user.setPassword(PasswordHasher.encode(DEFAULT_PASSWORD));
            // 新建账号使用统一初始密码时强制首次登录改密，避免弱密码长期留存
            user.setMustChangePassword(true);
        } else {
            user.setPassword(PasswordHasher.encode(user.getPassword()));
            if (user.getMustChangePassword() == null) {
                user.setMustChangePassword(false);
            }
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

    /**
     * 按主键更新用户资料，采用字段白名单防止提权与凭据篡改。
     *
     * 白名单规则：
     *  - 所有调用者可改展示类字段（name/phone/email/signature/avatar），
     *    但提交空值会被 MyBatis-Plus 默认的 NOT_NULL 更新策略忽略，原值保留；
     *  - 仅 regulator / admin（由 UserContext 判定）可改 role/status/enterpriseId/orgId，
     *    且仅在提交值非空时覆盖，避免普通资料编辑把角色或归属清空；
     *  - username、password、id、createdAt 不通过本接口修改（密码走 changePassword / resetPassword）。
     *
     * 权限：控制器层限制本人或 regulator / admin，服务层不重复鉴权。
     *
     * @param id   目标用户主键
     * @param user 提交的资料，非白名单字段被忽略
     * @return 更新后从库中重新读取的用户
     * @throws ApiException 目标用户不存在时抛 404
     */
    @Override
    public User update(Integer id, User user) {
        User current = getById(id);
        // 操作者角色决定能否修改 role/status 等管理字段；上下文缺失时按非管理员处理
        LoginUser operator = UserContext.get();
        boolean manager = operator != null
                && ("admin".equals(operator.getRole()) || "regulator".equals(operator.getRole()));

        // 白名单更新，防止通过本接口提权或篡改账号凭据：
        //  - 任何角色只能改资料展示字段；
        //  - 仅监管/管理员可改 role、status 与归属 enterpriseId / orgId；
        //  - username / password 一律不可通过本接口修改（密码走 changePassword）。
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

    /**
     * 按主键删除用户，并先清理该用户的账号设置行。
     *
     * 事务原因：user_settings.user_id 外键指向 users.id，顺序颠倒会被外键约束拦截；
     * 内置管理员账号（username 为 admin，忽略大小写）为系统保留账号，禁止删除。
     * 业务表只以姓名字符串记录填报/审核人，不与 users 建外键，因此删除用户不会影响历史业务数据。
     *
     * 权限：控制器仅允许 regulator / admin 调用。
     *
     * @param id 用户主键
     * @throws ApiException 用户不存在抛 404；内置管理员账号抛 400
     */
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

    /**
     * 管理员重置密码：覆盖为初始密码的 BCrypt 哈希，并解除该账号的登录失败锁定。
     *
     * 解锁仅清除本进程内存中的失败计数，不改动数据库状态；多实例部署时其他实例的计数不受影响。
     *
     * 权限：控制器仅允许 regulator / admin 调用；响应不回显密码。
     *
     * @param id 用户主键
     * @return 重置后的用户实体
     * @throws ApiException 用户不存在时抛 404
     */
    @Override
    public User resetPassword(Integer id) {
        // 管理员重置：写入统一默认密码，并解除该账号的登录失败暂时锁定
        User user = getById(id);
        user.setPassword(PasswordHasher.encode(DEFAULT_PASSWORD));
        // 重置后强制用户下次登录改密，避免管理员代设的弱密码长期有效
        user.setMustChangePassword(true);
        userMapper.updateById(user);
        loginFails.remove(user.getUsername());
        return user;
    }

    /**
     * 修改密码：校验旧密码与密码强度后写入新密码的 BCrypt 哈希，并清除强制改密标志。
     *
     * 旧密码经 PasswordHasher.matches 校验，同时兼容 {bcrypt} 哈希与迁移前的明文存储；
     * 新密码去除首尾空白后必须满足：长度 ≥ 8、同时包含大写字母/小写字母/数字、
     * 且不得与初始密码 123456 相同（强制改密场景下弱密码等于没改）。
     * 改密成功即清除该账号的连续失败计数，并把 must_change_password 置 0；
     * 已签发的旧令牌仍带 mustChangePassword=true 声明，由 AuthInterceptor 继续拦截，
     * 因此前端必须清理本地令牌并重新登录，等价于让旧弱密码会话失效。
     *
     * 权限：控制器限制本人或 regulator / admin。
     *
     * @param id          用户主键
     * @param oldPassword 原密码（明文）
     * @param newPassword 新密码（明文，满足强度策略）
     * @throws ApiException 用户不存在抛 404；旧密码不符、新密码不合规抛 400
     */
    @Override
    public void changePassword(Integer id, String oldPassword, String newPassword) {
        User user = getById(id);
        // 旧密码需与数据库当前值一致（兼容迁移前的明文与迁移后的 BCrypt）
        if (!PasswordHasher.matches(oldPassword, user.getPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "当前密码不正确");
        }
        String password = validateNewPassword(newPassword);
        user.setPassword(PasswordHasher.encode(password));
        // 改密成功即解除强制改密：数据库标志清零，后续重新登录签发的新令牌不再携带该声明
        user.setMustChangePassword(false);
        userMapper.updateById(user);
        loginFails.remove(user.getUsername());
    }

    /**
     * 校验新密码强度并返回去除首尾空白后的密码。
     *
     * 规则（与前端提示保持一致）：
     *   1. 长度 ≥ 8 位；
     *   2. 不能使用初始密码 123456；
     *   3. 同时包含大写字母、小写字母与数字，避免“长但简单”的弱口令。
     *
     * @param newPassword 明文新密码
     * @return 去除首尾空白后的密码，用于 BCrypt 编码
     * @throws ApiException 不满足任一规则时抛 400
     */
    private String validateNewPassword(String newPassword) {
        String password = newPassword == null ? "" : newPassword.trim();
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "新密码长度不能少于 8 位");
        }
        if (DEFAULT_PASSWORD.equals(password)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "新密码不能使用初始密码 123456");
        }
        boolean hasUpper = password.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = password.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasUpper || !hasLower || !hasDigit) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "新密码需同时包含大写字母、小写字母和数字");
        }
        return password;
    }

    /**
     * 修改当前登录用户密码（强制改密页专用入口，账号取自令牌，不接受客户端传入用户 id）。
     *
     * @param username    当前登录账号（来自 AuthInterceptor 解析的 JWT）
     * @param oldPassword 原密码
     * @param newPassword 新密码
     * @throws ApiException 账号不存在抛 404；原密码不符或新密码不合规抛 400
     */
    @Override
    public void changeOwnPassword(String username, String oldPassword, String newPassword) {
        User user = findByUsername(username);
        if (user == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        changePassword(user.getId(), oldPassword, newPassword);
    }

    /**
     * 按账号精确查询用户（最多取一条）。
     *
     * 供登录与账号去重场景使用；空白账号直接返回 null，由调用方处理不存在的情况，
     * 查询附带 limit 1 以避免脏数据下 selectOne 抛多行异常。
     *
     * @param username 登录账号
     * @return 匹配的用户；账号为空或不存在时返回 null
     */
    @Override
    public User findByUsername(String username) {
        // 空账号直接返回 null（登录/去重调用方需自行处理不存在场景）
        if (!StringUtils.hasText(username)) {
            return null;
        }
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username).last("limit 1"));
    }

    /**
     * 登录校验：账号 + 密码 + 角色 + 状态四要素全部匹配才签发令牌。
     *
     * 流程与边界：
     *  1. 先查内存失败计数，处于锁定期直接拒绝（429），不再校验密码，避免锁定期间继续试错；
     *  2. 密码由 PasswordHasher.matches 校验，兼容 {bcrypt} 哈希与迁移前明文；
     *  3. 角色必须与前端所选登录身份一致，status 不为 active 的账号一律拒绝；
     *  4. 任一要素失败统一返回 400（而非 401），不区分“账号不存在/密码错/角色不符”，
     *     避免账号枚举；同时累加失败次数，连续失败 5 次锁定 5 分钟；
     *  5. 校验通过清零计数并签发 JWT（有效期由 carbon.jwt.expire-hours 控制）。
     *
     * 锁定状态仅存于本进程内存，重启即清空；多实例部署时各实例独立计数。
     *
     * @param username 登录账号
     * @param password 明文密码
     * @param role     前端选择的登录角色（enterprise/auditor/regulator/admin）
     * @return 含 token（JWT）与 user（username/name/role/enterpriseId/orgId）的 Map
     * @throws ApiException 锁定期内抛 429；账号、密码、角色或状态不匹配抛 400
     */
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
            // 失败分支不区分具体原因，统一计数并返回同一文案；lockedUntil 仅在本次达到上限时设置
            recordLoginFailure(username, fail);
            throw new ApiException(HttpStatus.BAD_REQUEST, "账号或密码错误，或角色不匹配");
        }
        // 登录成功才清零计数：锁定只针对「连续」失败，成功一次即重置
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
        // 强制改密标记：前端据此在不进入业务布局的前提下跳转改密页
        result.put("mustChangePassword", Boolean.TRUE.equals(user.getMustChangePassword()));
        return result;
    }

    /**
     * 记录一次登录失败：累加计数，达到上限后设置锁定截止时间；锁定到期后由 login 校验逻辑放行。
     *
     * 边界：锁定到期并不会清零计数，因此到期后再失败一次会从原计数继续累加并立即再次锁定；
     * 只有登录成功、改密或管理员重置密码才移除计数。username 为 null 时无法按账号计数，直接忽略。
     */
    private void recordLoginFailure(String username, LoginFail fail) {
        if (username == null) {
            return;
        }
        int count = fail == null ? 1 : fail.count() + 1;
        long lockedUntil = count >= MAX_LOGIN_FAILURES ? System.currentTimeMillis() + LOCK_MILLIS : 0L;
        loginFails.put(username, new LoginFail(count, lockedUntil));
    }

    /**
     * 读取当前登录用户信息（由 AuthInterceptor 解析 JWT 后写入 UserContext）。
     *
     * 只返回令牌载荷中的展示字段，不回查数据库，因此用户资料变更需重新登录才会刷新。
     *
     * @return 含 username/name/role/enterpriseId/orgId 的 Map
     * @throws ApiException 上下文无登录人（未登录或令牌失效）时抛 401
     */
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
