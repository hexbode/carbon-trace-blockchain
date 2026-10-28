package com.carbon.trace.controller;

import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.entity.User;
import com.carbon.trace.service.UserService;
import com.carbon.trace.service.UserSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 用户管理接口：账号的增删改查、密码重置/修改与账号设置维护。
 *
 * 暴露接口：
 *   GET    /api/users                         用户列表（可按 username / role 过滤）；
 *   POST   /api/users                         新建用户；
 *   PUT    /api/users/{id}                    修改用户资料；
 *   DELETE /api/users/{id}                    删除用户；
 *   POST   /api/users/{id}/reset-password     重置密码为初始值 123456；
 *   PUT    /api/users/{id}/password           修改密码（body {oldPassword, newPassword}）；
 *   GET    /api/users/{id}/settings           查询账号设置（4 个布尔开关）；
 *   PUT    /api/users/{id}/settings           更新账号设置。
 * 角色约定：新增、删除、重置密码等管理操作仅 regulator / admin；修改资料、改密、账号设置
 * 允许本人操作（requireSelfOrManager）；用户目录含联系方式等敏感字段，仅监管/管理员可查。
 *
 * 依赖：{@link UserService}（账号读写、密码哈希与角色字段白名单）、
 *       {@link UserSettingsService}（个人中心 4 个布尔开关）。
 * 安全边界：
 *   1. 列表与写操作先做角色判断，再交给 Service；Service 层不再重复鉴权；
 *   2. 本人操作通过“令牌里的 username == 目标账号 username”判定，不依赖请求参数；
 *   3. 资料更新的字段白名单在 Service 内实现：普通用户即使提交 role/status/enterpriseId 也会被忽略，
 *      因此本层的“本人可改”不等于“本人可提权”；
 *   4. 密码字段为 WRITE_ONLY，任何响应都不会回显密码哈希；
 *   5. 本控制器只操作数据库，不触发链上写操作。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final UserSettingsService userSettingsService;

    /** 构造注入用户服务与账号设置服务；控制器无状态 */
    public UserController(UserService userService, UserSettingsService userSettingsService) {
        this.userService = userService;
        this.userSettingsService = userSettingsService;
    }

    /**
     * GET /api/users —— 用户列表。
     *
     * 权限：仅 regulator / admin（requireManagerRole），其余角色 403。
     * 参数：username 账号模糊过滤、role 角色过滤，均可省略。
     * 返回：List&lt;User&gt;；password 为 WRITE_ONLY，不会出现在响应中。
     * 错误码：401 未登录；403 角色不足；500 未知异常。
     * 链上写：无。
     */
    @GetMapping
    public List<User> list(@RequestParam(required = false) String username,
                           @RequestParam(required = false) String role) {
        // 用户目录含账号、联系方式等信息，仅监管/管理员可查
        requireManagerRole();
        return userService.list(username, role);
    }

    /**
     * GET /api/users/me —— 当前登录用户的个人资料。
     *
     * 权限：任意已登录角色（按令牌中的 username 查本人，不接受 id 参数，无法越权）。
     * 返回：User 实体，密码字段不回显。
     * 错误码：401 未登录；404 令牌对应的账号已被删除。
     * 链上写：无。
     */
    @GetMapping("/me")
    public User me() {
        LoginUser current = UserContext.get();
        if (current == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        User user = userService.findByUsername(current.getUsername());
        if (user == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    /**
     * POST /api/users —— 新建用户。
     *
     * 权限：仅 regulator / admin。
     * 参数（JSON body）：User 实体，username / role 必填；password 缺省时写入初始密码 123456 的
     * BCrypt 哈希并置强制改密标志；status 缺省 active；createdAt 由服务端补当日。
     * 返回：落库后的 User（含自增 id，不含密码）。
     * 错误码：400 账号重复（唯一键冲突）；401 未登录；403 角色不足；500 未知异常。
     * 链上写：无（用户账号不属于链上存证对象）。
     */
    @PostMapping
    public User create(@RequestBody User user) {
        requireManagerRole();
        return userService.create(user);
    }

    /**
     * PUT /api/users/{id} —— 修改用户资料。
     *
     * 权限：regulator / admin 可改任意用户；其他角色仅能改本人（requireSelfOrManager），否则 403。
     * 参数：路径 id 为目标用户主键；body 为 User，仅 name/phone/email/signature/avatar 等展示字段生效，
     * role/status/enterpriseId/orgId 仅监管/管理员可改，其余字段（username、password 等）被忽略。
     * 返回：更新后重新读取的 User。
     * 错误码：400 字段校验失败；401 未登录；403 越权改他人；404 目标用户不存在。
     * 链上写：无。
     */
    @PutMapping("/{id}")
    public User update(@PathVariable Integer id, @RequestBody User user) {
        requireSelfOrManager(id);
        return userService.update(id, user);
    }

    /**
     * DELETE /api/users/{id} —— 删除用户。
     *
     * 权限：仅 regulator / admin。
     * 参数：路径 id 为目标用户主键；删除前会连带清理该账号的账号设置记录。
     * 返回：空 Map（前端只关心状态码）。
     * 错误码：400 目标为内置 admin 账号（禁止删除）；401 未登录；403 角色不足；404 用户不存在。
     * 链上写：无。
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Integer id) {
        requireManagerRole();
        userService.delete(id);
        return Collections.emptyMap();
    }

    /**
     * POST /api/users/{id}/reset-password —— 重置密码为初始值 123456。
     *
     * 权限：仅 regulator / admin（用于用户忘记密码时管理员代操作）。
     * 参数：路径 id 为目标用户主键。
     * 返回：重置后的 User（密码不回显）；该账号下次登录会被强制改密。
     * 错误码：401 未登录；403 角色不足；404 用户不存在。
     * 链上写：无；副作用仅限数据库与当前进程内的登录失败计数清零。
     */
    @PostMapping("/{id}/reset-password")
    public User resetPassword(@PathVariable Integer id) {
        requireManagerRole();
        return userService.resetPassword(id);
    }

    /**
     * PUT /api/users/{id}/password —— 修改密码。
     *
     * 权限：本人或 regulator / admin（requireSelfOrManager）。
     * 参数：路径 id 为目标用户主键；body {oldPassword, newPassword}，
     * 新密码需 ≥ 8 位并同时包含大写字母、小写字母与数字，且不得为初始密码 123456。
     * 返回：空 Map。
     * 错误码：400 旧密码不符或新密码不合规；401 未登录；403 越权；404 用户不存在。
     * 链上写：无。
     */
    @PutMapping("/{id}/password")
    public Map<String, Object> changePassword(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        requireSelfOrManager(id);
        userService.changePassword(id, str(body.get("oldPassword")), str(body.get("newPassword")));
        return Collections.emptyMap();
    }

    /**
     * GET /api/users/{id}/settings —— 查询账号设置。
     *
     * 权限：本人或 regulator / admin。
     * 参数：路径 id 为目标用户主键。
     * 返回：4 个布尔开关（提醒 / 订阅类偏好）组成的 Map；未初始化时返回默认值。
     * 错误码：401 未登录；403 越权；404 用户不存在。
     * 链上写：无。
     */
    @GetMapping("/{id}/settings")
    public Map<String, Object> getSettings(@PathVariable Integer id) {
        requireSelfOrManager(id);
        return userSettingsService.getSettings(id);
    }

    /**
     * PUT /api/users/{id}/settings —— 更新账号设置。
     *
     * 权限：本人或 regulator / admin。
     * 参数：路径 id；body 为 4 个布尔开关字段，未提交的字段保持原值。
     * 返回：更新后的账号设置 Map。
     * 错误码：400 字段类型非法；401 未登录；403 越权；404 用户不存在。
     * 链上写：无。
     */
    @PutMapping("/{id}/settings")
    public Map<String, Object> updateSettings(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        requireSelfOrManager(id);
        return userSettingsService.updateSettings(id, body);
    }

    /**
     * 安全地把请求体字段转字符串。
     *
     * @param value 任意 JSON 值
     * @return 字段缺失时为 null
     */
    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 角色限制：用户的新增、删除、重置密码等管理操作仅监管端（regulator）与系统管理员（admin）可执行。
     *
     * @throws ApiException 403 其他角色（含企业端、审核端）
     */
    private void requireManagerRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"regulator".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权执行用户管理操作");
        }
    }

    /**
     * 权限限制：仅监管端/系统管理员可操作任意用户，其他角色只能操作本人账号
     * （用于个人中心修改资料、密码与账号设置）。
     *
     * 实现要点：先查目标用户（不存在直接 404），再用令牌中的 username 与目标账号比对；
     * 不能改成比较路径参数与登录人 id，因为令牌里只有 username，且 id 可能被猜到。
     *
     * @param id 目标用户主键
     * @throws ApiException 403 非本人且非监管/管理员；404 目标用户不存在
     */
    private void requireSelfOrManager(Integer id) {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if ("regulator".equals(role) || "admin".equals(role)) {
            return;
        }
        User target = userService.getById(id);
        String username = current == null ? null : current.getUsername();
        if (username == null || !username.equals(target.getUsername())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权操作其他用户信息");
        }
    }
}
