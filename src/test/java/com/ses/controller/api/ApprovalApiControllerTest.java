package com.ses.controller.api;

import com.ses.dto.approval.ApprovalDiffItem;
import com.ses.dto.approval.ApprovalRequestView;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.approval.ApprovalViewService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A1: exportは詳細表示と同じmask済みDTOのみをCSVへ出力する。 */
class ApprovalApiControllerTest {
    @Test
    void export_doesNotContainMaskedSensitiveValue() {
        ApprovalViewService viewService = mock(ApprovalViewService.class);
        ApprovalRequestView view = new ApprovalRequestView(1L, "AR-1", "CONTRACT", "CONTRACT", 2L, 1L,
                10L, 1L, BigDecimal.TEN, "in_review", 1, null, null, "/contract/list?id=2",
                List.of(new ApprovalDiffItem("cost", "原価", null, null, true, true)), List.of(),
                Map.of(), true, true, true, false, false);
        when(viewService.detail(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(view);
        ApprovalApiController controller = new ApprovalApiController(mock(ApprovalEngineService.class), viewService);

        ResponseEntity<byte[]> response = controller.export(1L, mock(Authentication.class));
        String csv = new String(response.getBody(), StandardCharsets.UTF_8);

        assertThat(csv).contains("cost", "true").doesNotContain("100000");
        // UTF-8 BOM + 真のCRLF（Excelが1行に潰さない）
        byte[] body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body[0] & 0xFF).isEqualTo(0xEF);
        assertThat(body[1] & 0xFF).isEqualTo(0xBB);
        assertThat(body[2] & 0xFF).isEqualTo(0xBF);
        assertThat(csv).contains("\r\n");
        assertThat(csv).doesNotContain("\\r\\n");
        assertThat(csv.chars().filter(ch -> ch == '\n').count()).isGreaterThan(1);
    }
}
