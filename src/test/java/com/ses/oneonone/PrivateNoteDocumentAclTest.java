package com.ses.oneonone;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.document.DocumentDetailDTO;
import com.ses.entity.DocumentVersion;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.PermissionGroup;
import com.ses.entity.SysUser;
import com.ses.entity.UserPermissionGroup;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.PermissionGroupActionMapper;
import com.ses.mapper.PermissionGroupMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserPermissionGroupMapper;
import com.ses.service.DocumentService;
import com.ses.service.security.impl.FileScopeValidationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1-P1-07: PRIVATE_NOTE（1on1 confidential相談）の文書台帳ACL。
 * HR・指定管理者（one-on-one.confidential権限グループ割当）のみ detail/download 可。
 * 一般管理者・営業・マネージャー・要員は detail 403 / download 403（fail-closed）。
 * document archiveのlist/detail/countがSQL母集団でPRIVATE_NOTEを除外することも検証する。
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class PrivateNoteDocumentAclTest {

    private static final String CONFIDENTIAL_ACTION = "one-on-one.confidential";

    @Autowired
    private DocumentService documentService;
    @Autowired
    private FileScopeValidationService fileScopeValidationService;
    @Autowired
    private DocumentVersionMapper documentVersionMapper;
    @Autowired
    private PermissionGroupMapper permissionGroupMapper;
    @Autowired
    private PermissionGroupActionMapper permissionGroupActionMapper;
    @Autowired
    private UserPermissionGroupMapper userPermissionGroupMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private EngineerMapper engineerMapper;
    @Autowired
    private EngineerAccountLinkMapper accountLinkMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** user id → 認証ロール（H2のsys_user.role ENUMはV32未適用のためDBは'管理者'保存、認証はコンテキストで表現） */
    private final java.util.Map<Long, String> userRoles = new java.util.HashMap<>();

    private Long privateNoteDocumentId;
    private String privateNoteStorageKey;

    @BeforeEach
    void setUp() {
        // created_byはMetaObjectHandlerが認証ユーザーから自動補完するため、登録前に認証しておく
        authenticate(1L, "管理者");
        com.ses.entity.Document document = documentService.registerGenerated(
                com.ses.dto.document.DocumentRegisterRequest.builder()
                        .documentType("PRIVATE_NOTE")
                        .title("1on1相談メモ ACLテスト")
                        .sourceType("GENERATED")
                        .direction("INTERNAL")
                        .counterpartyType("INTERNAL")
                        .transactionDate(LocalDate.now())
                        .businessKey("PRIVATE_ACL_TEST:" + System.nanoTime())
                        .versionDiscriminator("v1")
                        .originalName("private-note-acl.txt")
                        .contentType("text/plain;charset=UTF-8")
                        .build(),
                new ByteArrayInputStream("要員の機密相談内容".getBytes(StandardCharsets.UTF_8)));
        privateNoteDocumentId = document.getId();
        DocumentVersion version = documentVersionMapper.selectList(
                        new LambdaQueryWrapper<DocumentVersion>()
                                .eq(DocumentVersion::getDocumentId, privateNoteDocumentId)
                                .orderByDesc(DocumentVersion::getVersionNo)
                                .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        privateNoteStorageKey = version == null ? null : version.getStorageKey();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void PRIVATE_NOTEのdetailとdownloadはHRと指定管理者のみ許可される() {
        long hrUser = insertUser("HR");
        long designatedAdmin = insertUser("管理者");
        long regularAdmin = insertUser("管理者");
        long salesUser = insertUser("営業");
        long managerUser = insertUser("マネージャー");
        long engineerUser = insertUser("要員");
        long engineerId = createEngineer();
        link(engineerId, engineerUser);

        // 指定管理者へ one-on-one.confidential を割当（R1-P1-07: 実際にgroup/action/assignmentを登録）
        designate(designatedAdmin);

        // 認証をクリア
        SecurityContextHolder.clearContext();

        // ---- detail ----
        authenticate(hrUser, "HR");
        assertDoesNotThrow(() -> documentService.getDocumentDetail(privateNoteDocumentId),
                "HRはPRIVATE_NOTE detailを閲覧できる");

        authenticate(designatedAdmin, "管理者");
        assertDoesNotThrow(() -> documentService.getDocumentDetail(privateNoteDocumentId),
                "指定管理者（one-on-one.confidential割当）はPRIVATE_NOTE detailを閲覧できる");

        for (long userId : new long[]{regularAdmin, salesUser, managerUser, engineerUser}) {
            authenticate(userId, userRoles.getOrDefault(userId, "要員"));
            BusinessException ex = assertThrows(BusinessException.class, () ->
                    documentService.getDocumentDetail(privateNoteDocumentId),
                    "PRIVATE_NOTE detailは権限なしロールで403（fail-closed）");
            assertEquals(403, ex.getCode());
        }

        // ---- download（FileScopeValidationService。storage key境界） ----
        authenticate(hrUser, "HR");
        assertDoesNotThrow(() -> fileScopeValidationService.assertDownloadAllowed(privateNoteStorageKey),
                "HRはPRIVATE_NOTE downloadを許可される");

        authenticate(designatedAdmin, "管理者");
        assertDoesNotThrow(() -> fileScopeValidationService.assertDownloadAllowed(privateNoteStorageKey),
                "指定管理者はPRIVATE_NOTE downloadを許可される");

        for (long userId : new long[]{regularAdmin, salesUser, managerUser, engineerUser}) {
            authenticate(userId, userRoles.getOrDefault(userId, "要員"));
            BusinessException ex = assertThrows(BusinessException.class, () ->
                    fileScopeValidationService.assertDownloadAllowed(privateNoteStorageKey),
                    "PRIVATE_NOTE downloadは権限なしロールで403（fail-closed）");
            assertEquals(403, ex.getCode());
        }
    }

    @Test
    void 一般管理者はdocumentArchive一覧の母集団にPRIVATE_NOTEが現れない() {
        long hrUser = insertUser("HR");
        long regularAdmin = insertUser("管理者");
        long designatedAdmin = insertUser("管理者");
        designate(designatedAdmin);

        SecurityContextHolder.clearContext();

        // 管理者は全件見えるはずのところ、PRIVATE_NOTEだけはSQL母集団から除外される
        authenticate(regularAdmin, "管理者");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.ses.dto.document.DocumentListDTO> page =
                documentService.searchDocuments(new com.ses.dto.document.DocumentSearchQuery());
        assertTrue(page.getRecords().stream().noneMatch(d -> d.getId().equals(privateNoteDocumentId)),
                "一般管理者の一覧母集団にPRIVATE_NOTEが現れない（R1-P1-07）");

        // 指定管理者には現れる
        authenticate(designatedAdmin, "管理者");
        page = documentService.searchDocuments(new com.ses.dto.document.DocumentSearchQuery());
        assertTrue(page.getRecords().stream().anyMatch(d -> d.getId().equals(privateNoteDocumentId)),
                "指定管理者の一覧母集団にPRIVATE_NOTEが現れる");

        // HRにも現れる
        authenticate(hrUser, "HR");
        page = documentService.searchDocuments(new com.ses.dto.document.DocumentSearchQuery());
        assertTrue(page.getRecords().stream().anyMatch(d -> d.getId().equals(privateNoteDocumentId)),
                "HRの一覧母集団にPRIVATE_NOTEが現れる");
    }

    // ----------------------------------------------------------------
    // ヘルパー
    // ----------------------------------------------------------------

    /** 指定管理者: 新規permission groupを作成しone-on-one.confidentialを割当、ユーザーを割当。 */
    private void designate(long adminUserId) {
        PermissionGroup group = new PermissionGroup();
        group.setTenantId("default");
        group.setGroupKey("confidential-designated-" + System.nanoTime());
        group.setGroupName("confidential閲覧指定");
        group.setEnabled(1);
        permissionGroupMapper.insert(group);

        com.ses.entity.PermissionGroupAction action = new com.ses.entity.PermissionGroupAction();
        action.setTenantId("default");
        action.setGroupId(group.getId());
        action.setActionKey(CONFIDENTIAL_ACTION);
        action.setDenyFlag(0);
        permissionGroupActionMapper.insert(action);

        UserPermissionGroup assignment = new UserPermissionGroup();
        assignment.setTenantId("default");
        assignment.setUserId(adminUserId);
        assignment.setGroupId(group.getId());
        userPermissionGroupMapper.insert(assignment);
    }

    private String roleOf(long userId) {
        return userRoles.getOrDefault(userId, "要員");
    }

    long insertUser(String role) {
        // H2のsys_user.role ENUMはV32未適用のため'要員'を持たない。DBは'管理者'で保存し、
        // 実際のロールはuserRolesで保持してauthenticate()へ渡す（既存テストと同じ規約）。
        SysUser user = SysUser.builder()
                .username("pnacl-" + System.nanoTime())
                .password("x")
                .realName("PRIVATE_NOTE ACLテスト")
                .role("要員".equals(role) ? "管理者" : role)
                .status(1)
                .build();
        sysUserMapper.insert(user);
        userRoles.put(user.getId(), role);
        return user.getId();
    }

    long createEngineer() {
        Engineer engineer = Engineer.builder()
                .fullName("PN要員-" + System.nanoTime())
                .employmentType("正社員")
                .status("Bench")
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

    void authenticate(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}