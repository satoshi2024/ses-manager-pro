package com.ses.dto.analytics;

import lombok.Data;

import java.util.List;

@Data
public class EngineerTimelineDto {
    private Long id;
    private String name;
    private List<TimelineBarDto> bars;
    private boolean endingSoon;
}
