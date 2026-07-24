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
import com.ses.dto.skillsheet.SkillSheetOptions;

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

    @Mock
    private com.ses.service.SystemConfigService systemConfigService;

    @InjectMocks
    private SkillSheetGenerator skillSheetGenerator;

    private Engineer mockEngineer;

    @BeforeEach
    void setUp() {
        mockEngineer = new Engineer();
        mockEngineer.setId(1L);
        mockEngineer.setFullName("テスト 太郎");
        mockEngineer.setNearestStation("品川");
        mockEngineer.setInitialName("T.T");
        mockEngineer.setAvailableDate(LocalDate.of(2024, 4, 1));
        
        // 様式の妥当性検証はエンジニア取得より前に走るため、不正様式のテストではこのスタブを使わない
        org.mockito.Mockito.lenient().when(engineerService.getById(1L)).thenReturn(mockEngineer);

        org.springframework.test.util.ReflectionTestUtils.setField(skillSheetGenerator, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());
        org.mockito.Mockito.lenient().when(systemConfigService.getString(org.mockito.ArgumentMatchers.eq("skillsheet.templates"), org.mockito.ArgumentMatchers.anyString()))
                 .thenReturn("[{\"id\":\"STANDARD\",\"name\":\"自社標準\"},{\"id\":\"SIMPLE\",\"name\":\"簡易\"},{\"id\":\"CLIENT_A\",\"name\":\"客先A\"}]");
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
        SkillSheetOptions options = new SkillSheetOptions();
        options.setAnonymize(false);
        options.setTemplate("STANDARD");
        byte[] pdfBytes = skillSheetGenerator.generatePdf(1L, options);

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
        SkillSheetOptions options = new SkillSheetOptions();
        options.setAnonymize(false);
        options.setTemplate("STANDARD");
        byte[] pdfBytes = skillSheetGenerator.generatePdf(1L, options);

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
        SkillSheetOptions options = new SkillSheetOptions();
        options.setAnonymize(false);
        options.setTemplate("STANDARD");
        byte[] excelBytes = skillSheetGenerator.generateExcel(1L, options);

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

    @Test
    void testGeneratePdf_WithAnonymize_ShouldNotContainName() throws Exception {
        // Arrange
        try {
            when(pdfFontUtils.resolveCjkFont()).thenReturn(BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED));
        } catch (Exception e) {}
        when(engineerSkillService.listDetail(1L)).thenReturn(Collections.emptyList());
        when(engineerCareerService.list((Wrapper<EngineerCareer>) any())).thenReturn(Collections.emptyList());

        // Act
        SkillSheetOptions options = new SkillSheetOptions();
        options.setAnonymize(true);
        options.setTemplate("STANDARD");
        byte[] pdfBytes = skillSheetGenerator.generatePdf(1L, options);

        // Assert
        assertThat(pdfBytes).isNotNull();
        try (com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(pdfBytes)) {
            com.lowagie.text.pdf.parser.PdfTextExtractor extractor = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);
            String text = extractor.getTextFromPage(1);
            assertThat(text).doesNotContain("テスト 太郎");
            assertThat(text).contains("T.T");
        }
    }

    @Test
    void testGenerateExcel_WithAnonymize_ShouldMaskName() throws Exception {
        // Arrange
        when(engineerSkillService.listDetail(1L)).thenReturn(Collections.emptyList());
        when(engineerCareerService.list((Wrapper<EngineerCareer>) any())).thenReturn(Collections.emptyList());

        // Act
        SkillSheetOptions options = new SkillSheetOptions();
        options.setAnonymize(true);
        options.setTemplate("STANDARD");
        byte[] excelBytes = skillSheetGenerator.generateExcel(1L, options);

        // Assert
        try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(excelBytes);
             org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(bis)) {
            
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheet("スキルシート");
            assertThat(sheet).isNotNull();
            
            boolean foundMaskedInitial = false;
            boolean foundFullName = false;
            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                for (org.apache.poi.ss.usermodel.Cell cell : row) {
                    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                        String val = cell.getStringCellValue();
                        if (val.contains("T.T")) {
                            foundMaskedInitial = true;
                        }
                        if (val.contains("テスト 太郎")) {
                            foundFullName = true;
                        }
                    }
                }
            }
            assertThat(foundFullName).isFalse();
            assertThat(foundMaskedInitial).isTrue();
        }
    }

    @Test
    void testGenerateExcel_StandardVsSimple_ShouldHaveDifferentColumns() throws Exception {
        when(engineerSkillService.listDetail(1L)).thenReturn(Collections.emptyList());
        when(engineerCareerService.list((Wrapper<EngineerCareer>) any())).thenReturn(Collections.emptyList());

        SkillSheetOptions optionsStandard = new SkillSheetOptions();
        optionsStandard.setTemplate("STANDARD");
        byte[] standardBytes = skillSheetGenerator.generateExcel(1L, optionsStandard);

        SkillSheetOptions optionsSimple = new SkillSheetOptions();
        optionsSimple.setTemplate("SIMPLE");
        byte[] simpleBytes = skillSheetGenerator.generateExcel(1L, optionsSimple);

        try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(standardBytes);
             org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(bis)) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheet("スキルシート");
            // Career header row is index 10 (or similar), just find "■ 職務経歴" then next row is header
            int headerRowIndex = -1;
            for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(i);
                if (row != null && row.getCell(0) != null && "■ 職務経歴".equals(row.getCell(0).getStringCellValue())) {
                    headerRowIndex = i + 1;
                    break;
                }
            }
            assertThat(headerRowIndex).isGreaterThan(0);
            org.apache.poi.ss.usermodel.Row headerRow = sheet.getRow(headerRowIndex);
            assertThat(headerRow.getLastCellNum()).isEqualTo((short) 6); // 6 columns
        }

        try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(simpleBytes);
             org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(bis)) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheet("スキルシート");
            int headerRowIndex = -1;
            for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(i);
                if (row != null && row.getCell(0) != null && "■ 職務経歴".equals(row.getCell(0).getStringCellValue())) {
                    headerRowIndex = i + 1;
                    break;
                }
            }
            assertThat(headerRowIndex).isGreaterThan(0);
            org.apache.poi.ss.usermodel.Row headerRow = sheet.getRow(headerRowIndex);
            assertThat(headerRow.getLastCellNum()).isEqualTo((short) 5); // 5 columns
        }
    }

    @Test
    void testGenerateExcel_ClientA_ShouldAddRemarksAndKeepTeamSize() throws Exception {
        when(engineerSkillService.listDetail(1L)).thenReturn(Collections.emptyList());
        when(engineerCareerService.list((Wrapper<EngineerCareer>) any())).thenReturn(Collections.emptyList());

        SkillSheetOptions options = new SkillSheetOptions();
        options.setTemplate("CLIENT_A");
        byte[] bytes = skillSheetGenerator.generateExcel(1L, options);

        try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(bytes);
             org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(bis)) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheet("スキルシート");

            // 基本情報: 客先A様式は「備考」列が増える
            org.apache.poi.ss.usermodel.Row basicHeaderRow = sheet.getRow(2);
            assertThat(basicHeaderRow.getLastCellNum()).isEqualTo((short) 4);
            assertThat(basicHeaderRow.getCell(3).getStringCellValue()).isEqualTo("備考");

            // 職務経歴: 自社標準と同じく「チーム規模」を含む(6列)
            int headerRowIndex = -1;
            for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(i);
                if (row != null && row.getCell(0) != null && "■ 職務経歴".equals(row.getCell(0).getStringCellValue())) {
                    headerRowIndex = i + 1;
                    break;
                }
            }
            assertThat(headerRowIndex).isGreaterThan(0);
            assertThat(sheet.getRow(headerRowIndex).getLastCellNum()).isEqualTo((short) 6);
        }
    }

    @Test
    void testGenerateExcel_UnknownTemplate_ShouldThrowBusinessException() {
        SkillSheetOptions options = new SkillSheetOptions();
        options.setTemplate("NOT_EXIST");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> skillSheetGenerator.generateExcel(1L, options))
                .isInstanceOf(com.ses.common.exception.BusinessException.class);
    }
}
