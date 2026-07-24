package com.ses.controller.api;

import com.ses.service.skillsheet.SkillSheetGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/engineers/{id}")
@RequiredArgsConstructor
public class SkillSheetApiController {

    private final SkillSheetGenerator skillSheetGenerator;
    private final com.ses.service.security.DataScopeService dataScopeService;

    /** データスコープ発動中に担当外要員のスキルシートを秘匿する（詳細404パターン）。 */
    private void assertEngineerVisible(Long id) {
        if (dataScopeService.isScoped() && !dataScopeService.allowedEngineerIds().contains(id)) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
    }

    @GetMapping("/skill-sheet.pdf")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable Long id,
                                              @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean anonymize,
                                              @org.springframework.web.bind.annotation.RequestParam(defaultValue = "STANDARD") String template) {
        assertEngineerVisible(id);
        com.ses.dto.skillsheet.SkillSheetOptions options = new com.ses.dto.skillsheet.SkillSheetOptions(anonymize, template);
        byte[] pdfBytes = skillSheetGenerator.generatePdf(id, options);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "skill-sheet-" + id + ".pdf");

        return ResponseEntity.ok()
                .headers(headers)
                .body(pdfBytes);
    }

    @GetMapping("/skill-sheet.xlsx")
    public ResponseEntity<byte[]> downloadExcel(@PathVariable Long id,
                                                @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean anonymize,
                                                @org.springframework.web.bind.annotation.RequestParam(defaultValue = "STANDARD") String template) {
        assertEngineerVisible(id);
        com.ses.dto.skillsheet.SkillSheetOptions options = new com.ses.dto.skillsheet.SkillSheetOptions(anonymize, template);
        byte[] excelBytes = skillSheetGenerator.generateExcel(id, options);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDispositionFormData("attachment", "skill-sheet-" + id + ".xlsx");

        return ResponseEntity.ok()
                .headers(headers)
                .body(excelBytes);
    }
}
