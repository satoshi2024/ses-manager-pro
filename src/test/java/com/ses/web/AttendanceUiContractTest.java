package com.ses.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** T070の390px向けmarkup契約。実ブラウザ幅の確認はDemoで行う。 */
class AttendanceUiContractTest {

    private static final Path RESOURCE_ROOT = Path.of("src/main/resources");

    @Test
    void 本人画面は狭幅で横スクロール可能な日次表と折返しフォームを持つ() throws IOException {
        String html = read("templates/attendance/my.html");
        assertTrue(html.contains("table-responsive"));
        assertTrue(html.contains("d-flex flex-wrap"));
        assertTrue(html.contains("attendance-my.js"));
    }

    @Test
    void 管理画面は権限フラグ付きのresponsive表を持つ() throws IOException {
        String html = read("templates/attendance/management.html");
        assertTrue(html.contains("table-responsive"));
        assertTrue(html.contains("attendanceRoleFlags"));
        assertTrue(html.contains("attendance-management.js"));
    }

    private String read(String relative) throws IOException {
        return Files.readString(RESOURCE_ROOT.resolve(relative));
    }
}
