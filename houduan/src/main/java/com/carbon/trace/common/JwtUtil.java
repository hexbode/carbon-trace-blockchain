package com.carbon.trace.common;

import com.carbon.trace.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具：签发与解析登录令牌。
 *
 * 职责：登录成功后基于用户信息签发 HS256 签名令牌；鉴权拦截器 AuthInterceptor
 * 持本类按同一密钥解析令牌并校验签名/有效期。
 * claims 约定：subject=username，并携带 role、enterpriseId、orgId、name 等业务字段，
 * 解析后由拦截器组装为 LoginUser 供鉴权与数据隔离使用。
 *
 * 安全边界：
 *   1. 令牌由服务端使用 {@code carbon.jwt.secret} 以 HS256 签名，解析时同时校验签名与 exp，
 *      因此 claims 中的 role / enterpriseId 可视为可信，服务端不得再接受客户端另行传入的归属参数；
 *   2. JWT 无状态、不可主动吊销：泄露的令牌在有效期内仍然可用，只能通过更换 secret 或缩短有效期失效；
 *   3. 密钥长度必须满足 HS256 的最小要求（32 字节），否则启动阶段构造密钥时会失败。
 */
@Component
public class JwtUtil {

    /** 签名密钥，来源：application 配置项 carbon.jwt.secret（HS256 算法，密钥需满足最小长度） */
    @Value("${carbon.jwt.secret}")
    private String secret;

    /** 令牌有效期（小时），来源：carbon.jwt.expire-hours，缺省默认 24 小时 */
    @Value("${carbon.jwt.expire-hours:24}")
    private long expireHours;

    /** 用配置的密钥构造 HMAC 签名密钥，签发与解析共用同一把密钥保证一致性 */
    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成令牌，claims 中携带 username、role、enterpriseId、orgId、name。
     * subject 固定为登录账号；可空的业务字段只在有值时才写入 claims，避免出现 null 值声明。
     *
     * @param user 已通过密码校验的登录用户
     * @return 紧凑格式（三段式）的 JWT 字符串
     */
    public String generateToken(User user) {
        Date now = new Date();
        // 有效期按小时换算为毫秒（expireHours * 3600_000L），由 jjwt 在解析时自动校验 exp
        Date expire = new Date(now.getTime() + expireHours * 3600_000L);
        var builder = Jwts.builder()
                .subject(user.getUsername())
                .issuedAt(now)
                .expiration(expire);
        if (user.getRole() != null) {
            builder.claim("role", user.getRole());
        }
        if (user.getEnterpriseId() != null) {
            builder.claim("enterpriseId", user.getEnterpriseId());
        }
        if (user.getOrgId() != null) {
            builder.claim("orgId", user.getOrgId());
        }
        if (user.getName() != null) {
            builder.claim("name", user.getName());
        }
        // 强制改密标志：仅在为 true 时写入，旧令牌/正常账号不携带该声明，拦截器按 false 处理
        if (Boolean.TRUE.equals(user.getMustChangePassword())) {
            builder.claim("mustChangePassword", true);
        }
        return builder.signWith(key()).compact();
    }

    /**
     * 解析并验签令牌，失败抛出异常（签名不匹配、格式非法、已过期等）。
     * 调用方（AuthInterceptor）不应区分异常类型，统一按未登录处理。
     *
     * @param token 紧凑格式的 JWT
     * @return 已验签的 claims 载荷
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
