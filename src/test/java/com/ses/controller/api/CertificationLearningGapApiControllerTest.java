package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.dto.certificationlearninggap.CertificationLearningGapFilter;
import com.ses.dto.certificationlearninggap.CertificationLearningGapRow;
import com.ses.service.certificationlearninggap.CertificationLearningGapQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A1のlist/detail/count/exportが同一query serviceへ委譲されることを固定する。 */
class CertificationLearningGapApiControllerTest {

    private final CertificationLearningGapQueryService queryService = mock(CertificationLearningGapQueryService.class);
    private final CertificationLearningGapApiController controller = new CertificationLearningGapApiController(queryService);
    private final Authentication authentication = new TestingAuthenticationToken("100", "n", "ROLE_HR");

    @Test
    void listDetailCountExportは同じservice境界を使いexportに番号を含めない() {
        CertificationLearningGapRow row = new CertificationLearningGapRow(1L, "対象 一郎", "稼動中", "ACTIVE",
                List.of(), List.of(), "OK", null, null, List.of());
        Page<CertificationLearningGapRow> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(row));
        when(queryService.page(any(CertificationLearningGapFilter.class), eq(1L), eq(10L), eq(authentication)))
                .thenReturn(page);
        when(queryService.count(any(CertificationLearningGapFilter.class), eq(authentication))).thenReturn(1L);
        when(queryService.detail(eq(1L), any(CertificationLearningGapFilter.class), eq(authentication))).thenReturn(row);
        when(queryService.export(any(CertificationLearningGapFilter.class), eq(authentication))).thenReturn(List.of(row));

        ApiResult<Page<CertificationLearningGapRow>> list = controller.page(1, 10, null, null, null, null,
                null, null, null, null, authentication);
        ApiResult<Long> count = controller.count(null, null, null, null, null, null, null, null, authentication);
        ApiResult<CertificationLearningGapRow> detail = controller.detail(1L, null, null, null, null, null,
                null, null, authentication);
        ResponseEntity<byte[]> exported = controller.export(null, null, null, null, null, null, null, null,
                authentication);

        assertEquals(200, list.getCode());
        assertEquals(List.of(1L), list.getData().getRecords().stream().map(CertificationLearningGapRow::engineerId).toList());
        assertEquals(1L, count.getData());
        assertEquals(1L, detail.getData().engineerId());
        String csv = new String(exported.getBody(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("engineerId,engineerName"));
        assertFalse(csv.contains("certificateNumber"));
        verify(queryService).page(any(CertificationLearningGapFilter.class), eq(1L), eq(10L), eq(authentication));
        verify(queryService).count(any(CertificationLearningGapFilter.class), eq(authentication));
        verify(queryService).detail(eq(1L), any(CertificationLearningGapFilter.class), eq(authentication));
        verify(queryService).export(any(CertificationLearningGapFilter.class), eq(authentication));
    }
}
