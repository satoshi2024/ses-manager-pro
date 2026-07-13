package com.ses.mapper;

import com.ses.BaseIntegrationTest;
import com.ses.dto.WorkRecordGridDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorkRecordMapperTest extends BaseIntegrationTest {

    @Autowired
    private WorkRecordMapper workRecordMapper;

    @Test
    void testSelectMonthlyGrid() {
        // 現在の年月 (あるいはマスターデータが登録されている年月)
        // テストデータがどうなっているかによるが、一旦実行してエラーにならないかを検証
        String currentMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        List<WorkRecordGridDto> result = workRecordMapper.selectMonthlyGrid(currentMonth);

        assertNotNull(result);
        // H2データベースでのクエリ実行構文(CONCAT等)が正しく解釈され、エラーが出ないことを確認するだけでも価値がある
    }
}
