package com.carbon.trace.controller;

import com.carbon.trace.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 文件上传接口
 *
 * 头像等图片保存到本地磁盘（carbon.upload.dir 配置的目录），
 * 数据库只存相对地址，静态资源由 WebMvcConfig 里的 /uploads/** 映射对外提供访问。
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    /** 允许的图片扩展名 */
    private static final List<String> ALLOWED_EXT = Arrays.asList("jpg", "jpeg", "png", "gif", "webp");

    /** 单张图片大小上限：5MB */
    private static final long MAX_SIZE = 5 * 1024 * 1024L;

    /** 头像存放目录（绝对路径） */
    private final Path avatarDir;

    public FileController(@Value("${carbon.upload.dir:./uploads}") String uploadDir) {
        this.avatarDir = Paths.get(uploadDir).toAbsolutePath().normalize().resolve("avatar");
    }

    /** 上传头像：返回可直接访问的图片地址 */
    @PostMapping("/avatar")
    public Map<String, Object> uploadAvatar(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请选择要上传的图片");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "图片大小不能超过 5MB");
        }

        // 校验扩展名
        String original = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "";
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                : "";
        if (!ALLOWED_EXT.contains(ext)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "仅支持 jpg / jpeg / png / gif / webp 格式的图片");
        }

        try {
            Files.createDirectories(avatarDir);
            // 用随机文件名，避免重名覆盖
            String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            file.transferTo(avatarDir.resolve(fileName).toFile());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", "/uploads/avatar/" + fileName);
            result.put("name", original);
            result.put("size", file.getSize());
            return result;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "头像保存失败：" + e.getMessage());
        }
    }
}
