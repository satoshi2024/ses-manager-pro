package com.ses.service.security.impl;

import com.ses.common.enums.FileKind;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 開発・test用scanner fake。
 * EICAR fixtureを検知し、読めない場合はfail-closedでUNAVAILABLEを返す。
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(prefix = "app.upload", name = "scanner-enabled", havingValue = "true", matchIfMissing = true)
public class LocalSignatureFileScanner implements FileScanner {

    private static final String VERSION = "test-signature-1";
    private static final String EICAR_MARKER = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    @Override
    public FileScanResult scan(Path quarantinedFile, FileKind kind) {
        try {
            byte[] data = Files.readAllBytes(quarantinedFile);
            String content = new String(data, StandardCharsets.US_ASCII);
            if (content.contains(EICAR_MARKER)) {
                return FileScanResult.infected(VERSION);
            }
            return FileScanResult.clean(VERSION);
        } catch (IOException e) {
            return FileScanResult.unavailable("scanner read failed");
        } catch (RuntimeException e) {
            return FileScanResult.unavailable("scanner failed");
        }
    }
}
