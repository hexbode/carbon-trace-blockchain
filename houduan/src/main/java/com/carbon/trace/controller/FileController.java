package com.carbon.trace.controller;

import com.carbon.trace.common.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 文件上传接口
 *
 * 头像等图片保存到本地磁盘（carbon.upload.dir 配置的目录），
 * 数据库只存相对地址，静态资源由 WebMvcConfig 里的 /uploads/** 映射对外提供访问。
 *
 * 安全校验（均不信任客户端提交的扩展名与 Content-Type）：
 *   1. 大小上限 5MB；
 *   2. 按文件头（magic bytes）识别真实图片类型，非 jpg/png/gif/webp 一律拒绝；
 *   3. 客户端扩展名必须与文件头识别的类型一致，防止把 HTML/脚本改名成 .jpg；
 *   4. 落盘文件名使用服务端生成的 UUID + 真实类型扩展名；
 *   5. 落盘前对目标路径归一化并校验仍位于头像目录内，防止路径穿越；
 *   6. 原始文件名去路径、去控制字符后仅用于回显，且限制长度；
 *   7. 写入失败只记录日志并返回通用提示，不向前端泄露服务器路径。
 */
@Slf4j
@RestController
@RequestMapping("/api/files")
public class FileController {

    /** 单张图片大小上限：5MB */
    private static final long MAX_SIZE = 5 * 1024 * 1024L;

    /** 回显给前端的原始文件名最大长度 */
    private static final int MAX_NAME_LENGTH = 100;

    /** 头像存放目录（绝对路径） */
    private final Path avatarDir;

    public FileController(@Value("${carbon.upload.dir:./uploads}") String uploadDir) {
        this.avatarDir = Paths.get(uploadDir).toAbsolutePath().normalize().resolve("avatar");
    }

    /**
     * 上传头像：保存到本地磁盘 avatar 子目录，返回可直接通过 /uploads/avatar/** 访问的相对地址。
     * 文件为空、超过 5MB、内容不是受支持的图片、扩展名与内容不一致均返回 400；写入失败返回 500。
     */
    @PostMapping("/avatar")
    public Map<String, Object> uploadAvatar(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请选择要上传的图片");
        }
        // 读取内容（多读 1 字节用于判断是否超限），后续校验与落盘都基于这份内存数据
        byte[] content = readContent(file);
        if (content.length > MAX_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "图片大小不能超过 5MB");
        }

        // 以文件头识别真实类型，不信任客户端的扩展名与 Content-Type
        String realExt = detectImageType(content);
        if (realExt == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "文件内容不是有效的图片，仅支持 jpg / jpeg / png / gif / webp 格式");
        }

        String original = sanitizeFileName(file.getOriginalFilename());
        String claimedExt = extensionOf(original);
        if (!claimedExt.isEmpty() && !isSameType(claimedExt, realExt)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "图片扩展名与文件内容不一致，请重新选择图片");
        }

        // 文件名为服务端生成的 UUID + 真实类型扩展名，本身不含路径分隔符；仍做归一化校验作为纵深防御
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + realExt;
        Path target = avatarDir.resolve(fileName).normalize();
        if (!target.startsWith(avatarDir)) {
            log.warn("检测到非法的头像落盘路径：{}", target);
            throw new ApiException(HttpStatus.BAD_REQUEST, "文件名不合法");
        }

        try {
            Files.createDirectories(avatarDir);
            // CREATE_NEW：目标文件已存在时失败，避免任何形式的覆盖写入
            Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            // 完整堆栈进日志，前端仅拿到通用提示，避免泄露服务器目录结构
            log.error("头像保存失败，目录：{}", avatarDir, e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "头像保存失败，请稍后重试");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", "/uploads/avatar/" + fileName);
        result.put("name", original);
        result.put("size", content.length);
        return result;
    }

    /** 读取上传内容，最多读取 MAX_SIZE + 1 字节，用于判断是否超限 */
    private byte[] readContent(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes((int) MAX_SIZE + 1);
        } catch (IOException e) {
            log.error("读取上传图片失败", e);
            throw new ApiException(HttpStatus.BAD_REQUEST, "图片读取失败，请重新上传");
        }
    }

    /**
     * 按文件头（magic bytes）识别图片真实类型，返回规范扩展名；无法识别返回 null。
     * JPEG: FF D8 FF；PNG: 89 50 4E 47 0D 0A 1A 0A；GIF: GIF87a / GIF89a；WebP: RIFF....WEBP
     */
    private static String detectImageType(byte[] b) {
        if (b.length >= 3
                && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (b.length >= 8
                && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A
                && (b[6] & 0xFF) == 0x1A && (b[7] & 0xFF) == 0x0A) {
            return "png";
        }
        if (b.length >= 6
                && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8'
                && (b[4] == '7' || b[4] == '9') && b[5] == 'a') {
            return "gif";
        }
        if (b.length >= 12
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }

    /** 扩展名与真实类型是否匹配（jpeg / jpg 视为同一类型） */
    private static boolean isSameType(String claimedExt, String realExt) {
        if ("jpg".equals(realExt)) {
            return "jpg".equals(claimedExt) || "jpeg".equals(claimedExt);
        }
        return realExt.equals(claimedExt);
    }

    /** 取小写扩展名，无扩展名返回空串 */
    private static String extensionOf(String name) {
        if (name == null || !name.contains(".")) {
            return "";
        }
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 清洗原始文件名：只保留文件名部分（去掉路径）、剔除控制字符并限长。
     * 该值仅用于前端回显，不参与任何路径拼接。
     */
    private static String sanitizeFileName(String name) {
        if (name == null) {
            return "";
        }
        String base = name;
        int separator = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (separator >= 0) {
            base = base.substring(separator + 1);
        }
        base = base.replaceAll("\\p{Cntrl}", "").trim();
        if (base.length() > MAX_NAME_LENGTH) {
            base = base.substring(0, MAX_NAME_LENGTH);
        }
        return base;
    }
}
