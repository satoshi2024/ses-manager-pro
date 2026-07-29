package com.ses.service.security.impl;

import com.ses.common.enums.FileKind;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;

import java.nio.file.Path;

/** scanner beanが無効・未配線の場合に安全側へ倒すfallback。 */
public class UnavailableFileScanner implements FileScanner {

    @Override
    public FileScanResult scan(Path quarantinedFile, FileKind kind) {
        return FileScanResult.unavailable("scanner is not configured");
    }
}
