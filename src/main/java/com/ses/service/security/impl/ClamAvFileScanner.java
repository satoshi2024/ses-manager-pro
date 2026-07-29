package com.ses.service.security.impl;

import com.ses.common.enums.FileKind;
import com.ses.config.UploadProperties;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** prodでClamAV daemonのINSTREAM protocolを利用するscanner adapter。 */
@Component
@Profile("prod")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.upload", name = "scanner-enabled", havingValue = "true", matchIfMissing = true)
public class ClamAvFileScanner implements FileScanner {

    private static final byte[] INSTREAM_COMMAND = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
    private static final int CHUNK_SIZE = 8192;
    private static final int MAX_RESPONSE_BYTES = 4096;
    private static final String VERSION = "clamav-instream";

    private final UploadProperties properties;

    @Override
    public FileScanResult scan(Path quarantinedFile, FileKind kind) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(properties.getScannerHost(), properties.getScannerPort()),
                    properties.getScannerConnectTimeoutMs());
            socket.setSoTimeout(properties.getScannerReadTimeoutMs());
            try (DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                 BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                 BufferedInputStream fileInput = new BufferedInputStream(Files.newInputStream(quarantinedFile))) {
                output.write(INSTREAM_COMMAND);
                byte[] chunk = new byte[CHUNK_SIZE];
                int read;
                while ((read = fileInput.read(chunk)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    output.writeInt(read);
                    output.write(chunk, 0, read);
                }
                output.writeInt(0);
                output.flush();
                String response = readResponse(input);
                if (response.endsWith(" OK")) {
                    return FileScanResult.clean(VERSION);
                }
                if (response.endsWith(" FOUND")) {
                    return FileScanResult.infected(VERSION);
                }
                return FileScanResult.unavailable("scanner returned an unknown response");
            }
        } catch (IOException | RuntimeException e) {
            return FileScanResult.unavailable("scanner connection failed");
        }
    }

    private String readResponse(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        for (int i = 0; i < MAX_RESPONSE_BYTES; i++) {
            int value = input.read();
            if (value < 0 || value == 0 || value == '\n') {
                break;
            }
            response.write(value);
        }
        if (response.size() == MAX_RESPONSE_BYTES) {
            throw new IOException("scanner response is too long");
        }
        return response.toString(StandardCharsets.US_ASCII).trim();
    }
}
