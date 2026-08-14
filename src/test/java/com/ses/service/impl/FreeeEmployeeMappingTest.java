package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.payroll.FreeeEmployeeDto;
import com.ses.dto.payroll.PayrollEngineerCandidateDto;
import com.ses.entity.Engineer;
import com.ses.entity.FreeeConnection;
import com.ses.entity.FreeeEmployeeLink;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.FreeeConnectionMapper;
import com.ses.mapper.FreeeEmployeeLinkMapper;
import com.ses.service.FreeeIntegrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * HFP-01-005: 会社境界付き従業員対応付け。
 *
 * <ul>
 *   <li>current/other/null company、BP直接指定・BPへ変更済み、削除済み、存在しないemployee、
 *       unique競合、並行link、unlink対象company（AC06）</li>
 *   <li>legacy NULL/別company linkはRECONFIRM_REQUIRED表示で給与に使わない（R04-6）</li>
 *   <li>confirmedByは引数（認証主体）を一貫使用（design §9.2）</li>
 *   <li>responseに銀行/住所/家族/生年月日fieldが無い（privacy）</li>
 * </ul>
 */
@DisplayName("HFP-01-005 従業員対応付け（会社境界）")
class FreeeEmployeeMappingTest {

    private static final String HR_BASE = "https://api.freee.co.jp/hr";
    private static final String EMPLOYEES = HR_BASE + "/api/v1/companies/123/employees";

    private FreeeConnectionMapper connectionMapper;
    private FreeeEmployeeLinkMapper linkMapper;
    private EngineerMapper engineerMapper;
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private FreeeIntegrationServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        connectionMapper = mock(FreeeConnectionMapper.class);
        linkMapper = mock(FreeeEmployeeLinkMapper.class);
        engineerMapper = mock(EngineerMapper.class);
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        service = new FreeeIntegrationServiceImpl(connectionMapper, linkMapper, engineerMapper,
                restTemplate, applicationContext);
        ReflectionTestUtils.setField(service, "apiBase", "https://api.freee.co.jp");
        ReflectionTestUtils.setField(service, "oauthBase", "https://accounts.secure.freee.co.jp/public_api");
        ReflectionTestUtils.setField(service, "hrApiBase", HR_BASE);
        ReflectionTestUtils.setField(service, "clientId", "fixture-client-id");
        ReflectionTestUtils.setField(service, "clientSecret", "fixture-client-secret");
        ReflectionTestUtils.setField(service, "redirectUri", "http://localhost:8080/integrations/freee/callback");
        ReflectionTestUtils.setField(service, "encryptionKey", "change-me-change-me-change-me-1234");
        ReflectionTestUtils.setField(service, "activeProfile", "test");
        when(applicationContext.getBean(FreeeIntegrationService.class)).thenReturn(service);

