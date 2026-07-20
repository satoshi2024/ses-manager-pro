package com.ses.service.csv.impl;

import com.ses.common.i18n.EnumMappings;

import com.ses.common.util.CsvUtils;
import com.ses.dto.csv.CsvImportResultDto;
import com.ses.entity.Engineer;
import com.ses.service.EngineerService;
import com.ses.service.csv.EngineerCsvService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 要員CSV入出力サービス実装。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EngineerCsvServiceImpl implements EngineerCsvService {

    private final EngineerService engineerService;
    private final Validator validator;

    /** ヘッダー（エクスポート/インポート共通の列順） */
    static final String[] HEADER = {
            "氏名", "氏名カナ", "イニシャル", "性別", "雇用形態", "ステータス",
            "希望単価", "経験年数", "最寄駅", "日本語レベル", "備考"
    };

    @Override
    public byte[] exportCsv(List<Engineer> engineers) {
        StringBuilder sb = new StringBuilder(CsvUtils.UTF8_BOM);
        CsvUtils.appendLine(sb, HEADER);
        for (Engineer e : engineers) {
            CsvUtils.appendLine(sb,
                    nz(e.getFullName()),
                    nz(e.getFullNameKana()),
                    nz(e.getInitialName()),
                    nz(e.getGender()),
                    nz(e.getEmploymentType()),
                    nz(e.getStatus()),
                    e.getExpectedUnitPrice() != null ? e.getExpectedUnitPrice().toPlainString() : "",
                    e.getExperienceYears() != null ? String.valueOf(e.getExperienceYears()) : "",
                    nz(e.getNearestStation()),
                    nz(e.getJapaneseLevel()),
                    nz(e.getRemarks()));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public CsvImportResultDto importCsv(InputStream in) {
        CsvImportResultDto result = new CsvImportResultDto();
        List<List<String>> rows;
        try {
            rows = CsvUtils.parse(in);
        } catch (IOException e) {
            log.error("CSVの読み込みに失敗しました", e);
            result.addError(0, "CSVの読み込みに失敗しました");
            return result;
        }
        if (rows.isEmpty()) {
            result.addError(0, "CSVが空です");
            return result;
        }
        // 1行目はヘッダーとして扱う
        for (int i = 1; i < rows.size(); i++) {
            int lineNo = i + 1; // 1始まり・ヘッダー込みの行番号
            List<String> cols = rows.get(i);
            // 完全な空行はスキップ
            if (cols.stream().allMatch(s -> s == null || s.isBlank())) {
                continue;
            }
            try {
                Engineer e = toEngineer(cols);
                
                if (e.getGender() != null && !e.getGender().isBlank() && !EnumMappings.GROUPS.get("gender").containsKey(e.getGender())) {
                    result.addError(lineNo, "性別の区分値が不正です");
                    continue;
                }
                if (e.getEmploymentType() != null && !e.getEmploymentType().isBlank() && !EnumMappings.GROUPS.get("employmentType").containsKey(e.getEmploymentType())) {
                    result.addError(lineNo, "雇用形態の区分値が不正です");
                    continue;
                }
                if (e.getStatus() != null && !e.getStatus().isBlank() && !EnumMappings.GROUPS.get("engineerStatus").containsKey(e.getStatus())) {
                    result.addError(lineNo, "ステータスの区分値が不正です");
                    continue;
                }

                Set<ConstraintViolation<Engineer>> violations = validator.validate(e);
                if (!violations.isEmpty()) {
                    String msg = violations.iterator().next().getMessage();
                    result.addError(lineNo, msg);
                    continue;
                }
                engineerService.save(e);
                result.incrementSuccess();
            } catch (NumberFormatException nfe) {
                result.addError(lineNo, "数値項目の形式が不正です");
            } catch (Exception ex) {
                log.error("CSV import error at line {}", lineNo, ex);
                result.addError(lineNo, "取込に失敗しました");
            }
        }
        return result;
    }

    private Engineer toEngineer(List<String> cols) {
        Engineer e = new Engineer();
        e.setFullName(get(cols, 0));
        e.setFullNameKana(get(cols, 1));
        e.setInitialName(get(cols, 2));
        e.setGender(get(cols, 3));
        e.setEmploymentType(get(cols, 4));
        e.setStatus(get(cols, 5));
        String price = get(cols, 6);
        if (price != null && !price.isBlank()) {
            e.setExpectedUnitPrice(new BigDecimal(price.trim()));
        }
        String exp = get(cols, 7);
        if (exp != null && !exp.isBlank()) {
            e.setExperienceYears(Integer.parseInt(exp.trim()));
        }
        e.setNearestStation(get(cols, 8));
        e.setJapaneseLevel(get(cols, 9));
        e.setRemarks(get(cols, 10));
        return e;
    }

    private static String get(List<String> cols, int idx) {
        if (idx >= cols.size()) {
            return null;
        }
        String v = cols.get(idx);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}


