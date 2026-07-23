package com.ses.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * モバイル対応レイアウトの回帰テスト。
 *
 * <p>レスポンシブ挙動そのもの（実際の折りたたみ）はCSS/JSで動くためブラウザ検証が必要だが、
 * その土台となる「共通レイアウトが各ページに正しく差し込まれ、モバイル用のマークアップ契約が
 * 全ページに存在すること」はサーバーサイドで検証できる。各ページコントローラーはビュー名を
 * 返すだけでDBに触れないため、H2上で全ページを実レンダリングして検証する。</p>
 *
 * <p>特に、サイドバーのドロワー化ブレークポイント(992px)とハンバーガーボタンの表示条件が
 * ズレると、768〜992px幅でナビゲーションに到達できなくなる不具合が発生していた。
 * そのリグレッションを {@code d-lg-none} の存在で恒久的に防ぐ。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@WithMockUser(username = "admin", roles = "管理者")
class MobileResponsiveLayoutTest {

    @Autowired
    private MockMvc mockMvc;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.ses.service.RoleMenuService roleMenuService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        org.mockito.Mockito.when(roleMenuService.getAllMenuKeys())
            .thenReturn(java.util.List.of("engineer", "project", "customer", "proposal", "contract"));
    }

    /** 共通レイアウト(base.html)を継承する全ページ。 */
    static final String[] ALL_PAGES = {
            "/dashboard",
            "/dashboard/profit",
            "/engineer/list",
            "/customer/list",
            "/project/list",
            "/contract/list",
            "/contract/gantt",
            "/proposal/kanban",
            "/email/template/list",
            "/ai/matching"
    };

    private String render(String uri) throws Exception {
        MvcResult result = mockMvc.perform(get(uri))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private String readCss(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    @ParameterizedTest(name = "{0} はモバイル用の共通レイアウト部品を含む")
    @ValueSource(strings = {
            "/dashboard",
            "/dashboard/profit",
            "/engineer/list",
            "/customer/list",
            "/project/list",
            "/contract/list",
            "/contract/gantt",
            "/proposal/kanban",
            "/email/template/list",
            "/ai/matching"
    })
    void 全ページにモバイル用レイアウト部品が差し込まれている(String uri) throws Exception {
        String html = render(uri);

        // viewportメタタグ（レスポンシブの前提）
        assertThat(html)
                .as("%s: viewport メタタグが存在すること", uri)
                .contains("name=\"viewport\"");

        // ハンバーガーボタンと画面外クリック用の背景オーバーレイ
        assertThat(html)
                .as("%s: サイドバートグルボタンが存在すること", uri)
                .contains("id=\"sidebar-toggle-btn\"");
        assertThat(html)
                .as("%s: モバイル用の背景オーバーレイが存在すること", uri)
                .contains("id=\"sidebar-backdrop\"");
    }

    @ParameterizedTest(name = "{0} のトグルボタンは992px未満でのみ表示される(d-lg-none)")
    @ValueSource(strings = {
            "/dashboard",
            "/engineer/list",
            "/proposal/kanban",
            "/contract/gantt"
    })
    void ハンバーガーボタンはドロワー化ブレークポイントと一致する(String uri) throws Exception {
        String html = render(uri);

        // トグルボタンの直前・直後を含む断片を取り出して、そのボタンが d-lg-none を持つことを確認。
        int idx = html.indexOf("id=\"sidebar-toggle-btn\"");
        assertThat(idx).as("%s: トグルボタンが見つかること", uri).isGreaterThan(-1);
        String buttonTag = html.substring(Math.max(0, idx - 120), idx + 40);

        assertThat(buttonTag)
                .as("%s: トグルボタンは d-lg-none(=992px未満で表示) を持つこと。"
                        + "d-md-none だと768〜992px幅でボタンが消えつつサイドバーも隠れ、"
                        + "ナビゲーションに到達できなくなる。", uri)
                .contains("d-lg-none");
        assertThat(buttonTag)
                .as("%s: トグルボタンに古い d-md-none が残っていないこと", uri)
                .doesNotContain("d-md-none");
    }

    @Test
    void クイック作成ボタンのラベルは小画面で非表示にできるようspanで包まれている() throws Exception {
        String html = render("/dashboard");
        // 小画面ではラベルを隠しアイコンのみにするため、ラベルを .quick-add-label で包んでいる。
        assertThat(html)
                .as("クイック作成ボタンのラベルが quick-add-label で包まれていること")
                .contains("quick-add-label");
    }

    @Test
    void 各ページヘッダーはCSSの折り返し対象クラスを備えている() throws Exception {
        // common.css のヘッダー折り返しルールが狙うクラス組み合わせが実在すること。
        // （このクラスが変わるとレスポンシブCSSが空振りする＝サイレントな劣化を防ぐ）
        String html = render("/engineer/list");
        assertThat(html)
                .as("ページヘッダーが折り返し対象のクラス組み合わせを持つこと")
                .contains("d-flex justify-content-between align-items-center mb-4");
    }

    @Test
    void 幅広コンテンツはメイン領域を押し広げず内部でスクロールできる() throws Exception {
        String commonCss = readCss("static/css/common.css");
        String kanbanCss = readCss("static/css/kanban.css");
        String html = render("/proposal/kanban");

        assertThat(commonCss)
                .as("Flex子要素のmain-wrapperがカンバンの最小幅で押し広げられないこと")
                .containsPattern("(?s)\\.main-wrapper\\s*\\{[^}]*min-width:\\s*0");
        assertThat(kanbanCss)
                .as("カンバン全体の固定幅はスクロールコンテナ内部に保持すること")
                .containsPattern("(?s)\\.kanban-board\\s*\\{[^}]*min-width:\\s*max-content");
        assertThat(html)
                .as("Bootstrapのoverflow-autoでカンバン固有の縦横スクロール指定を上書きしないこと")
                .contains("class=\"kanban-board-container pb-3\"")
                .doesNotContain("class=\"kanban-board-container overflow-auto");
    }

    @Test
    void 小画面では見出しとフォームが読みやすいレイアウト契約を持つ() throws Exception {
        String commonCss = readCss("static/css/common.css");
        String contractHtml = render("/contract/list");

        assertThat(commonCss)
                .as("一覧画面で共通レイアウトとcontainer-fluidの余白が二重にならないこと")
                .containsPattern("(?s)\\.content-area \\.container-fluid\\.py-4\\s*\\{[^}]*padding:\\s*0");
        assertThat(commonCss)
                .as("ページ見出しの長いタイトルが小画面で折り返せること")
                .contains("overflow-wrap: anywhere");
        assertThat(commonCss)
                .as("モバイル入力欄はiOSの自動ズームを防ぐ16px以上であること")
                .containsPattern("(?s)\\.content-area \\.form-control:not\\(textarea\\)[^{]*\\{[^}]*font-size:\\s*16px");
        assertThat(commonCss)
                .as("スマートフォン幅のモーダル本文は専用の余白に縮小されること")
                .containsPattern("(?s)@media \\(max-width: 576px\\).*?\\.modal-body\\s*\\{[^}]*padding:\\s*1rem");
        assertThat(contractHtml)
                .as("契約期間の開始日・終了日はスマートフォン幅で縦積みにできること")
                .contains("mobile-date-range");
    }

    @Test
    void 通知ドロップダウンの長文は枠内で折り返せる() throws Exception {
        String commonCss = readCss("static/css/common.css");
        String commonJs = readCss("static/js/common.js");

        assertThat(commonJs)
                .as("通知項目は折り返し制御用の専用クラスを持つこと")
                .contains("notification-item")
                .contains("notification-item-message");
        assertThat(commonCss)
                .as("Bootstrap dropdown-itemのnowrapを通知項目だけ上書きすること")
                .containsPattern("(?s)#notification-list \\.notification-item\\s*\\{[^}]*white-space:\\s*normal");
        assertThat(commonCss)
                .as("契約番号などの長い連続文字列も通知枠内で折り返せること")
                .containsPattern("(?s)#notification-list \\.notification-item-message\\s*\\{[^}]*overflow-wrap:\\s*anywhere");
    }
}
