package com.carbon.trace.config;

import com.carbon.trace.common.AuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Web 配置：注册鉴权拦截器 + 全局 CORS + 上传文件的静态资源映射。
 *
 * 依赖：{@link AuthInterceptor}（唯一注入的组件；其余配置均来自 carbon.* 配置项）。
 * 安全边界（三处配置都直接决定攻击面，改动需评审）：
 *   1. 免登录白名单只包含登录接口、公共查询端和公众核验接口，新增接口默认受鉴权保护；
 *   2. CORS 默认关闭（同源部署），仅在显式配置白名单时放行，且 allowCredentials=false，
 *      登录态走 Authorization 头而非 Cookie，避免“任意源 + 携带凭据”的跨站风险；
 *   3. /uploads/** 被直接映射为静态资源，不经过鉴权拦截器，任何拿到 URL 的人都能访问，
 *      其安全性依赖 SecurityHeaderFilter 的响应头与 FileController 的写入校验。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    /** 上传文件根目录（与 FileController 使用同一配置项） */
    @Value("${carbon.upload.dir:./uploads}")
    private String uploadDir;

    /** 允许跨域访问的来源白名单（逗号分隔）；留空表示仅允许同源访问 */
    @Value("${carbon.cors.allowed-origins:}")
    private String corsAllowedOrigins;

    /** 构造注入鉴权拦截器；本配置类仅持有该组件，不做额外状态维护 */
    public WebMvcConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    /**
     * 注册鉴权拦截器并配置放行白名单：登录、公共查询端与公众免登录的认证查询/校验接口不拦截。
     *
     * 拦截范围是 /api/**：非该前缀的请求（静态资源、错误页等）不会被鉴权。
     * 白名单按“前缀或精确路径”匹配，顺序无关；新增免登录接口必须在此登记，
     * 反之，任何落在 /api/public/** 下的新接口都会自动绕过登录校验，新增时需确认其确实可公开。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 登录接口、公共查询端，以及公众免登录的认证查询/校验接口无需鉴权
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/public/**",
                        "/api/certifications/by-fingerprint/**",
                        "/api/certifications/verify"
                );
    }

    /**
     * 跨域配置：默认不做任何放行（前后端由 nginx / Vite 代理同域访问，同源请求不需要 CORS）。
     * 仅在显式配置 carbon.cors.allowed-origins 时按白名单放行，且不允许携带 Cookie 凭据，
     * 避免“任意来源 + 携带凭据”组合带来的跨站请求风险。登录态通过 Authorization 头传递。
     *
     * 配置为空或全是空白项时直接返回，等价于“不注册任何 CORS 规则”，浏览器只允许同源访问。
     * 白名单按逗号切分并逐项 trim，避免形如 "a, b" 的配置带上空格导致匹配失败。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (!StringUtils.hasText(corsAllowedOrigins)) {
            return;
        }
        String[] origins = Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toArray(String[]::new);
        if (origins.length == 0) {
            return;
        }
        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }

    /**
     * 上传文件（头像等）的访问映射：/uploads/** → 本地磁盘目录。
     *
     * 注意：该路径不受 AuthInterceptor 保护，只要知道文件名即可访问，因此上传内容必须是
     * 服务端重命名、经过类型校验的静态资源（见 FileController），不可在此映射业务敏感文件。
     * 目录先转绝对路径再归一化，防止配置中使用 .. 造成映射到非预期目录。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Paths.get(uploadDir).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}
