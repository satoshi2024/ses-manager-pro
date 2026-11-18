package com.ses.controller.page;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * NF-06 取込センター画面（W3-12）を実security chainで確認する（NF-06 ON の test profile）。
 *
 * <p>画面は view 名だけを返し、データは既存の {@code /api/import-center/**} からJSで読む。到達できるのは管理者だけ
 * （{@code SecurityConfig} の静的限定と {@code MenuPermissionFilter}）。共有H2を書き換えないため
 * {@code @Transactional} で各testをrollbackする（本testは読取りだけ）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ImportCenterPageControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @WithMockUser(username = "nf06-page-admin", roles = "管理者")
    void 管理者は取込センター画面とサイドバーの導線を表示できる() throws Exception {
        String html = mockMvc.perform(get("/import-center"))
                .andExpect(status().isOk())
                .andExpect(view().name("import-center/index"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("id=\"importCenterApp\"", "/js/modules/import-center.js",
                // 一覧画面と同じ構造（card > card-body > form#searchForm）で、スマホでは絞込みpanelが折りたためる
                "id=\"searchForm\"",
                // V193相当のH2 seedで menu import-center が入り、サイドバーへ導線が出る
                "href=\"/import-center\"");
        assertThat(html).contains("取込センター");
        assertThat(html).as("message keyの未登録（raw keyの表示）が無い").doesNotContain("importCenter.", "menu.importCenter");
        assertThat(html).as("apply系の入口を画面に置かない").doesNotContain("/apply", "/restart", "/rollback");
    }

    @Test
    void 管理者以外のroleは画面にもAPIにも到達できない() throws Exception {
        for (String role : List.of("営業", "HR", "マネージャー", "要員")) {
            mockMvc.perform(get("/import-center").with(user("nf06-page-" + role).roles(role)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/import-center/sources").with(user("nf06-page-" + role).roles(role)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void 未認証の画面要求はloginへ誘導される() throws Exception {
        // ブラウザの画面遷移（Accept: text/html）はform loginへ誘導する（Acceptの無い要求は既定の401 entry pointになる）。
        String location = mockMvc.perform(get("/import-center").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).contains("/login");
    }

    @Test
    void menuのseedは管理者だけへ付与されapi_prefixは公開routeを指す() {
        assertThat(jdbc.queryForList("SELECT CONCAT(path_prefix, '|', api_prefix) FROM m_menu "
                + "WHERE menu_key = 'import-center'", String.class))
                .containsExactly("/import-center|/api/import-center/sources");
        assertThat(jdbc.queryForList("SELECT r.role FROM t_role_menu r JOIN m_menu m ON m.id = r.menu_id "
                + "WHERE m.menu_key = 'import-center'", String.class)).containsExactly("管理者");
    }
}
