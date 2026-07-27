package com.ses.service.security;

import com.ses.config.LoginUser;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.service.OrganizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 管理者・部門責任者・一般ユーザーの組織scopeをSQL境界で検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("/sql/engineer-schema-h2.sql")
class OrganizationScopeServiceImplTest {

    @Autowired
    private OrganizationScopeService scopeService;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private EngineerAccountLinkMapper engineerAccountLinkMapper;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 部門責任者は自組織と子組織をSQL条件で閲覧できる() {
        OrganizationUnit root = organization("SCOPE-ROOT", "営業本部", null);
        organizationService.save(root);
        OrganizationUnit child = organization("SCOPE-CHILD", "営業一課", root.getId());
        organizationService.save(child);
        OrganizationUnit other = organization("SCOPE-OTHER", "人事部", null);
        organizationService.save(other);

        SysUser manager = insertUser("scope-manager", "部門長", "マネージャー");
        organizationService.assignUser(UserOrganization.builder()
                .userId(manager.getId()).organizationId(root.getId()).primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1)).build());
        authenticate(manager);

        assertEquals(2, scopeService.listVisibleOrganizations(null, LocalDate.of(2026, 7, 1)).size());
        assertEquals(2, scopeService.countVisibleOrganizations(null, LocalDate.of(2026, 7, 1)));
        assertEquals(2, scopeService.exportVisibleOrganizations(null, LocalDate.of(2026, 7, 1)).size());
        assertTrue(scopeService.allowedOrganizationIds(LocalDate.of(2026, 7, 1)).contains(child.getId()));
        assertFalse(scopeService.allowedOrganizationIds(LocalDate.of(2026, 7, 1)).contains(other.getId()));
        assertTrue(scopeService.allowedEngineerIds(LocalDate.of(2026, 7, 1)).isEmpty());
        assertTrue(scopeService.allowedContractIds(LocalDate.of(2026, 7, 1)).isEmpty());
        assertTrue(scopeService.allowedInvoiceIds(LocalDate.of(2026, 7, 1)).isEmpty());
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> scopeService.assertAllowedOrganization(other.getId()));
    }

    @Test
    void 営業とHRは所属組織だけで子組織へ拡張しない() {
        OrganizationUnit root = organization("SCOPE-SALES", "営業", null);
        organizationService.save(root);
        OrganizationUnit child = organization("SCOPE-SALES-CHILD", "営業課", root.getId());
        organizationService.save(child);
        SysUser sales = insertUser("scope-sales", "営業", "営業");
        organizationService.assignUser(UserOrganization.builder()
                .userId(sales.getId()).organizationId(root.getId()).primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1)).build());
        authenticate(sales);

        assertEquals(List.of(root.getId()), scopeService.allowedOrganizationIds(LocalDate.of(2026, 7, 1))
                .stream().sorted().toList());
        assertEquals(1, scopeService.listVisibleOrganizations(null, LocalDate.of(2026, 7, 1)).size());
    }

    @Test
    void HRも所属組織だけを一覧件数出力で参照する() {
        OrganizationUnit root = organization("SCOPE-HR", "人事", null);
        organizationService.save(root);
        OrganizationUnit child = organization("SCOPE-HR-CHILD", "採用課", root.getId());
        organizationService.save(child);
        SysUser hr = insertUser("scope-hr", "人事", "HR");
        organizationService.assignUser(UserOrganization.builder()
                .userId(hr.getId()).organizationId(root.getId()).primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1)).build());
        authenticate(hr);

        assertEquals(1, scopeService.countVisibleOrganizations(null, LocalDate.of(2026, 7, 1)));
        assertEquals(root.getId(), scopeService.exportVisibleOrganizations(null,
                LocalDate.of(2026, 7, 1)).get(0).getId());
    }

    @Test
    void 管理者は全組織を閲覧し組織条件を追加しない() {
        OrganizationUnit root = organization("SCOPE-ADMIN", "全社", null);
        organizationService.save(root);
        SysUser admin = sysUserMapper.selectByUsername("admin");
        // @Sqlの既存fixtureは実行環境によって日本語seedの文字コードが崩れるため、
        // 認証主体のロールはテストの意図を明示して設定する。
        admin.setRole("管理者");
        authenticate(admin);

        assertTrue(scopeService.hasFullAccess());
        assertTrue(scopeService.allowedOrganizationIds().isEmpty(), "全件は空集合ではなくSQL無条件で表現する");
        assertEquals(1, scopeService.listVisibleOrganizations(null, LocalDate.of(2026, 7, 1)).size());
    }

    @Test
    void 直属管理の外部組織ユーザーは個人だけ許可し組織全体へ拡張しない() {
        OrganizationUnit own = organization("SCOPE-MANAGER-OWN", "自組織", null);
        organizationService.save(own);
        OrganizationUnit external = organization("SCOPE-MANAGER-EXT", "外部組織", null);
        organizationService.save(external);

        SysUser manager = insertUser("scope-manager-direct", "部門長", "マネージャー");
        SysUser subordinate = insertUser("scope-subordinate", "直属要員", "要員");
        SysUser peer = insertUser("scope-peer", "外部同僚", "要員");
        organizationService.assignUser(UserOrganization.builder()
                .userId(manager.getId()).organizationId(own.getId()).primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1)).build());
        organizationService.assignUser(UserOrganization.builder()
                .userId(subordinate.getId()).organizationId(external.getId()).managerUserId(manager.getId())
                .validFrom(LocalDate.of(2026, 1, 1)).build());
        organizationService.assignUser(UserOrganization.builder()
                .userId(peer.getId()).organizationId(external.getId())
                .validFrom(LocalDate.of(2026, 1, 1)).build());
        authenticate(manager);

        LocalDate asOf = LocalDate.of(2026, 7, 1);
        assertFalse(scopeService.allowedOrganizationIds(asOf).contains(external.getId()));
        assertTrue(scopeService.allowedDirectUserIds(asOf).contains(subordinate.getId()));
        assertTrue(scopeService.isAllowedUser(subordinate.getId(), asOf));
        assertFalse(scopeService.isAllowedUser(peer.getId(), asOf));
    }

    @Test
    void 要員一覧の組織scopeは所属組織と直属ユーザーをSQLで交差する() {
        OrganizationUnit own = organization("SCOPE-ENGINEER-OWN", "要員自組織", null);
        organizationService.save(own);
        OrganizationUnit other = organization("SCOPE-ENGINEER-OTHER", "要員他組織", null);
        organizationService.save(other);

        SysUser manager = insertUser("scope-engineer-manager", "要員部門長", "マネージャー");
        SysUser ownUser = insertUser("scope-engineer-own", "自組織要員", "要員");
        SysUser otherUser = insertUser("scope-engineer-other", "他組織要員", "要員");
        organizationService.assignUser(UserOrganization.builder()
                .userId(manager.getId()).organizationId(own.getId()).primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1)).build());
        organizationService.assignUser(UserOrganization.builder()
                .userId(ownUser.getId()).organizationId(own.getId()).primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1)).build());
        organizationService.assignUser(UserOrganization.builder()
                .userId(otherUser.getId()).organizationId(other.getId()).primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1)).build());

        Engineer ownEngineer = Engineer.builder().fullName("自組織要員").employmentType("正社員")
                .status("Bench").build();
        Engineer otherEngineer = Engineer.builder().fullName("他組織要員").employmentType("正社員")
                .status("Bench").build();
        engineerMapper.insert(ownEngineer);
        engineerMapper.insert(otherEngineer);
        EngineerAccountLink ownLink = new EngineerAccountLink();
        ownLink.setEngineerId(ownEngineer.getId());
        ownLink.setSysUserId(ownUser.getId());
        engineerAccountLinkMapper.insert(ownLink);
        EngineerAccountLink otherLink = new EngineerAccountLink();
        otherLink.setEngineerId(otherEngineer.getId());
        otherLink.setSysUserId(otherUser.getId());
        engineerAccountLinkMapper.insert(otherLink);

        authenticate(manager);
        assertEquals(java.util.Set.of(ownEngineer.getId()),
                scopeService.allowedEngineerIds(LocalDate.of(2026, 7, 1)));
    }

    @Test
    void 標準principalはusernameからローカルユーザーへ解決する() {
        OrganizationUnit own = organization("SCOPE-STANDARD-PRINCIPAL", "標準principal組織", null);
        organizationService.save(own);
        SysUser sales = insertUser("standard-scope-sales", "標準営業", "営業");
        organizationService.assignUser(UserOrganization.builder()
                .userId(sales.getId()).organizationId(own.getId()).primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1)).build());

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                sales.getUsername(), null, List.of(new SimpleGrantedAuthority("ROLE_営業"))));

        assertEquals(java.util.Set.of(own.getId()),
                scopeService.allowedOrganizationIds(LocalDate.of(2026, 7, 1)));
    }

    private OrganizationUnit organization(String code, String name, Long parentId) {
        return OrganizationUnit.builder()
                .code(code).name(name).type("部").parentId(parentId)
                .validFrom(LocalDate.of(2026, 1, 1)).status("有効").version(0).build();
    }

    private SysUser insertUser(String username, String realName, String role) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword("pass");
        user.setRealName(realName);
        user.setRole(role);
        user.setStatus(1);
        sysUserMapper.insert(user);
        return user;
    }

    private void authenticate(SysUser user) {
        LoginUser principal = new LoginUser(user,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
