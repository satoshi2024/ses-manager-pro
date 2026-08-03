package com.ses.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 200名規模の要員一覧UI契約を固定する。 */
@DisplayName("要員一覧の規模・状態契約")
class EngineerScaleUiContractTest {

    private static final Path RESOURCE_ROOT = Path.of("src/main/resources");

    @Test
    @DisplayName("待機filterは表示をlocale化し送信値をBenchにする")
    void 待機filterはBenchを送信する() throws IOException {
        String html = read("templates/engineer/list.html");

        assertTrue(html.contains("<option value=\"Bench\" th:text=\"#{enum.engineerStatus.bench}\"") );
        assertFalse(html.contains("<option value=\"待機\""));
    }

    @Test
    @DisplayName("URL statusは初回取得前に適用し旧待機をnormalizeする")
    void URL初期化はBenchと旧待機を扱う() throws IOException {
        String js = read("static/js/modules/engineer.js");

        assertTrue(js.contains("const ENGINEER_STATUS_VALUES = ['稼動中', 'Bench', '提案中', '退場予定'];"));
        assertTrue(js.contains("if (status === '待機') return 'Bench';"));
        assertTrue(js.contains("const status = normalizeEngineerListStatus(params.get('status'));"));
        assertTrue(js.indexOf("applyEngineerListQueryFilters();") < js.indexOf("loadEngineers();"),
                "初回API取得より前にURL filterを反映すること");
    }

    @Test
    @DisplayName("Dashboardの待機導線は正規値Benchを使用する")
    void dashboard導線はBenchを使用する() throws IOException {
        String html = read("templates/dashboard/index.html");
        assertTrue(html.contains("/engineer/list?status=Bench"));
        assertFalse(html.contains("/engineer/list?status=待機"));
    }

    @Test
    @DisplayName("detail初期DOMはdummyを持たずloading中はcontentとactionを隠す")
    void detail初期DOMは安全なloading状態である() throws IOException {
        String html = read("templates/engineer/detail.html");

        assertFalse(html.contains("田中 太郎"));
        assertFalse(html.contains("T.T"));
        assertTrue(html.contains("id=\"engineer-detail-container\" aria-busy=\"true\""));
        assertTrue(html.contains("id=\"engineer-detail-content\" class=\"d-none\""));
        assertTrue(html.contains("id=\"engineer-detail-error\" class=\"alert alert-warning d-none\""));
        assertTrue(html.contains("engineer-detail-action") && html.contains("disabled"));
    }

    @Test
    @DisplayName("403・404・非200・network failureは共通error rendererへ集約する")
    void detail失敗時にdummyと操作を表示しない() throws IOException {
        String js = read("static/js/modules/engineer-detail.js");

        assertTrue(occurrences(js, "renderEngineerLoadError(") >= 4,
                "missing ID・ApiResult非200・HTTP/network errorを共通化すること");
        assertTrue(js.contains("renderEngineerLoadError(res.code, res.message);"));
        assertTrue(js.contains("renderEngineerLoadError(xhr.status, message);"));
        assertTrue(js.contains("status === 403 || status === 404"));
        assertTrue(js.contains("status === 0"));
        assertTrue(js.contains("$('#engineer-detail-content').addClass('d-none');"));
        assertTrue(js.contains("$('.engineer-detail-action').prop('disabled', true);"));
        assertTrue(js.contains("currentEngineerId = null"));
    }

    @Test
    @DisplayName("detail成功後だけcontentとactionを有効化する")
    void detail成功後だけ操作を有効化する() throws IOException {
        String js = read("static/js/modules/engineer-detail.js");

        assertTrue(js.contains("$('.engineer-detail-action').prop('disabled', false);"));
        assertTrue(js.contains("$('#engineer-detail-content').removeClass('d-none');"));
        assertTrue(js.contains("$('#engineer-detail-container').attr('aria-busy', 'false');"));
        assertTrue(js.indexOf("currentEngineerId = eng.id;") > js.indexOf("function renderEngineerDetail(eng)"));
    }

    private String read(String relative) throws IOException {
        return Files.readString(RESOURCE_ROOT.resolve(relative));
    }

    private int occurrences(String text, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }
}
