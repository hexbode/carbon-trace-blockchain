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
 * JWT 工具：签发与解析令牌
 */
@Component
public class JwtUtil {

    @Value("${carbon.jwt.secret}")
    private String secret;

    @Value("${carbon.jwt.expire-hours:24}")
    private long expireHours;

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
