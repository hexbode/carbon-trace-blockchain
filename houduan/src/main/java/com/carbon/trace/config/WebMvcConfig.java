package com.carbon.trace.config;

import com.carbon.trace.common.AuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * Web 配置：注册鉴权拦截器 + 全局 CORS + 上传文件的静态资源映射
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    /** 上传文件根目录（与 FileController 使用同一配置项） */
    @Value("${carbon.upload.dir:./uploads}")
    private String uploadDir;

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
     * 全局 CORS 放行：允许任意来源（携带凭据），支持 GET/POST/PUT/DELETE/OPTIONS，预检缓存有效期 1 小时。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /** 上传文件（头像等）的访问映射：/uploads/** → 本地磁盘目录 */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Paths.get(uploadDir).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}
