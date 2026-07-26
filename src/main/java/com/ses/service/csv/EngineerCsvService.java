package com.ses.service.csv;

import com.ses.dto.csv.CsvImportResultDto;
import com.ses.entity.Engineer;

import java.io.InputStream;
import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * 要員CSV入出力サービス。
 */
public interface EngineerCsvService {

    /** 要員一覧をCSV（UTF-8 BOM付き）にエクスポートする。 */
    byte[] exportCsv(List<Engineer> engineers);

    /** 要員一覧を指定されたWriterへ直接書き込む（大量出力用）。 */
    void writeCsv(List<Engineer> engineers, Writer writer) throws IOException;

    void writeCsvHeader(Writer writer) throws IOException;

    void writeCsvRows(List<Engineer> engineers, Writer writer) throws IOException;

    /** CSVから要員をインポートする。行単位で検証し、失敗行はスキップして成功行は取り込む。 */
    CsvImportResultDto importCsv(InputStream in);
}
