package com.ses.controller.api;

import com.ses.common.enums.FileKind;
import com.ses.common.result.ApiResult;
import com.ses.dto.file.StoredFile;
import com.ses.service.FileStorageService;
import com.ses.service.security.impl.FileScopeValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLConnection;

/**
 * ファイルアップロード・ダウンロードAPI。
 * /api/files はどのロールも利用する共通機能のため、m_menu には登録しない
 * （MenuPermissionFilterは m_menu に一致しないパスを許可する）。
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileApiController {

    private final FileStorageService fileStorageService;
    private final FileScopeValidationService fileScopeValidationService;

    /**
     * ファイルアップロード。
     * @param kind SKILL_SHEET / PHOTO
     */
    @PostMapping
    public ApiResult<StoredFile> upload(@RequestParam("file") MultipartFile file,
                                        @RequestParam("kind") FileKind kind) {
        return ApiResult.success(fileStorageService.store(file, kind));
    }

    /**
     * ファイルダウンロード（認証済みユーザーのみ。静的公開はしない）。
     */
    @GetMapping("/{storedName}")
    public ResponseEntity<Resource> download(@PathVariable String storedName) {
        // A8-04: ファイルダウンロードのスコープ検証
        fileScopeValidationService.assertDownloadAllowed(storedName);

        Resource resource = fileStorageService.load(storedName);
        String contentType = URLConnection.guessContentTypeFromName(storedName);
        MediaType mediaType = contentType != null
                ? MediaType.parseMediaType(contentType)
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + storedName + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(resource);
    }
}
