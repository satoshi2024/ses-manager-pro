package com.ses.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
}
