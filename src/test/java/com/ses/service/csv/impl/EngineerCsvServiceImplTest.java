package com.ses.service.csv.impl;

import com.ses.dto.csv.CsvImportResultDto;
import com.ses.entity.Engineer;
import com.ses.service.EngineerService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 要員CSVインポートの単体テスト（P8 Task4）。
 * 不正行はスキップし成功行のみ取り込むこと、失敗行の理由が返ることを検証する。
 */
class EngineerCsvServiceImplTest {

    private EngineerCsvServiceImpl service;
    private EngineerService engineerService;

    @BeforeEach
    void setUp() {
        engineerService = Mockito.mock(EngineerService.class);
        when(engineerService.save(any(Engineer.class))).thenReturn(true);
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        service = new EngineerCsvServiceImpl(engineerService, validator);
    }

    @Test
    void importCsv_正常行のみ取り込み不正行は理由付きでスキップ() {
        // 行2: 正常, 行3: 氏名空(バリデーション違反), 行4: 単価が数値でない
        String csv = String.join("\r\n",
                "氏名,氏名カナ,イニシャル,性別,雇用形態,ステータス,希望単価,経験年数,最寄駅,日本語レベル,備考",
                "山田太郎,ヤマダタロウ,Y.T,男性,正社員,Bench,60,5,新宿,N1,",
                ",カナ,,男性,正社員,Bench,50,3,渋谷,N2,",
                "田中花子,タナカ,T.H,女性,BP,Bench,abc,2,品川,N1,");

        CsvImportResultDto result = importCsv(csv);

        assertEquals(1, result.getSuccessCount(), "正常な1件のみ取り込まれる");
        assertEquals(2, result.getErrors().size(), "不正な2行が失敗として記録される");
        // 行3(氏名空)は3行目、行4(数値不正)は4行目
        assertEquals(3, result.getErrors().get(0).getLine());
        assertEquals(4, result.getErrors().get(1).getLine());
    }

    @Test
    void importCsv_空行はスキップされエラーにならない() {
        String csv = String.join("\r\n",
                "氏名,氏名カナ,イニシャル,性別,雇用形態,ステータス,希望単価,経験年数,最寄駅,日本語レベル,備考",
                "山田太郎,,,,,Bench,,,,,",
                ",,,,,,,,,,");

        CsvImportResultDto result = importCsv(csv);

        assertEquals(1, result.getSuccessCount());
        assertTrue(result.getErrors().isEmpty(), "完全な空行はエラーにしない");
    }

    private CsvImportResultDto importCsv(String csv) {
        try (InputStream in = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))) {
            return service.importCsv(in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
