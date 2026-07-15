package com.ses.dto.analytics;

import lombok.Data;

import java.time.LocalDate;

@Data
public class TimelineBarDto {
    private LocalDate start;
    private LocalDate end;
    private String type; // "contracted" or "available"
    private Long contractId;
}
