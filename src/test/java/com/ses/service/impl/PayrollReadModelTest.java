package com.ses.service.impl;

import com.ses.dto.payroll.PayrollItemDto;
import com.ses.dto.payroll.PayrollStatementDto;
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
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * HFP-01-006: 給与・賞与のread model（公式field→nullable金額・区分付きitems・対応付けfilter）。
 *
 * <ul>
 *   <li>salary/bonus各fixture、計算中null、正式0円、同名item 2件、未対応、BP変更済み、
 *       別company link、安定sort（AC07/AC08）</li>
 *   <li>response JSONにraw provider objectや不要PIIが混入しない（privacy）</li>
 * </ul>
 */
@DisplayName("HFP-01-006 給与・賞与 read model")
class PayrollReadModelTest {

    private static final String HR_BASE = "https://api.freee.co.jp/hr";
    private static final String SALARY = HR_BASE + "/api/v1/salaries/employee_payroll_statements";
    private static final String BONUS = HR_BASE + "/api/v1/bonuses/employee_payroll_statements";

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
    }

    private String encrypt(String plain) throws Exception {
        Method m = FreeeIntegrationServiceImpl.class.getDeclaredMethod("encrypt", String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, plain);
    }

    private void seedConnection() throws Exception {
        FreeeConnection c = new FreeeConnection();
        c.setId(1L);
        c.setCompanyId(123L);
        c.setAccessTokenEncrypted(encrypt("fixture-access-token"));
        c.setRefreshTokenEncrypted(encrypt("fixture-refresh-token"));
        c.setTokenExpiresAt(LocalDateTime.now().plusHours(1));
        c.setConnectionStatus("CONNECTED");
        when(connectionMapper.selectOne(any())).thenReturn(c);
    }

    private String fixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/freee/" + name)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found: /freee/" + name);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** 現在companyの有効link: 501→engineer7, 502→engineer8。 */
    private void seedLinks() {
        List<FreeeEmployeeLink> links = new ArrayList<>();
        FreeeEmployeeLink l1 = new FreeeEmployeeLink();
        l1.setEngineerId(7L);
        l1.setFreeeEmployeeId("501");
        l1.setFreeeCompanyId(123L);
        links.add(l1);
        FreeeEmployeeLink l2 = new FreeeEmployeeLink();
        l2.setEngineerId(8L);
        l2.setFreeeEmployeeId("502");
        l2.setFreeeCompanyId(123L);
        links.add(l2);
        when(linkMapper.selectList(any())).thenReturn(links);
    }

    private void seedEngineers(Engineer... engineers) {
        when(engineerMapper.selectList(any())).thenReturn(List.of(engineers));
    }

    private Engineer engineer(long id, String name, String employmentType) {
        Engineer e = new Engineer();
        e.setId(id);
        e.setFullName(name);
        e.setEmploymentType(employmentType);
        return e;
    }

    private void expectSalary(String body) throws Exception {
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(SALARY + "?")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectBonus(String body) throws Exception {
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith(BONUS + "?")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("salary: 公式fieldから全部品・区分付きitems・会社負担を変換（AC07）")
    void salaryを完全変換する() throws Exception {
        seedConnection();
        seedLinks();
        seedEngineers(engineer(7L, "テスト要員7", "正社員"));
        expectSalary(fixture("salary-calculated.json"));

        List<PayrollStatementDto> all = service.statements(2026, 7, "salary");
        assertEquals(1, all.size());
        PayrollStatementDto d = all.get(0);
        assertEquals(7L, d.getEngineerId());
        assertEquals("テスト要員7", d.getEngineerName());
        assertEquals("501", d.getEmployeeId());
        assertEquals("E-501", d.getEmployeeNumber());
        assertEquals("2026-07-25", d.getPayDate());
        assertEquals(true, d.getFixed());
        assertEquals("calculated", d.getCalculationStatus());
        assertEquals(new BigDecimal("250000"), d.getGrossAmount());
        assertEquals(new BigDecimal("50000"), d.getDeductionAmount());
        assertEquals(new BigDecimal("200000"), d.getNetAmount());
        assertEquals(new BigDecimal("32000"), d.getEmployerShareAmount());

        assertEquals(6, d.getItems().size());
        assertEquals("PAYMENT", d.getItems().get(0).getCategory());
        assertEquals("基本給", d.getItems().get(0).getName());
        assertEquals(new BigDecimal("220000"), d.getItems().get(0).getAmount());
        assertEquals("PAYMENT", d.getItems().get(1).getCategory());
        assertEquals("DEDUCTION", d.getItems().get(2).getCategory());
        assertEquals("DEDUCTION", d.getItems().get(3).getCategory());
        assertEquals("EMPLOYER_SHARE", d.getItems().get(4).getCategory());
        assertEquals("EMPLOYER_SHARE", d.getItems().get(5).getCategory());
    }

    @Test
    @DisplayName("bonus: allowances→ALLOWANCE・deductions→DEDUCTION・employerShareはnull（AC07）")
    void bonusを完全変換する() throws Exception {
        seedConnection();
        seedLinks();
        seedEngineers(engineer(8L, "テスト要員8", "正社員"));
        expectBonus(fixture("bonus-calculated.json"));

        List<PayrollStatementDto> all = service.statements(2026, 7, "bonus");
        assertEquals(1, all.size());
        PayrollStatementDto d = all.get(0);
        assertEquals(8L, d.getEngineerId());
        assertEquals("bonus", d.getType());
        assertEquals(new BigDecimal("500000"), d.getGrossAmount());
        assertNull(d.getEmployerShareAmount(), "賞与に会社負担は無い");
        assertEquals(3, d.getItems().size());
        assertEquals("ALLOWANCE", d.getItems().get(0).getCategory());
        assertEquals("DEDUCTION", d.getItems().get(1).getCategory());
        assertEquals("DEDUCTION", d.getItems().get(2).getCategory());
    }

    @Test
    @DisplayName("計算中nullは保持され、正式0円は0として返る（AC07）")
    void 計算中nullと正式0円を区別する() throws Exception {
        seedConnection();
        seedLinks();
        seedEngineers(engineer(7L, "テスト要員7", "正社員"));
        // 計算中（null）＋同じ要員に正式0円の2件
        String body = "{\"employee_payroll_statements\":["
                + "{\"id\":9001,\"company_id\":123,\"employee_id\":501,\"employee_num\":\"E-501\","
                + "\"fixed\":false,\"calc_status\":\"calculating\","
                + "\"gross_payment_amount\":null,\"total_deduction_amount\":null,\"net_payment_amount\":null,"
                + "\"payments\":[],\"deductions\":[],\"deductions_employer_share\":[]},"
                + "{\"id\":9002,\"company_id\":123,\"employee_id\":501,\"employee_num\":\"E-501\","
                + "\"fixed\":true,\"calc_status\":\"calculated\","
                + "\"gross_payment_amount\":\"0\",\"total_deduction_amount\":\"0\",\"net_payment_amount\":\"0\","
                + "\"payments\":[{\"name\":\"基本給\",\"amount\":\"0\"}],"
                + "\"deductions\":[],\"deductions_employer_share\":[]}],\"total_count\":2}";
        expectSalary(body);

        List<PayrollStatementDto> all = service.statements(2026, 7, "salary");
        assertEquals(2, all.size());
        PayrollStatementDto calculating = all.stream()
                .filter(s -> "calculating".equals(s.getCalculationStatus())).findFirst().orElseThrow();
        assertNull(calculating.getGrossAmount(), "計算中nullを0へ変換しない");
        PayrollStatementDto zero = all.stream()
                .filter(s -> "calculated".equals(s.getCalculationStatus())).findFirst().orElseThrow();
        assertEquals(BigDecimal.ZERO, zero.getGrossAmount(), "正式0円は0のまま");
    }

    @Test
    @DisplayName("同名itemはlistの別要素として保持される（AC07）")
    void 同名itemを失わない() throws Exception {
        seedConnection();
        seedLinks();
        seedEngineers(engineer(7L, "テスト要員7", "正社員"));
        String body = "{\"employee_payroll_statements\":["
                + "{\"id\":9001,\"company_id\":123,\"employee_id\":501,\"employee_num\":\"E-501\","
                + "\"fixed\":true,\"calc_status\":\"calculated\","
                + "\"gross_payment_amount\":\"1000\",\"total_deduction_amount\":\"0\",\"net_payment_amount\":\"1000\","
                + "\"payments\":[{\"name\":\"手当\",\"amount\":\"500\"},{\"name\":\"手当\",\"amount\":\"500\"}],"
                + "\"deductions\":[],\"deductions_employer_share\":[]}],\"total_count\":1}";
        expectSalary(body);

        List<PayrollStatementDto> all = service.statements(2026, 7, "salary");
        assertEquals(1, all.size());
        long sameNameCount = all.get(0).getItems().stream()
                .filter(i -> "手当".equals(i.getName())).count();
        assertEquals(2, sameNameCount, "同名項目2件が両方保持される（Map上書き禁止）");
    }

    @Test
    @DisplayName("未対応・BP変更済み・削除済み・別company linkは明細から除外（AC08/R04-5）")
    void 対応付けfilterが機能する() throws Exception {
        seedConnection();
        // link: 501→engineer7（正社員）, 502→engineer8（BPへ変更済み）, 503→engineer9（削除済み=リスト外）, 504→engineer10（別company）
        List<FreeeEmployeeLink> links = new ArrayList<>();
        links.add(link(7L, "501", 123L));
        links.add(link(8L, "502", 123L));
        links.add(link(9L, "503", 123L));
        links.add(link(10L, "504", 456L));
        links.add(link(11L, "505", null)); // legacy NULL
        when(linkMapper.selectList(any())).thenReturn(links);
        // 削除済みengineer9はselectListに含まれない
        seedEngineers(engineer(7L, "テスト要員7", "正社員"),
                engineer(8L, "テスト要員8", "BP"),
                engineer(10L, "テスト要員10", "正社員"));

        String body = "{\"employee_payroll_statements\":["
                + "{\"id\":9001,\"company_id\":123,\"employee_id\":501,\"employee_num\":\"E-501\","
                + "\"fixed\":true,\"calc_status\":\"calculated\","
                + "\"gross_payment_amount\":\"100\",\"total_deduction_amount\":\"0\",\"net_payment_amount\":\"100\","
                + "\"payments\":[],\"deductions\":[],\"deductions_employer_share\":[]},"
                + "{\"id\":9002,\"company_id\":123,\"employee_id\":502,\"employee_num\":\"E-502\","
                + "\"fixed\":true,\"calc_status\":\"calculated\","
                + "\"gross_payment_amount\":\"200\",\"total_deduction_amount\":\"0\",\"net_payment_amount\":\"200\","
                + "\"payments\":[],\"deductions\":[],\"deductions_employer_share\":[]},"
                + "{\"id\":9003,\"company_id\":123,\"employee_id\":503,\"employee_num\":\"E-503\","
                + "\"fixed\":true,\"calc_status\":\"calculated\","
                + "\"gross_payment_amount\":\"300\",\"total_deduction_amount\":\"0\",\"net_payment_amount\":\"300\","
                + "\"payments\":[],\"deductions\":[],\"deductions_employer_share\":[]},"
                + "{\"id\":9004,\"company_id\":123,\"employee_id\":504,\"employee_num\":\"E-504\","
                + "\"fixed\":true,\"calc_status\":\"calculated\","
                + "\"gross_payment_amount\":\"400\",\"total_deduction_amount\":\"0\",\"net_payment_amount\":\"400\","
                + "\"payments\":[],\"deductions\":[],\"deductions_employer_share\":[]},"
                + "{\"id\":9005,\"company_id\":123,\"employee_id\":505,\"employee_num\":\"E-505\","
                + "\"fixed\":true,\"calc_status\":\"calculated\","
                + "\"gross_payment_amount\":\"500\",\"total_deduction_amount\":\"0\",\"net_payment_amount\":\"500\","
                + "\"payments\":[],\"deductions\":[],\"deductions_employer_share\":[]},"
                + "{\"id\":9006,\"company_id\":123,\"employee_id\":999,\"employee_num\":\"E-999\","
                + "\"fixed\":true,\"calc_status\":\"calculated\","
                + "\"gross_payment_amount\":\"600\",\"total_deduction_amount\":\"0\",\"net_payment_amount\":\"600\","
                + "\"payments\":[],\"deductions\":[],\"deductions_employer_share\":[]}],\"total_count\":6}";
        expectSalary(body);

        List<PayrollStatementDto> all = service.statements(2026, 7, "salary");
        // 501のみ有効（502=BP変更、503=削除、504=別company、505=legacy NULL、999=未対応）
        assertEquals(1, all.size());
        assertEquals("501", all.get(0).getEmployeeId());
    }

    private FreeeEmployeeLink link(Long engineerId, String employeeId, Long companyId) {
        FreeeEmployeeLink l = new FreeeEmployeeLink();
        l.setEngineerId(engineerId);
        l.setFreeeEmployeeId(employeeId);
        l.setFreeeCompanyId(companyId);
        return l;
    }

    @Test
    @DisplayName("返却順は内部要員氏名、employee IDの安定sort（design §10.2）")
    void 安定sortされる() throws Exception {
        seedConnection();
        seedLinks();
        seedEngineers(engineer(7L, "田中", "正社員"), engineer(8L, "安藤", "正社員"));
        String body = "{\"employee_payroll_statements\":["
                + "{\"id\":9001,\"company_id\":123,\"employee_id\":501,\"employee_num\":\"E-501\","
                + "\"fixed\":true,\"calc_status\":\"calculated\","
                + "\"gross_payment_amount\":\"100\",\"total_deduction_amount\":\"0\",\"net_payment_amount\":\"100\","
                + "\"payments\":[],\"deductions\":[],\"deductions_employer_share\":[]},"
                + "{\"id\":9002,\"company_id\":123,\"employee_id\":502,\"employee_num\":\"E-502\","
                + "\"fixed\":true,\"calc_status\":\"calculated\","
                + "\"gross_payment_amount\":\"200\",\"total_deduction_amount\":\"0\",\"net_payment_amount\":\"200\","
                + "\"payments\":[],\"deductions\":[],\"deductions_employer_share\":[]}],\"total_count\":2}";
        expectSalary(body);

        List<PayrollStatementDto> all = service.statements(2026, 7, "salary");
        assertEquals(2, all.size());
        // 安藤(502) < 田中(501) の氏名順
        assertEquals("安藤", all.get(0).getEngineerName());
        assertEquals("田中", all.get(1).getEngineerName());
    }

    @Test
    @DisplayName("response JSONにraw provider objectや不要PIIが混入しない（privacy）")
    void responseにrawProviderやPIIを含まない() throws Exception {
        seedConnection();
        seedLinks();
        seedEngineers(engineer(7L, "テスト要員7", "正社員"));
        expectSalary(fixture("salary-calculated.json"));

        List<PayrollStatementDto> all = service.statements(2026, 7, "salary");
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(all);
        for (String forbidden : new String[]{"employee_payroll_statements", "payments", "gross_payment_amount",
                "bank", "address", "family", "birth", "dependent", "email", "token"}) {
            assertTrue(!json.contains(forbidden),
                    "responseへraw provider object/不要PIIが混入: " + forbidden);
        }
    }
}
