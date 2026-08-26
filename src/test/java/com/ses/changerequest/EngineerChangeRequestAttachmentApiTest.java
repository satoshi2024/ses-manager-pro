package com.ses.changerequest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.MenuCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R2-P1-01 一気通貫検証: 変更申請添付の upload→申請関連付け→CLEAN→本人download→監査ログ。
 * - 本人download 200、他要員download 404（IDOR規約）、未scan download 403（fail-closed）
 * - 営業は管理側download 403、管理者は管理側download 200
 * - ApiAuditFilterが /api/{my,engineer-}change-requests/{id}/attachment を監査ログへ記録する
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EngineerChangeRequestAttachmentApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private EngineerMapper engineerMapper;
    @Autowired
    private EngineerAccountLinkMapper accountLinkMapper;
    @Autowired
    private MenuCacheService menuCacheService;

    @BeforeEach
    void restoreSelfServiceMenus() {
        // 共有 H2 + 乱数順で engineer-schema 等がメニューを削っても /api/my と管理APIが届くようにする
        Integer myCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM m_menu WHERE menu_key = 'my-timesheet'", Integer.class);
        if (myCount == null || myCount == 0) {
            jdbcTemplate.update(
                    "INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order) "
                            + "VALUES ('my-timesheet', 'my-timesheet', '/my', '/api/my', 92)");
        }
        Integer mgmtCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM m_menu WHERE menu_key = 'engineerChangeRequests'", Integer.class);
        if (mgmtCount == null || mgmtCount == 0) {
            jdbcTemplate.update(
                    "INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order) "
                            + "VALUES ('engineerChangeRequests', 'engineer-change-requests', "
                            + "'/engineer-change-requests', '/api/engineer-change-requests', 99)");
        }
        Long myMenuId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_menu WHERE menu_key = 'my-timesheet'", Long.class);
        Long mgmtMenuId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_menu WHERE menu_key = 'engineerChangeRequests'", Long.class);
        Integer link = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_role_menu WHERE role = '要員' AND menu_id = ?", Integer.class, myMenuId);
        if (link == null || link == 0) {
            jdbcTemplate.update("INSERT INTO t_role_menu (role, menu_id) VALUES ('要員', ?)", myMenuId);
        }
        for (String role : new String[] {"管理者", "HR", "マネージャー"}) {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_role_menu WHERE role = ? AND menu_id = ?",
                    Integer.class, role, mgmtMenuId);
            if (n == null || n == 0) {
                jdbcTemplate.update("INSERT INTO t_role_menu (role, menu_id) VALUES (?, ?)", role, mgmtMenuId);
            }
        }
        menuCacheService.invalidate();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 本人の添付はuploadから申請関連付けdownload監査ログまで一気通貫で動く() throws Exception {
        long userIdA = insertUser("要員");
        long engineerIdA = createEngineer();
        link(engineerIdA, userIdA);
        RequestPostProcessor userA = engineerUser(userIdA);

        // 1. upload → documentId
        MvcResult uploadResult = mockMvc.perform(multipart("/api/my/change-requests/attachment")
                        .file(new MockMultipartFile("file", "proof.pdf", "application/pdf",
                                "%PDF-1.4 attachment A".getBytes(StandardCharsets.UTF_8)))
                        .with(userA).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.documentId").isNumber())
                .andReturn();
        long documentId = readLong(uploadResult, "/data/documentId");

        // 2. 申請作成（attachmentDocumentIdを関連付け）
        MvcResult createResult = mockMvc.perform(post("/api/my/change-requests")
                        .with(userA).with(csrf())
                        .contentType("application/json")
                        .content("{\"requestType\":\"profile.change\","
                                + "\"payload\":{\"nearestStation\":\"新駅\"},"
                                + "\"reason\":\"住所変更に伴う最寄駅の更新\","
                                + "\"attachmentDocumentId\":" + documentId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.attachmentDocumentId").value(documentId))
                .andReturn();
        long requestId = readLong(createResult, "/data/id");

        // 3. 本人download → 200・内容一致
        mockMvc.perform(get("/api/my/change-requests/" + requestId + "/attachment")
                        .with(userA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("%PDF-1.4 attachment A")));

        // 4. 監査ログ記録（ApiAuditFilter: GET download → FILE_DOWNLOAD）
        Integer auditRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_audit_log WHERE method='GET' "
                        + "AND uri='/api/my/change-requests/" + requestId + "/attachment' AND success_flag=1",
                Integer.class);
        assertTrue(auditRows != null && auditRows >= 1,
                "本人の添付downloadがt_audit_logへ記録されること（R2-P1-01）");
    }

    @Test
    void 他要員は本人の添付をダウンロードできない() throws Exception {
        long userA = insertUser("要員");
        long engineerA = createEngineer();
        link(engineerA, userA);
        long userB = insertUser("要員");
        long engineerB = createEngineer();
        link(engineerB, userB);

        MvcResult uploadResult = mockMvc.perform(multipart("/api/my/change-requests/attachment")
                        .file(new MockMultipartFile("file", "proof.pdf", "application/pdf",
                                "%PDF-1.4 attachment A".getBytes(StandardCharsets.UTF_8)))
                        .with(engineerUser(userA)).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        long documentId = readLong(uploadResult, "/data/documentId");

        MvcResult createResult = mockMvc.perform(post("/api/my/change-requests")
                        .with(engineerUser(userA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"requestType\":\"profile.change\","
                                + "\"payload\":{\"nearestStation\":\"A駅\"},"
                                + "\"attachmentDocumentId\":" + documentId + "}"))
                .andExpect(status().isOk())
                .andReturn();
        long requestId = readLong(createResult, "/data/id");

        // 他要員（B）は404（IDOR規約。error.changeRequest.notFound）
        mockMvc.perform(get("/api/my/change-requests/" + requestId + "/attachment")
                        .with(engineerUser(userB)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 未scanの添付はダウンロードできない() throws Exception {
        long userA = insertUser("要員");
        long engineerA = createEngineer();
        link(engineerA, userA);

        MvcResult uploadResult = mockMvc.perform(multipart("/api/my/change-requests/attachment")
                        .file(new MockMultipartFile("file", "bad.pdf", "application/pdf",
                                "%PDF-1.4 infected".getBytes(StandardCharsets.UTF_8)))
                        .with(engineerUser(userA)).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        long documentId = readLong(uploadResult, "/data/documentId");

        // scan_statusを未scan（INFECTED）へ書き換えてfail-closedを検証
        jdbcTemplate.update("UPDATE t_document_version SET scan_status='INFECTED' WHERE document_id=?", documentId);

        MvcResult createResult = mockMvc.perform(post("/api/my/change-requests")
                        .with(engineerUser(userA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"requestType\":\"profile.change\","
                                + "\"payload\":{\"nearestStation\":\"C駅\"},"
                                + "\"attachmentDocumentId\":" + documentId + "}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        // validateAttachmentが未scanを拒否するため申請自体が400（error.document.notClean）
        assertEquals(400, createResult.getResponse().getStatus());

        // 直接DBへ下書きを作ってでもdownloadは403を返すことを確認するため、
        // 正常CLEANで下書きを作り直した後にscanを汚染して403を確認する
        MvcResult upload2 = mockMvc.perform(multipart("/api/my/change-requests/attachment")
                        .file(new MockMultipartFile("file", "good.pdf", "application/pdf",
                                "%PDF-1.4 ok".getBytes(StandardCharsets.UTF_8)))
                        .with(engineerUser(userA)).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        long documentId2 = readLong(upload2, "/data/documentId");
        MvcResult create2 = mockMvc.perform(post("/api/my/change-requests")
                        .with(engineerUser(userA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"requestType\":\"profile.change\","
                                + "\"payload\":{\"nearestStation\":\"D駅\"},"
                                + "\"attachmentDocumentId\":" + documentId2 + "}"))
                .andExpect(status().isOk())
                .andReturn();
        long requestId2 = readLong(create2, "/data/id");
        jdbcTemplate.update("UPDATE t_document_version SET scan_status='PENDING' WHERE document_id=?", documentId2);

        mockMvc.perform(get("/api/my/change-requests/" + requestId2 + "/attachment")
                        .with(engineerUser(userA)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 管理側は営業403管理者は管理一覧経由でダウンロードできる() throws Exception {
        long userA = insertUser("要員");
        long engineerA = createEngineer();
        link(engineerA, userA);

        MvcResult uploadResult = mockMvc.perform(multipart("/api/my/change-requests/attachment")
                        .file(new MockMultipartFile("file", "proof.pdf", "application/pdf",
                                "%PDF-1.4 mgmt".getBytes(StandardCharsets.UTF_8)))
                        .with(engineerUser(userA)).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        long documentId = readLong(uploadResult, "/data/documentId");

        MvcResult createResult = mockMvc.perform(post("/api/my/change-requests")
                        .with(engineerUser(userA)).with(csrf())
                        .contentType("application/json")
                        .content("{\"requestType\":\"profile.change\","
                                + "\"payload\":{\"nearestStation\":\"E駅\"},"
                                + "\"attachmentDocumentId\":" + documentId + "}"))
                .andExpect(status().isOk())
                .andReturn();
        long requestId = readLong(createResult, "/data/id");

        // 営業は管理APIへ到達できない（@PreAuthorize hasAnyRole('管理者','HR','マネージャー')）
        mockMvc.perform(get("/api/engineer-change-requests/" + requestId + "/attachment")
                        .with(user(String.valueOf(insertUser("営業"))).roles("営業")))
                .andExpect(status().isForbidden());

        // 管理者は管理側download 200
        mockMvc.perform(get("/api/engineer-change-requests/" + requestId + "/attachment")
                        .with(user(String.valueOf(insertUser("管理者"))).roles("管理者")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("%PDF-1.4 mgmt")));
    }

    // ----------------------------------------------------------------
    // ヘルパー
    // ----------------------------------------------------------------

    private RequestPostProcessor engineerUser(long userId) {
        return user(String.valueOf(userId)).roles("要員");
    }

    private long readLong(MvcResult result, String jsonPointer) throws Exception {
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode node = root.at(jsonPointer);
        if (node.isMissingNode() || node.isNull()) {
            throw new AssertionError("JSON path not found: " + jsonPointer + " body="
                    + result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        }
        return node.asLong();
    }

    long insertUser(String role) {
        // H2のsys_user.role ENUMはV32未適用のため'要員'を持たない。DBは'管理者'で保存し、
        // 実際のロールはMockMvcのwith(user(...)).roles(...)で表現する（既存テストと同じ規約）。
        SysUser user = SysUser.builder()
                .username("cr-api-" + role + "-" + System.nanoTime())
                .password("x")
                .realName("添付APIテスト")
                .role("要員".equals(role) ? "管理者" : role)
                .status(1)
                .build();
        sysUserMapper.insert(user);
        return user.getId();
    }

    long createEngineer() {
        Engineer engineer = Engineer.builder()
                .fullName("添付API要員-" + System.nanoTime())
                .employmentType("正社員")
                .status("Bench")
                .nearestStation("旧駅")
                .build();
        engineerMapper.insert(engineer);
        jdbcTemplate.update("DELETE FROM t_engineer_accounting_history WHERE engineer_id = ?", engineer.getId());
        return engineer.getId();
    }

    void link(Long engineerId, Long sysUserId) {
        accountLinkMapper.delete(new LambdaQueryWrapper<EngineerAccountLink>()
                .eq(EngineerAccountLink::getEngineerId, engineerId));
        accountLinkMapper.delete(new LambdaQueryWrapper<EngineerAccountLink>()
                .eq(EngineerAccountLink::getSysUserId, sysUserId));
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(engineerId);
        link.setSysUserId(sysUserId);
        accountLinkMapper.insert(link);
    }
}