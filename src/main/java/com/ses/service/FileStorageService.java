package com.ses.service;

import com.ses.common.enums.FileKind;
import com.ses.dto.file.StoredFile;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * ファイル保存サービス。
 */
public interface FileStorageService {

    /**
     * ファイルを検証して保存する。拡張子・サイズ・Content-Typeが不正な場合は BusinessException。
     */
    StoredFile store(MultipartFile file, FileKind kind);

    /**
     * 保存済みファイルを読み込む。パストラバーサルは拒否する。
     */
    Resource load(String storedName);
}
