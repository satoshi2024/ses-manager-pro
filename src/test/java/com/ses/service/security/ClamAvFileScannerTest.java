package com.ses.service.security;

import com.ses.common.enums.FileKind;
import com.ses.config.UploadProperties;
import com.ses.service.security.impl.ClamAvFileScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClamAvFileScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void clean応答を公開可能として返す() throws Exception {
        assertEquals(FileScanResult.Status.CLEAN, scanWithResponse("stream: OK\0").status());
    }

    @Test
    void found応答を感染として返す() throws Exception {
        assertEquals(FileScanResult.Status.INFECTED,
                scanWithResponse("stream: Win.Test.EICAR_HDB-1 FOUND\0").status());
    }

    @Test
    void scannerへ接続できない場合はunavailableを返す() throws Exception {
        int unusedPort;
        try (ServerSocket server = new ServerSocket(0)) {
            unusedPort = server.getLocalPort();
        }
        UploadProperties properties = properties(unusedPort);
        Path file = Files.writeString(tempDir.resolve("sample.pdf"), "sample");

        assertEquals(FileScanResult.Status.UNAVAILABLE,
                new ClamAvFileScanner(properties).scan(file, FileKind.SKILL_SHEET).status());
    }

    private FileScanResult scanWithResponse(String response) throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> daemon = CompletableFuture.runAsync(() -> respond(server, response));
            Path file = Files.writeString(tempDir.resolve("sample-" + server.getLocalPort() + ".pdf"), "sample");
            FileScanResult result = new ClamAvFileScanner(properties(server.getLocalPort()))
                    .scan(file, FileKind.SKILL_SHEET);
            daemon.get(3, TimeUnit.SECONDS);
            return result;
        }
    }

    private UploadProperties properties(int port) {
        UploadProperties properties = new UploadProperties();
        properties.setScannerHost("127.0.0.1");
        properties.setScannerPort(port);
        properties.setScannerConnectTimeoutMs(1000);
        properties.setScannerReadTimeoutMs(1000);
        return properties;
    }

    private void respond(ServerSocket server, String response) {
        try (Socket socket = server.accept(); DataInputStream input = new DataInputStream(socket.getInputStream())) {
            input.readNBytes(10);
            int length;
            while ((length = input.readInt()) > 0) {
                input.readNBytes(length);
            }
            socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
