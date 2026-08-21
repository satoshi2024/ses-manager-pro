package com.ses.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TS-01: マイ勤怠は裸 fetch/alert を使わず SES.api + Toast/Swal に統一する。
 * 日次6列圧縮レイアウトと table-responsive も静的に固定する。
 */
@DisplayName("TS-01 my-timesheet.js UI contract")
class MyTimesheetJsContractTest {

    private static final Path MY_TIMESHEET_JS =
            Path.of("src/main/resources/static/js/modules/my-timesheet.js");

    @Test
    @DisplayName("裸の fetch/alert/confirm を使わず SES.api と Swal/Toast を使う")
    void usesSesApiWithoutBareFetchOrAlert() throws Exception {
        String js = Files.readString(MY_TIMESHEET_JS, StandardCharsets.UTF_8);

        assertFalse(js.contains("fetch("), "裸の fetch( を使わないこと");
        assertFalse(js.contains("alert("), "alert( を使わないこと");
        assertFalse(js.matches("(?s).*\\bconfirm\\s*\\(.*"), "window.confirm を使わないこと");

        assertTrue(js.contains("SES.api.get"), "一覧取得は SES.api.get");
        assertTrue(js.contains("SES.api.post"), "保存/提出は SES.api.post");
        assertTrue(js.contains("SES.api.delete"), "日次削除は SES.api.delete");
        assertTrue(js.contains("SES.toast."), "成功/検証エラーは Toast");
        assertTrue(js.contains("Swal.fire"), "確認は Swal");
    }

    @Test
    @DisplayName("日次明細は table-responsive で包み、入力は6列圧縮レイアウトを維持")
    void keepsDailyTableResponsiveAndSixColumnForm() throws Exception {
        String js = Files.readString(MY_TIMESHEET_JS, StandardCharsets.UTF_8);

        assertTrue(js.contains("table-responsive"), "日次表を table-responsive で包むこと");
        assertTrue(js.contains("col-12 col-md"), "日付列の圧縮クラスを維持");
        assertTrue(js.contains("col-6 col-md"), "開始/終了等の圧縮クラスを維持");
        assertTrue(js.contains("col-12 col-md-auto"), "追加ボタン列を維持");
        assertFalse(js.contains("row g-1"), "旧 g-1 一列レイアウトへ戻さない");
    }
}
