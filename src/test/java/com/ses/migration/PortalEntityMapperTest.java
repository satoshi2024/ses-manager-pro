package com.ses.migration;

import com.ses.entity.PortalInvitation;
import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalTermsConsent;
import com.ses.entity.PortalUser;
import com.ses.entity.PortalUserPermission;
import com.ses.mapper.PortalInvitationMapper;
import com.ses.mapper.PortalOrganizationMapper;
import com.ses.mapper.PortalTermsConsentMapper;
import com.ses.mapper.PortalUserMapper;
import com.ses.mapper.PortalUserPermissionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T082 F1のmapper経路検証（H2 replay schema）。
 * portal entity/mapperがschema-locationsのschema-portal-h2.sqlと整合し、
 * 招待token一回性CAS（design §6.3）とemail一意・同意UNIQUEがmapper経路で成立することを確認する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PortalEntityMapperTest {

    @Autowired
    private PortalOrganizationMapper organizationMapper;
    @Autowired
    private PortalUserMapper userMapper;
    @Autowired
    private PortalInvitationMapper invitationMapper;
    @Autowired
    private PortalUserPermissionMapper permissionMapper;
    @Autowired
    private PortalTermsConsentMapper consentMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long insertCustomer(String name) {
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", name);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, name);
        return id;
    }

    @Test
    void portal組織とuserと招待CASと同意がmapper経路で成立する() {
        long customerId = insertCustomer("portal-test-customer");

        PortalOrganization org = new PortalOrganization();
        org.setType("CUSTOMER");
        org.setCustomerId(customerId);
        org.setStatus("ACTIVE");
        organizationMapper.insert(org);
        assertNotNull(org.getId(), "portal組織のIDが採番されるはず");

        PortalOrganization found = organizationMapper.selectByCustomerId(customerId);
        assertNotNull(found, "customer_idで組織を引けるはず");
        assertEquals(org.getId(), found.getId());

        PortalUser user = new PortalUser();
        user.setPortalOrgId(org.getId());
        user.setEmail("portal-test@example.com");
        user.setDisplayName("テスト利用者");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        assertNotNull(user.getId());

        PortalUser byEmail = userMapper.selectByEmail("portal-test@example.com");
        assertNotNull(byEmail, "emailでuserを引けるはず");
        assertEquals("ACTIVE", byEmail.getStatus());

        // email一意（DB UNIQUE制約。論理削除以外の重複は拒否）
        PortalUser duplicate = new PortalUser();
        duplicate.setPortalOrgId(org.getId());
        duplicate.setEmail("portal-test@example.com");
        assertThrows(DuplicateKeyException.class, () -> userMapper.insert(duplicate),
                "同一emailの重複userを拒否するはず（UNIQUE(email)）");

        // 招待token: hashのみ保存・CASで一回限り
        PortalInvitation invitation = new PortalInvitation();
        invitation.setPortalOrgId(org.getId());
        invitation.setEmail("portal-test@example.com");
        invitation.setRole("ADMIN");
        invitation.setTokenHash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        invitation.setExpiresAt(LocalDateTime.now().plusHours(72));
        invitationMapper.insert(invitation);
        assertNotNull(invitation.getId());

        PortalInvitation byToken = invitationMapper.selectByTokenHash(invitation.getTokenHash());
        assertNotNull(byToken, "token_hashで招待を引けるはず");
        assertNull(byToken.getUsedAt(), "発行直後は未使用");

        int consumed1 = invitationMapper.consumeIfUnused(invitation.getId(),
                LocalDateTime.now(), user.getId());
        int consumed2 = invitationMapper.consumeIfUnused(invitation.getId(),
                LocalDateTime.now(), user.getId());
        assertEquals(1, consumed1, "1回目のCASは1件成功するはず");
        assertEquals(0, consumed2, "2回目（同時使用の敗者）は0件のはず");

        // 権限
        PortalUserPermission permission = new PortalUserPermission();
        permission.setUserId(user.getId());
        permission.setPermissionKey("document.view");
        permissionMapper.insert(permission);
        List<String> keys = permissionMapper.selectPermissionKeys(user.getId());
        assertTrue(keys.contains("document.view"), "permission_keyが引けるはず");

        // 規約同意: append-only・同一versionの二重同意はUNIQUEで拒否
        PortalTermsConsent consent = new PortalTermsConsent();
        consent.setUserId(user.getId());
        consent.setTermsVersion("1");
        consent.setConsentedAt(LocalDateTime.now());
        consentMapper.insert(consent);
        assertNotNull(consent.getId());

        PortalTermsConsent duplicateConsent = new PortalTermsConsent();
        duplicateConsent.setUserId(user.getId());
        duplicateConsent.setTermsVersion("1");
        duplicateConsent.setConsentedAt(LocalDateTime.now());
        assertThrows(DuplicateKeyException.class, () -> consentMapper.insert(duplicateConsent),
                "同一versionへの二重同意を拒否するはず（UNIQUE(user_id, terms_version)）");

        assertEquals("1", consentMapper.latestConsentedVersion(user.getId()),
                "最新同意versionが引けるはず");
        assertEquals(0, consentMapper.latestConsentedVersion(user.getId() + 1000) == null ? 0 : 1,
                "未同意userはnullのはず");
    }

    @Test
    void BP支払の受領確認列がschemaに存在する() {
        // H2は識別子を大文字で情報スキーマへ記録するためUPPER比較（DispatchComplianceSchemaH2Testと同じ）
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE UPPER(table_name)=UPPER('t_bp_payment')"
                        + " AND UPPER(column_name)=UPPER('received_confirmed_at')",
                Integer.class);
        assertEquals(1, count, "t_bp_payment.received_confirmed_at がH2 replay schemaに存在するはず");
    }
}
