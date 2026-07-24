package com.ses.service.compliance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.compliance.ComplianceFinding;
import com.ses.dto.compliance.ContractComplianceDto;
import com.ses.dto.compliance.ContractTierDto;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LaborComplianceServiceImplTest {

    @Mock
    private ContractMapper contractMapper;
    @Mock
    private BpPaymentMapper bpPaymentMapper;
    @Mock
    private EngineerMapper engineerMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private SystemConfigService systemConfigService;
    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private LaborComplianceServiceImpl service;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.JAPANESE);
        // ルールは既定で全て有効・段数上限は3
        lenient().when(systemConfigService.getString(anyString(), eq("true"))).thenReturn("true");
        lenient().when(systemConfigService.getInt(eq("compliance.max-tier"), eq(3))).thenReturn(3);
        lenient().when(messageSource.getMessage(any(), any(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    private Contract contract(Long id, String type) {
        Contract c = new Contract();
        c.setId(id);
        c.setContractType(type);
        return c;
    }

    @Test
    void check_段数超過なら該当する() {
        Contract c = contract(1L, "準委任");
        when(bpPaymentMapper.selectMaxLayerOrderByContractId(1L)).thenReturn(4);

        List<ComplianceFinding> findings = service.check(c);

        assertThat(findings).extracting(ComplianceFinding::getCode).contains("TIER_EXCEEDED");
    }

    @Test
    void check_段数が上限以内なら非該当() {
        Contract c = contract(1L, "準委任");
        when(bpPaymentMapper.selectMaxLayerOrderByContractId(1L)).thenReturn(3);

        List<ComplianceFinding> findings = service.check(c);

        assertThat(findings).extracting(ComplianceFinding::getCode).doesNotContain("TIER_EXCEEDED");
    }

    @Test
    void check_準委任で指揮命令フラグがtrueなら偽装請負兆候に該当する() {
        Contract c = contract(1L, "準委任");
        c.setDirectCommandFlag(true);
        when(bpPaymentMapper.selectMaxLayerOrderByContractId(1L)).thenReturn(0);

        List<ComplianceFinding> findings = service.check(c);

        assertThat(findings).extracting(ComplianceFinding::getCode).contains("DIRECT_COMMAND");
    }

    @Test
    void check_請負で指揮命令フラグがfalseなら非該当() {
        Contract c = contract(1L, "請負");
        c.setDirectCommandFlag(false);
        when(bpPaymentMapper.selectMaxLayerOrderByContractId(1L)).thenReturn(0);

        List<ComplianceFinding> findings = service.check(c);

        assertThat(findings).extracting(ComplianceFinding::getCode).doesNotContain("DIRECT_COMMAND");
    }

    @Test
    void check_派遣で階層が2以上なら二重派遣兆候に該当する() {
        Contract c = contract(1L, "派遣");
        when(bpPaymentMapper.selectMaxLayerOrderByContractId(1L)).thenReturn(2);

        List<ComplianceFinding> findings = service.check(c);

        assertThat(findings).extracting(ComplianceFinding::getCode).contains("DOUBLE_DISPATCH");
    }

    @Test
    void check_派遣で階層が1なら二重派遣は非該当() {
        Contract c = contract(1L, "派遣");
        when(bpPaymentMapper.selectMaxLayerOrderByContractId(1L)).thenReturn(1);

        List<ComplianceFinding> findings = service.check(c);

        assertThat(findings).extracting(ComplianceFinding::getCode).doesNotContain("DOUBLE_DISPATCH");
    }

    @Test
    void check_請負で精算時間が設定されていれば実態不整合に該当する() {
        Contract c = contract(1L, "請負");
        c.setSettlementHoursMin(new BigDecimal("140"));
        when(bpPaymentMapper.selectMaxLayerOrderByContractId(1L)).thenReturn(0);

        List<ComplianceFinding> findings = service.check(c);

        assertThat(findings).extracting(ComplianceFinding::getCode).contains("SETTLEMENT_MISMATCH");
    }

    @Test
    void check_ルールが設定で無効化されていれば該当しない() {
        Contract c = contract(1L, "準委任");
        when(bpPaymentMapper.selectMaxLayerOrderByContractId(1L)).thenReturn(4);
        when(systemConfigService.getString(eq("compliance.rule.tier-exceeded.enabled"), eq("true"))).thenReturn("false");

        List<ComplianceFinding> findings = service.check(c);

        assertThat(findings).extracting(ComplianceFinding::getCode).doesNotContain("TIER_EXCEEDED");
    }

    @Test
    void check_契約IDがnullなら空リストを返す() {
        Contract c = new Contract();

        List<ComplianceFinding> findings = service.check(c);

        assertThat(findings).isEmpty();
    }

    @Test
    void findCurrentRisks_該当契約のみ名前付きで返す() {
        Contract c1 = contract(1L, "派遣");
        c1.setContractNo("C-001");
        c1.setEngineerId(10L);
        c1.setProjectId(20L);
        Contract c2 = contract(2L, "準委任");
        c2.setContractNo("C-002");

        when(contractMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(c1, c2));
        ContractTierDto tier1 = new ContractTierDto();
        tier1.setContractId(1L);
        tier1.setMaxLayer(2);
        when(bpPaymentMapper.selectMaxLayerOrderGroupedByContract()).thenReturn(List.of(tier1));

        Engineer engineer = new Engineer();
        engineer.setId(10L);
        engineer.setFullName("山田太郎");
        when(engineerMapper.selectBatchIds(List.of(10L))).thenReturn(List.of(engineer));

        Project project = new Project();
        project.setId(20L);
        project.setProjectName("案件A");
        when(projectMapper.selectBatchIds(List.of(20L))).thenReturn(List.of(project));

        List<ContractComplianceDto> risks = service.findCurrentRisks();

        assertThat(risks).hasSize(1);
        ContractComplianceDto dto = risks.get(0);
        assertThat(dto.getContractNo()).isEqualTo("C-001");
        assertThat(dto.getEngineerName()).isEqualTo("山田太郎");
        assertThat(dto.getProjectName()).isEqualTo("案件A");
        assertThat(dto.getFindings()).extracting(ComplianceFinding::getCode).contains("DOUBLE_DISPATCH");
    }
}
