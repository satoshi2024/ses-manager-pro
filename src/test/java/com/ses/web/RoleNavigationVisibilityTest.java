package com.ses.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("5ロールナビゲーション可視性契約")
class RoleNavigationVisibilityTest {

    private static final Path RESOURCE_ROOT = Path.of("src/main/resources");

    @Test
    @DisplayName("マイ勤怠メニューは要員ロールのみに表示制御される")
    void myTimesheetMenuIsRestrictedToMemberRole() throws IOException {
        String sidebarHtml = read("templates/layout/sidebar.html");

        assertTrue(sidebarHtml.contains("sec:authorize=\"hasRole('要員')\""));
        assertTrue(sidebarHtml.contains("allowedMenus.contains('my-timesheet')"));
    }

    @Test
    @DisplayName("横断検索ボタンとモーダルは要員ロールへ表示しない")
    void globalSearchIsHiddenFromMemberRole() throws IOException {
        String headerHtml = read("templates/layout/header.html");
        String baseHtml = read("templates/layout/base.html");
        String js = read("static/js/common.js");

        assertTrue(headerHtml.contains("sec:authorize=\"!hasRole('要員')\""));
        assertTrue(headerHtml.contains("id=\"global-search-btn\""));

        assertTrue(baseHtml.contains("sec:authorize=\"!hasRole('要員')\""));
        assertTrue(baseHtml.contains("id=\"globalSearchModal\""));

        assertTrue(js.contains("if (!$input.length || !$('#global-search-btn').length) return;"));
    }

    private String read(String relative) throws IOException {
        return Files.readString(RESOURCE_ROOT.resolve(relative));
    }
}
