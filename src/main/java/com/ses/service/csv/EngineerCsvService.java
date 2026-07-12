package com.ses.service.csv;

import com.ses.dto.csv.CsvImportResultDto;
import com.ses.entity.Engineer;

import java.io.InputStream;
import java.util.List;

/**
 * 要員CSV入出力サービス。
 */
public interface EngineerCsvService {

    /** 要員一覧をCSV（UTF-8 BOM付き）にエクスポートする。 */
    byte[] exportCsv(List<Engineer> engineers);

    /** CSVから要員をインポートする。行単位で検証し、失敗行はスキップして成功行は取り込む。 */
    CsvImportResultDto importCsv(InputStream in);
}
