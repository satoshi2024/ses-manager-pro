package com.ses.common.util;

import com.ses.common.exception.BusinessException;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

public class DateUtils {
    
    private DateUtils() {
        // Utility class
    }

    public static YearMonth parseYearMonth(String yearMonthStr) {
        if (yearMonthStr == null || yearMonthStr.trim().isEmpty()) {
            throw BusinessException.of(400, "無効な年月形式です（yyyy-MM を指定してください）");
        }
        try {
            return YearMonth.parse(yearMonthStr);
        } catch (DateTimeParseException e) {
            throw BusinessException.of(400, "無効な年月形式です（yyyy-MM を指定してください）");
        }
    }
}
