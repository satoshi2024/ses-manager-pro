package com.ses.service.compliance;

import com.ses.dto.compliance.ComplianceFinding;
import com.ses.entity.Contract;
import com.ses.entity.ContractComplianceProfile;
import com.ses.entity.DocumentDelivery;
import com.ses.entity.WorkRecord;
import com.ses.entity.WorkRecordDaily;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.ContractComplianceProfileMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.DocumentDeliveryMapper;
import com.ses.mapper.WorkRecordDailyMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.mapper.WorkplaceMapper;
import com.ses.entity.Workplace;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * F2: 新ruleのcode別境界（trigger/非trigger）を検証する。
 * 既存4 ruleのgolden fixtureはLaborComplianceServiceImplTestが維持する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComplianceRuleEngineTest {

    @Mock
    private ContractMapper contractMapper;
    @Mock
    private BpPaymentMapper bpPaymentMapper;
    @Mock
    private ContractComplianceProfileMapper profileMapper;
    @Mock
    private DocumentDeliveryMapper deliveryMapper;
    @Mock
    private WorkRecordMapper workRecordMapper;
    @Mock
    private WorkRecordDailyMapper workRecordDailyMapper;
    @Mock
    private WorkplaceMapper workplaceMapper;
    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private MessageSource messageSource;
    @Mock
    private LimitationDateCalculator limitationDateCalculator;

    @InjectMocks
    private ComplianceRuleEngine engine;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.JAPANESE);
        lenient().when(systemConfigService.getString(anyString(), eq("true"))).thenReturn("true");
        lenient().when(systemConfigService.getInt(anyString(), eq(30))).thenReturn(30);
        lenient().when(systemConfigService.getInt(anyString(), eq(36))).thenReturn(36);
        lenient().when(systemConfigService.getInt(anyString(), eq(12))).thenReturn(12);
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any())).thenReturn("msg");
        lenient().when(bpPaymentMapper.selectMaxLayerOrderByContractId(1L)).thenReturn(0);
        lenient().when(workRecordMapper.selectList(any())).thenReturn(List.of());
        lenient().when(workRecordDailyMapper.selectList(any())).thenReturn(List.of());
        lenient().when(contractMapper.selectList(any())).thenReturn(List.of());
        Workplace workplace = new Workplace();
        workplace.setId(10L);
        workplace.setOrganizationUnit("開発部");
        lenient().when(workplaceMapper.selectById(10L)).thenReturn(workplace);
        lenient().when(limitationDateCalculator.compute(any(), any(), any(), any()))
                .thenReturn(new LimitationDateCalculator.LimitationDates(null, null));
    }

    private Contract contract(String type, LocalDate start, LocalDate end) {
        Contract contract = new Contract();
        contract.setId(1L);
        contract.setEngineerId(100L);
        contract.setContractType(type);
        contract.setStartDate(start);
        contract.setEndDate(end);
        return contract;
    }

    private ContractComplianceProfile profile() {
        ContractComplianceProfile profile = new ContractComplianceProfile();
        profile.setId(1L);
        profile.setContractId(1L);
        profile.setWorkplaceId(10L);
        return profile;
    }

    @Test
    void 派遣で2種抵触日がNULLならMISSINGfindingが出て設定済みなら出ない() {
        lenient().when(profileMapper.selectOne(any())).thenReturn(profile());
        Contract contract = contract("派遣", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));

        List<ComplianceFinding> findings = engine.evaluate(contract);
        assertThat(findings).extracting(ComplianceFinding::getCode)
                .contains("MISSING_WORKPLACE_LIMITATION_DATE", "MISSING_ORGANIZATION_LIMITATION_DATE");

        ContractComplianceProfile filled = profile();
        filled.setWorkplaceLimitationDate(LocalDate.parse("2029-01-01"));
        filled.setOrganizationLimitationDate(LocalDate.parse("2027-01-01"));
        when(profileMapper.selectOne(any())).thenReturn(filled);
        assertThat(engine.evaluate(contract)).extracting(ComplianceFinding::getCode)
                .doesNotContain("MISSING_WORKPLACE_LIMITATION_DATE", "MISSING_ORGANIZATION_LIMITATION_DATE");
    }

    @Test
    void 派遣で責任者が未設定ならMISSINGfindingが出る() {
        lenient().when(profileMapper.selectOne(any())).thenReturn(profile());
        Contract contract = contract("派遣", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        assertThat(engine.evaluate(contract)).extracting(ComplianceFinding::getCode)
                .contains("MISSING_COMMAND_PERSON", "MISSING_CLIENT_RESPONSIBLE", "MISSING_DISPATCH_RESPONSIBLE");

        ContractComplianceProfile filled = profile();
        filled.setCommandPersonName("田中");
        filled.setClientResponsibleName("鈴木");
        filled.setDispatchResponsibleName("佐藤");
        when(profileMapper.selectOne(any())).thenReturn(filled);
        assertThat(engine.evaluate(contract)).extracting(ComplianceFinding::getCode)
                .doesNotContain("MISSING_COMMAND_PERSON", "MISSING_CLIENT_RESPONSIBLE", "MISSING_DISPATCH_RESPONSIBLE");
    }

    @Test
    void 派遣で保険3種が未確認ならMISSING_INSURANCE_CONFIRMATIONがfingerprint別に出る() {
        lenient().when(profileMapper.selectOne(any())).thenReturn(profile());
        Contract contract = contract("派遣", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        List<ComplianceFinding> findings = engine.evaluate(contract).stream()
                .filter(f -> "MISSING_INSURANCE_CONFIRMATION".equals(f.getCode())).toList();
        assertThat(findings).hasSize(3);
        assertThat(findings).extracting(ComplianceFinding::getConditionFingerprint)
                .containsExactlyInAnyOrder("HEALTH", "PENSION", "EMPLOYMENT");
    }

    @Test
    void 派遣で明示書と通知書の交付記録が無ければMISSING_DOCUMENT_DELIVERYが出て有れば出ない() {
        lenient().when(profileMapper.selectOne(any())).thenReturn(profile());
        lenient().when(deliveryMapper.selectList(any())).thenReturn(List.of());
        Contract contract = contract("派遣", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        assertThat(engine.evaluate(contract)).extracting(ComplianceFinding::getCode)
                .contains("MISSING_DOCUMENT_DELIVERY");

        DocumentDelivery delivered = new DocumentDelivery();
        delivered.setDocumentType("EMPLOYMENT_CONDITIONS_STATEMENT");
        delivered.setDeliveryStatus("DELIVERED");
        DocumentDelivery notice = new DocumentDelivery();
        notice.setDocumentType("DISPATCH_NOTICE");
        notice.setDeliveryStatus("DELIVERED");
        when(deliveryMapper.selectList(any())).thenReturn(List.of(delivered, notice));
        assertThat(engine.evaluate(contract)).extracting(ComplianceFinding::getCode)
                .doesNotContain("MISSING_DOCUMENT_DELIVERY");
    }

    @Test
    void 準委任と請負で指示経路が未設定ならMISSING_INSTRUCTION_ROUTEが出て設定済みなら出ない() {
        lenient().when(profileMapper.selectOne(any())).thenReturn(profile());
        Contract junin = contract("準委任", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        assertThat(engine.evaluate(junin)).extracting(ComplianceFinding::getCode)
                .contains("MISSING_INSTRUCTION_ROUTE");
        Contract sekyu = contract("請負", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        assertThat(engine.evaluate(sekyu)).extracting(ComplianceFinding::getCode)
                .contains("MISSING_INSTRUCTION_ROUTE");

        ContractComplianceProfile filled = profile();
        filled.setInstructionRoute("PM経由で指示、勤怠承認者は社内管理者");
        when(profileMapper.selectOne(any())).thenReturn(filled);
        assertThat(engine.evaluate(junin)).extracting(ComplianceFinding::getCode)
                .doesNotContain("MISSING_INSTRUCTION_ROUTE");
    }

    @Test
    void 契約期間外の稼動記録があればRISK_WORK_OUTSIDE_PERIODが出る() {
        lenient().when(profileMapper.selectOne(any())).thenReturn(profile());
        Contract contract = contract("派遣", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));

        WorkRecord record = new WorkRecord();
        record.setId(5L);
        record.setContractId(1L);
        WorkRecordDaily inside = new WorkRecordDaily();
        inside.setId(51L);
        inside.setWorkRecordId(5L);
        inside.setWorkDate(LocalDate.parse("2026-06-01"));
        WorkRecordDaily outside = new WorkRecordDaily();
        outside.setId(52L);
        outside.setWorkRecordId(5L);
        outside.setWorkDate(LocalDate.parse("2025-12-15"));
        when(workRecordMapper.selectList(any())).thenReturn(List.of(record));
        when(workRecordDailyMapper.selectList(any())).thenReturn(List.of(inside, outside));

        List<ComplianceFinding> findings = engine.evaluate(contract).stream()
                .filter(f -> "RISK_WORK_OUTSIDE_PERIOD".equals(f.getCode())).toList();
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getConditionFingerprint()).isEqualTo("WR:52");
    }

    @Test
    void profileが未作成なら全field未入力としてMISSING系ruleが全件検知し既存4ruleも動く() {
        lenient().when(profileMapper.selectOne(any())).thenReturn(null);
        Contract contract = contract("派遣", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        List<ComplianceFinding> findings = engine.evaluate(contract);
        assertThat(findings).extracting(ComplianceFinding::getCode)
                .contains("MISSING_WORKPLACE_LIMITATION_DATE", "MISSING_ORGANIZATION_LIMITATION_DATE",
                        "MISSING_COMMAND_PERSON", "MISSING_CLIENT_RESPONSIBLE", "MISSING_DISPATCH_RESPONSIBLE",
                        "MISSING_INSURANCE_CONFIRMATION", "MISSING_DOCUMENT_DELIVERY");
        assertThat(findings).filteredOn(f -> "MISSING_INSURANCE_CONFIRMATION".equals(f.getCode())).hasSize(3);
        assertThat(findings).filteredOn(f -> "MISSING_DOCUMENT_DELIVERY".equals(f.getCode())).hasSize(2);
    }

    @Test
    void 準委任でprofile未作成なら指示経路MISSINGが出る() {
        lenient().when(profileMapper.selectOne(any())).thenReturn(null);
        Contract contract = contract("準委任", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        assertThat(engine.evaluate(contract)).extracting(ComplianceFinding::getCode)
                .contains("MISSING_INSTRUCTION_ROUTE");
    }

    @Test
    void 交付期限超過でDEADLINE系findingが発火し期限前は発火しない() {
        // 派遣開始日 2026-01-01。profileに派遣期間を設定
        ContractComplianceProfile p = profile();
        p.setDispatchPeriodStart(LocalDate.parse("2026-01-01"));
        p.setDispatchPeriodEnd(LocalDate.parse("2026-12-31"));
        lenient().when(profileMapper.selectOne(any())).thenReturn(p);
        // 通知書猶予日数は既定3日（compliance.delivery.notice-grace-days）
        lenient().when(systemConfigService.getInt("compliance.delivery.notice-grace-days", 3)).thenReturn(3);

        Contract contract = contract("派遣", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        // 今日は2026-08-10: 明示書期限（2025-12-31）・通知書期限（2026-01-04）とも超過 → 発火
        List<ComplianceFinding> findings = engine.evaluate(contract);
        assertThat(findings).extracting(ComplianceFinding::getCode)
                .contains("DEADLINE_DOCUMENT_DELIVERY", "DEADLINE_DISPATCH_NOTICE");

        // 交付記録があれば発火しない（FAILED以外）
        DocumentDelivery delivered = new DocumentDelivery();
        delivered.setDocumentType("EMPLOYMENT_CONDITIONS_STATEMENT");
        delivered.setDeliveryStatus("DELIVERED");
        DocumentDelivery notice = new DocumentDelivery();
        notice.setDocumentType("DISPATCH_NOTICE");
        notice.setDeliveryStatus("DELIVERED");
        when(deliveryMapper.selectList(any())).thenReturn(List.of(delivered, notice));
        assertThat(engine.evaluate(contract)).extracting(ComplianceFinding::getCode)
                .doesNotContain("DEADLINE_DOCUMENT_DELIVERY", "DEADLINE_DISPATCH_NOTICE");
    }

    @Test
    void 派遣開始日未設定なら交付期限ruleは発火しない() {
        lenient().when(profileMapper.selectOne(any())).thenReturn(profile());
        Contract contract = contract("派遣", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        assertThat(engine.evaluate(contract)).extracting(ComplianceFinding::getCode)
                .doesNotContain("DEADLINE_DOCUMENT_DELIVERY", "DEADLINE_DISPATCH_NOTICE");
    }
}
