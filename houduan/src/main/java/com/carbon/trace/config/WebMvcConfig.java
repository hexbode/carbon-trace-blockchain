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
 * Web 配置：注册鉴权拦截器 + 全局 CORS + 上传文件的静态资源映射
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

    public WebMvcConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    /**
     * 注册鉴权拦截器并配置放行白名单：登录、公共查询端与公众免登录的认证查询/校验接口不拦截。
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

    /** 上传文件（头像等）的访问映射：/uploads/** → 本地磁盘目录 */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Paths.get(uploadDir).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}
