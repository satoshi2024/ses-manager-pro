package com.ses.service.skillsheet;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerCareer;
import com.ses.service.EngineerCareerService;
import com.ses.service.EngineerService;
import com.ses.service.EngineerSkillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.ses.common.util.PdfFontUtils;
import com.lowagie.text.pdf.BaseFont;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillSheetGeneratorTest {

    @Mock
    private EngineerService engineerService;

    @Mock
    private EngineerSkillService engineerSkillService;

    @Mock
    private EngineerCareerService engineerCareerService;

    @Mock
    private PdfFontUtils pdfFontUtils;

    @InjectMocks
    private SkillSheetGenerator skillSheetGenerator;

    private Engineer mockEngineer;

    @BeforeEach
    void setUp() {
        mockEngineer = new Engineer();
        mockEngineer.setId(1L);
        mockEngineer.setFullName("テスト 太郎");
        mockEngineer.setNearestStation("品川");
        mockEngineer.setAvailableDate(LocalDate.of(2024, 4, 1));
        
        when(engineerService.getById(1L)).thenReturn(mockEngineer);
    }

    @Test
    void testGeneratePdf_WithZeroCareers_ShouldGeneratePdfBytes() {
        // Arrange (経歴0件、スキル0件)
        try {
            when(pdfFontUtils.resolveCjkFont()).thenReturn(BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED));
        } catch (Exception e) {}
        when(engineerSkillService.listDetail(1L)).thenReturn(Collections.emptyList());
        when(engineerCareerService.list((Wrapper<EngineerCareer>) any())).thenReturn(Collections.emptyList());

        // Act
        byte[] pdfBytes = skillSheetGenerator.generatePdf(1L);

        // Assert
        assertThat(pdfBytes).isNotNull();
        assertThat(pdfBytes.length).isGreaterThan(0);
        
        // PDFのシグネチャ "%PDF-" を確認
        String header = new String(pdfBytes, 0, 5);
        assertThat(header).isEqualTo("%PDF-");
    }

    @Test
    void testGeneratePdf_WithData_ShouldGeneratePdfBytes() {
        // Arrange
        try {
            when(pdfFontUtils.resolveCjkFont()).thenReturn(BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED));
        } catch (Exception e) {}
        EngineerSkillDetailDto skill = new EngineerSkillDetailDto();
        skill.setSkillName("Java");
        skill.setProficiency("上級");
        skill.setExperienceYears(3);

        EngineerCareer career = new EngineerCareer();
        career.setPeriodFrom(LocalDate.of(2020, 1, 1));
        career.setPeriodTo(LocalDate.of(2022, 12, 31));
        career.setClientIndustry("IT");
        career.setRole("SE");

        when(engineerSkillService.listDetail(1L)).thenReturn(List.of(skill));
        when(engineerCareerService.list((Wrapper<EngineerCareer>) any())).thenReturn(List.of(career));

        // Act
        byte[] pdfBytes = skillSheetGenerator.generatePdf(1L);

        // Assert
        assertThat(pdfBytes).isNotNull();
        assertThat(pdfBytes.length).isGreaterThan(0);
        String header = new String(pdfBytes, 0, 5);
        assertThat(header).isEqualTo("%PDF-");
    }

    @Test
    void testGenerateExcel_ShouldGenerateValidExcelAndContainSkill() throws Exception {
        // Arrange
        EngineerSkillDetailDto skill = new EngineerSkillDetailDto();
        skill.setSkillName("Spring Boot");
        skill.setProficiency("中級");
        skill.setExperienceYears(2);

        when(engineerSkillService.listDetail(1L)).thenReturn(List.of(skill));
        when(engineerCareerService.list((Wrapper<EngineerCareer>) any())).thenReturn(Collections.emptyList());

        // Act
        byte[] excelBytes = skillSheetGenerator.generateExcel(1L);

        // Assert
        assertThat(excelBytes).isNotNull();
        assertThat(excelBytes.length).isGreaterThan(0);

        // POIで再読み込みして検証
        try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(excelBytes);
             org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(bis)) {
            
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheet("スキルシート");
            assertThat(sheet).isNotNull();
            
            // 行数等の検証 (氏名行, タイトル行, などを経てスキル一覧行を探す)
            boolean foundSkill = false;
            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                org.apache.poi.ss.usermodel.Cell cell = row.getCell(0);
                if (cell != null && "Spring Boot".equals(cell.getStringCellValue())) {
                    foundSkill = true;
                    assertThat(row.getCell(1).getStringCellValue()).isEqualTo("中級");
                    assertThat(row.getCell(2).getStringCellValue()).isEqualTo("2年");
                    break;
                }
            }
            assertThat(foundSkill).isTrue();
        }
    }
}
