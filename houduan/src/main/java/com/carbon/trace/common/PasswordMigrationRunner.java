package com.carbon.trace.common;

import com.carbon.trace.entity.User;
import com.carbon.trace.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 密码哈希一次性迁移：启动时把 users 表中残留的明文密码幂等地转换为 {bcrypt} 哈希。
 * 迁移后不影响登录（README 中的密码保持不变，只是存储形式变了）。
 */
@Component
public class PasswordMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PasswordMigrationRunner.class);

    private final UserMapper userMapper;

    public PasswordMigrationRunner(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /** 应用启动后执行：遍历全部账号，仅对仍是明文的密码做一次性哈希迁移（幂等，重复启动无副作用） */
    @Override
    public void run(ApplicationArguments args) {
        List<User> users = userMapper.selectList(null);
        int migrated = 0;
        for (User user : users) {
            // 密码为空或已带 {bcrypt} 前缀的直接跳过，保证迁移幂等
            if (user.getPassword() == null || !PasswordHasher.needsMigration(user.getPassword())) {
                continue;
            }
            user.setPassword(PasswordHasher.encode(user.getPassword()));
            userMapper.updateById(user);
            migrated++;
        }
        if (migrated > 0) {
            log.info("密码哈希迁移完成：{} 个账号已从明文转为 BCrypt 存储。", migrated);
        }
    }
}