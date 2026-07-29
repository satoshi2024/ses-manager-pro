package com.ses.service.security;

import com.ses.common.enums.FileKind;

import java.nio.file.Path;

/** quarantine上のファイルを検査する抽象化。scanner異常はUNAVAILABLEで返す。 */
public interface FileScanner {

    FileScanResult scan(Path quarantinedFile, FileKind kind);
}
