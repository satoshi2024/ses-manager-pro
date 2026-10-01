package com.ses.service.certificationlearninggap;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.config.LoginUser;
import com.ses.dto.certificationlearninggap.CertificationLearningGapFilter;
import com.ses.dto.certificationlearninggap.CertificationLearningGapRow;
import com.ses.entity.Engineer;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 資格・学習gapのEngineer母集団が明示的tenant ownershipで分離されることをH2で検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CertificationLearningGapTenantOwnershipIntegrationTest {

    @Autowired
    private CertificationLearningGapQueryService queryService;

    @Autowired
    private EngineerMapper engineerMapper;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    @Test
    void fullAccessでも現在tenantのEngineerだけをlistDetailCountExportする() {
        Engineer tenantA = insertEngineer("tenant-a", "資格なしA");
        Engineer tenantB = insertEngineer("tenant-b", "資格なしB");
        Authentication authentication = authentication("tenant-a");
        SecurityContextHolder.getContext().setAuthentication(authentication);
        AccountingTenantContextHolder.setTenantId("tenant-a");

        CertificationLearningGapFilter filter = new CertificationLearningGapFilter(
                null, null, null, null, null, LocalDate.now(), null, null);
        Page<CertificationLearningGapRow> page = queryService.page(filter, 1, 100, authentication);

        assertEquals(List.of(tenantA.getId()), page.getRecords().stream()
                .map(CertificationLearningGapRow::engineerId).toList());
        assertEquals(1, queryService.count(filter, authentication));
        assertEquals(List.of(tenantA.getId()), queryService.export(filter, authentication).stream()
                .map(CertificationLearningGapRow::engineerId).toList());
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> queryService.detail(tenantB.getId(), filter, authentication));
    }

    private Engineer insertEngineer(String tenantId, String name) {
        Engineer engineer = Engineer.builder()
                .tenantId(tenantId)
                .fullName(name)
                .status("稼動中")
                .employmentType("正社員")
                .build();
        engineerMapper.insert(engineer);
        return engineer;
    }

    private Authentication authentication(String tenantId) {
        SysUser user = SysUser.builder()
                .username("nf03-admin-" + tenantId)
                .tenantId(tenantId)
                .role("管理者")
                .status(1)
                .build();
        LoginUser principal = new LoginUser(user,
                List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
