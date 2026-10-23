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

    /** 生成令牌，claims 中携带 username、role、enterpriseId、orgId、name */
    public String generateToken(User user) {
        Date now = new Date();
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
        return builder.signWith(key()).compact();
    }

    /** 解析令牌，失败抛出异常 */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