        when(engineerMapper.selectList(any())).thenReturn(Collections.emptyList());
    }

    private FreeeConnection connectedRow() throws Exception {
        FreeeConnection c = new FreeeConnection();
        c.setId(1L);
        c.setCompanyId(123L);
        c.setCompanyName("テスト事業所");
        c.setAccessTokenEncrypted(encrypt("fixture-access-token"));
        c.setRefreshTokenEncrypted(encrypt("fixture-refresh-token"));
        c.setTokenExpiresAt(LocalDateTime.now().plusHours(1));
        c.setConnectionStatus("CONNECTED");
        return c;
    }

    private String encrypt(String plain) throws Exception {
        Method m = FreeeIntegrationServiceImpl.class.getDeclaredMethod("encrypt", String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, plain);
    }

    private void seedConnected() throws Exception {
        when(connectionMapper.selectOne(any())).thenReturn(connectedRow());
        when(connectionMapper.selectLatestForUpdate()).thenReturn(connectedRow());
    }

    private void stubEmployeesPage() {
        String body = "["
                + "{\"id\":501,\"num\":\"E-501\",\"display_name\":\"従業員甲\",\"entry_date\":\"2020-04-01\","
                + "\"retire_date\":null,\"payroll_calculation\":true},"
                + "{\"id\":502,\"num\":\"E-502\",\"display_name\":\"従業員乙\",\"entry_date\":\"2021-04-01\","
                + "\"retire_date\":\"2026-03-31\",\"payroll_calculation\":true}"
                + "]";
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(EMPLOYEES + "?")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private Engineer engineer(long id, String employmentType) {
        Engineer e = new Engineer();
        e.setId(id);
        e.setFullName("テスト要員" + id);
        e.setEmploymentType(employmentType);
        return e;
    }

    // ============ link ============

    @Test
    @DisplayName("link成功: 現在company・非BP・一覧に存在するemployeeでcompany付き保存（AC06）")
    void link成功でcompany付きで保存される() throws Exception {
        seedConnected();
        stubEmployeesPage();
        when(engineerMapper.selectById(7L)).thenReturn(engineer(7L, "正社員"));
        when(linkMapper.selectOne(any())).thenReturn(null);

        service.link(7L, "501", 9L);

        ArgumentCaptor<FreeeEmployeeLink> captor = ArgumentCaptor.forClass(FreeeEmployeeLink.class);
        verify(linkMapper).insert(captor.capture());
        FreeeEmployeeLink saved = captor.getValue();
        assertEquals(7L, saved.getEngineerId());
        assertEquals("501", saved.getFreeeEmployeeId());
        assertEquals(123L, saved.getFreeeCompanyId());
        assertEquals(9L, saved.getConfirmedBy(), "confirmedByは認証主体（引数）を一貫使用");
    }

    @Test
    @DisplayName("BP要員のlinkは拒否（UI/API経路共通）（AC06）")
    void BP要員のlinkは拒否する() throws Exception {
        seedConnected();
        stubEmployeesPage();
        when(engineerMapper.selectById(7L)).thenReturn(engineer(7L, "BP"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.link(7L, "501", 9L));
        assertEquals("error.payroll.bpExcluded", ex.getMessage());
        verify(linkMapper, never()).insert(any(FreeeEmployeeLink.class));
    }

    @Test
    @DisplayName("削除済み要員（selectById null）は拒否（AC06）")
    void 削除済み要員のlinkは拒否する() throws Exception {
        seedConnected();
        stubEmployeesPage();
        when(engineerMapper.selectById(7L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.link(7L, "501", 9L));
        assertEquals("error.payroll.invalidEngineer", ex.getMessage());
    }

    @Test
    @DisplayName("現在company一覧に無いemployeeは拒否（AC06）")
    void 存在しないemployeeのlinkは拒否する() throws Exception {
        seedConnected();
        stubEmployeesPage();
        when(engineerMapper.selectById(7L)).thenReturn(engineer(7L, "正社員"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.link(7L, "999", 9L));
        assertEquals("error.payroll.invalidEmployeeId", ex.getMessage());
    }

    @Test
    @DisplayName("同一company×同一employeeの競合は409（AC06）")
    void duplicateEmployeeは409になる() throws Exception {
        seedConnected();
        stubEmployeesPage();
        when(engineerMapper.selectById(7L)).thenReturn(engineer(7L, "正社員"));
        FreeeEmployeeLink other = new FreeeEmployeeLink();
        other.setEngineerId(8L);
        other.setFreeeEmployeeId("501");
        other.setFreeeCompanyId(123L);
        when(linkMapper.selectOne(any())).thenReturn(other);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.link(7L, "501", 9L));
        assertEquals(409, ex.getCode());
        assertEquals("error.payroll.duplicateEmployeeLink", ex.getMessage());
    }

    @Test
    @DisplayName("接続がCONNECTEDでない場合はlink不可（AC06）")
    void 接続未確立のlinkは拒否する() throws Exception {
        FreeeConnection reauth = connectedRow();
        reauth.setConnectionStatus("REAUTH_REQUIRED");
        when(connectionMapper.selectOne(any())).thenReturn(reauth);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.link(7L, "501", 9L));
        assertEquals("error.payroll.reauthRequired", ex.getMessage());
    }

    @Test
    @DisplayName("同一engineerの別company linkは明示再対応付けで現在companyへ更新（R04-6）")
    void 別companylinkの再対応付けは現在companyへ更新する() throws Exception {
        seedConnected();
        stubEmployeesPage();
        when(engineerMapper.selectById(7L)).thenReturn(engineer(7L, "正社員"));
        when(linkMapper.selectOne(any())).thenReturn(null);
        FreeeEmployeeLink old = new FreeeEmployeeLink();
        old.setId(11L);
        old.setEngineerId(7L);
        old.setFreeeEmployeeId("E-OTHER");
        old.setFreeeCompanyId(456L);
        when(linkMapper.selectOne(any())).thenReturn(null).thenReturn(old);

        service.link(7L, "501", 9L);

        ArgumentCaptor<FreeeEmployeeLink> captor = ArgumentCaptor.forClass(FreeeEmployeeLink.class);
        verify(linkMapper).updateById(captor.capture());
        assertEquals(123L, captor.getValue().getFreeeCompanyId());
        assertEquals("501", captor.getValue().getFreeeEmployeeId());
    }

    // ============ unlink ============

    @Test
    @DisplayName("unlink: 現在companyのlinkだけ解除（他companyは拒否）（AC06）")
    void unlinkは現在companyだけ解除する() throws Exception {
        seedConnected();

        FreeeEmployeeLink current = new FreeeEmployeeLink();
        current.setEngineerId(7L);
        current.setFreeeCompanyId(123L);
        when(linkMapper.selectOne(any())).thenReturn(current);
        doNothing().when(linkMapper).deleteByEngineerIdHard(7L);
        service.unlink(7L);
        verify(linkMapper).deleteByEngineerIdHard(7L);

        // 他companyのlinkは解除不可
        FreeeEmployeeLink other = new FreeeEmployeeLink();
        other.setEngineerId(8L);
        other.setFreeeCompanyId(456L);
        when(linkMapper.selectOne(any())).thenReturn(other);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.unlink(8L));
        assertEquals("error.payroll.companyMismatchLink", ex.getMessage());
        verify(linkMapper, never()).deleteByEngineerIdHard(8L);
    }

    @Test
    @DisplayName("unlink: legacy NULL linkは解除できる（要再確認からの明示解除）")
    void unlinkはlegacyNULLlinkを解除できる() throws Exception {
        seedConnected();
        FreeeEmployeeLink legacy = new FreeeEmployeeLink();
        legacy.setEngineerId(7L);
        legacy.setFreeeCompanyId(null);
        when(linkMapper.selectOne(any())).thenReturn(legacy);
        doNothing().when(linkMapper).deleteByEngineerIdHard(7L);
        service.unlink(7L);
        verify(linkMapper).deleteByEngineerIdHard(7L);
    }

    // ============ employees表示 ============

    @Test
    @DisplayName("employees: LINKED/RECONFIRM_REQUIRED/UNLINKEDを区別し最小fieldだけ返す（AC06）")
    void employeesのlinkStateとprivacy() throws Exception {
        seedConnected();
        stubEmployeesPage();

        FreeeEmployeeLink current = new FreeeEmployeeLink();
        current.setEngineerId(7L);
        current.setFreeeEmployeeId("501");
        current.setFreeeCompanyId(123L);
        FreeeEmployeeLink stale = new FreeeEmployeeLink();
        stale.setEngineerId(8L);
        stale.setFreeeEmployeeId("502");
        stale.setFreeeCompanyId(null); // legacy NULL → 要再確認
        List<FreeeEmployeeLink> links = new ArrayList<>();
        links.add(current);
        links.add(stale);
        when(linkMapper.selectList(any())).thenReturn(links);
        when(engineerMapper.selectList(any())).thenReturn(List.of(engineer(7L, "正社員")));

        List<FreeeEmployeeDto> result = service.employees();
        assertEquals(2, result.size());

        FreeeEmployeeDto e501 = result.stream().filter(e -> e.getId().equals("501")).findFirst().orElseThrow();
        assertEquals("LINKED", e501.getLinkState());
        assertEquals(7L, e501.getLinkedEngineerId());
        assertEquals("テスト要員7", e501.getLinkedEngineerName());
        assertEquals("E-501", e501.getNum());
        assertEquals("2020-04-01", e501.getEntryDate());
        assertEquals(true, e501.getPayrollCalculation());

        // legacy NULL linkは要再確認（給与表示には使われない）
        FreeeEmployeeDto e502 = result.stream().filter(e -> e.getId().equals("502")).findFirst().orElseThrow();
        assertEquals("RECONFIRM_REQUIRED", e502.getLinkState());
        assertNull(e502.getLinkedEngineerId());

        // 銀行/住所/家族/生年月日等の不要fieldがJSONに無いこと（privacy）
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
        for (String forbidden : new String[]{"bank", "address", "family", "birth", "dependent", "email"}) {
            assertTrue(!json.toLowerCase().contains(forbidden),
                    "給与APIへ不要なPII fieldが混入: " + forbidden);
        }
    }

    @Test
    @DisplayName("engineerCandidatesは非BP・未削除だけを返す（AC06）")
    void engineerCandidatesは非BPだけ返す() {
        when(engineerMapper.selectList(any())).thenReturn(List.of(
                engineer(1L, "正社員"), engineer(2L, "業務委託"), engineer(3L, "BP")));

        List<PayrollEngineerCandidateDto> candidates = service.engineerCandidates();
        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().noneMatch(c -> "BP".equals(c.getEmploymentType())));
    }

    @Test
    @DisplayName("未接続時はlink/unlink/employeesがnotConnected（AC06）")
    void 未接続時は拒否する() throws Exception {
        when(connectionMapper.selectOne(any())).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.link(7L, "501", 9L));
        assertEquals("error.payroll.notConnected", ex.getMessage());
        BusinessException ex2 = assertThrows(BusinessException.class, () -> service.unlink(7L));
        assertEquals("error.payroll.notConnected", ex2.getMessage());
        BusinessException ex3 = assertThrows(BusinessException.class, () -> service.employees());
        assertEquals("error.payroll.notConnected", ex3.getMessage());
    }

}
