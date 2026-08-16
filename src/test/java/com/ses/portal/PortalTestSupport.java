package com.ses.portal;

import com.ses.common.util.TotpUtil;
import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalTermsConsent;
import com.ses.entity.PortalUser;
import com.ses.mapper.PortalOrganizationMapper;
import com.ses.mapper.PortalTermsConsentMapper;
import com.ses.mapper.PortalUserMapper;
import com.ses.service.portal.PortalSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * portalテスト共通fixture（T083 F2）。3組織（顧客A/顧客B/BP）と各user/session/同意を作る。
 * テストデータは@Transactionalでrollbackされる（emailはテスト毎に一意化）。
 */
public abstract class PortalTestSupport {

    protected static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
    protected static final String PASSWORD = "password123";

    @Autowired
    protected PortalOrganizationMapper organizationMapper;
    @Autowired
    protected PortalUserMapper userMapper;
    @Autowired
    protected PortalTermsConsentMapper termsConsentMapper;
    @Autowired
    protected PortalSessionService portalSessionService;

    /** user/規約同意済みsessionのfixture。 */
    protected record UserFixture(PortalUser user, MockCookie sessionCookie) {
    }

    /** portal CSRF（cookie+header値）。GET /portal/loginで発行されたcookieから取得する。 */
    protected record CsrfPair(MockCookie cookie, String headerValue) {
    }

    protected CsrfPair fetchPortalCsrf(org.springframework.test.web.servlet.MockMvc mockMvc) throws Exception {
        var csrfPage = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/portal/login"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        String value = csrfPage.getResponse().getCookie("XSRF-TOKEN-PORTAL").getValue();
        return new CsrfPair(new MockCookie("XSRF-TOKEN-PORTAL", value), value);
    }

    /** 組織fixture。 */
    protected PortalOrganization createCustomerOrg(String suffix) {
        long customerId = insertCustomer("portal-test-customer-" + suffix);
        PortalOrganization org = new PortalOrganization();
        org.setType("CUSTOMER");
        org.setCustomerId(customerId);
        org.setStatus("ACTIVE");
        organizationMapper.insert(org);
        return org;
    }

    protected PortalOrganization createBpOrg(String suffix) {
        long bpCompanyId = insertBpCompany("portal-test-bp-" + suffix);
        PortalOrganization org = new PortalOrganization();
        org.setType("BP");
        org.setBpCompanyId(bpCompanyId);
        org.setStatus("ACTIVE");
        organizationMapper.insert(org);
        return org;
    }

    /** user作成（MFA未設定）。 */
    protected PortalUser createUser(PortalOrganization org, String email) {
        PortalUser user = new PortalUser();
        user.setPortalOrgId(org.getId());
        user.setEmail(email);
        user.setDisplayName("テスト利用者");
        user.setPasswordHash(PASSWORD_ENCODER.encode(PASSWORD));
        user.setStatus("ACTIVE");
        user.setMfaPolicy("REQUIRED");
        userMapper.insert(user);
        return user;
    }

    /** MFA設定済みuser（serviceと同じAES-GCM形式で暗号化して保存。verifyはservice経由で使う）。 */
    protected PortalUser enableMfa(PortalUser user, String secret) {
        userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalUser>()
                .eq("id", user.getId())
                .set("totp_secret_encrypted", encryptSecret(secret))
                .set("totp_secret_key_version", "v1")
                .set("mfa_enabled_at", LocalDateTime.now()));
        return userMapper.selectById(user.getId());
    }

    /** テスト用: PortalMfaServiceImplと同じAES/GCM形式でsecretを暗号化する（dev鍵）。 */
    protected String encryptSecret(String secret) {
        try {
            byte[] keyBytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(mfaEncryptionKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            javax.crypto.spec.SecretKeySpec key = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "v1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(iv)
                    + ":" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("テスト用MFA secret暗号化に失敗しました", e);
        }
    }

    @org.springframework.beans.factory.annotation.Value("${app.security.mfa.encryption-key:dev-only-change-this-mfa-key}")
    protected String mfaEncryptionKey;

    /** session発行してcookieを返す（PortalSessionService経由の本物のsession）。 */
    protected MockCookie issueSession(PortalUser user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        portalSessionService.issue(request, response, user.getId());
        String value = response.getCookie("PORTAL_SESSION").getValue();
        return new MockCookie("PORTAL_SESSION", value);
    }

    /** 規約同意（現行version=1）。 */
    protected void consentTerms(PortalUser user) {
        PortalTermsConsent consent = new PortalTermsConsent();
        consent.setUserId(user.getId());
        consent.setTermsVersion("1");
        consent.setConsentedAt(LocalDateTime.now());
        termsConsentMapper.insert(consent);
    }

    /** 完全な利用可能user（MFA設定+同意+session）。 */
    protected UserFixture readyUser(PortalOrganization org, String email) {
        PortalUser user = createUser(org, email);
        enableMfa(user, uniqueSecret());
        consentTerms(user);
        return new UserFixture(user, issueSession(user));
    }

    protected static String uniqueSecret() {
        return TotpUtil.normalizeSecret(UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 32));
    }

    /** 現在のTOTPコード（step 0 skewで1回だけ有効なwindow。テスト用は同じstep内で生成する） */
    protected static String totpCode(String secret) {
        long step = Instant.now().getEpochSecond() / 30;
        return TotpUtil.code(secret, step, 6);
    }

    private long insertCustomer(String name) {
        org.springframework.jdbc.core.JdbcTemplate jdbc = jdbcTemplate();
        jdbc.update("INSERT INTO m_customer (company_name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT id FROM m_customer WHERE company_name = ?", Long.class, name);
    }

    private long insertBpCompany(String legalName) {
        org.springframework.jdbc.core.JdbcTemplate jdbc = jdbcTemplate();
        jdbc.update("INSERT INTO m_bp_company (legal_name, entity_type, status) VALUES (?, 'CORPORATE', 'ACTIVE')",
                legalName);
        return jdbc.queryForObject("SELECT id FROM m_bp_company WHERE legal_name = ?", Long.class, legalName);
    }

    protected abstract org.springframework.jdbc.core.JdbcTemplate jdbcTemplate();
}
