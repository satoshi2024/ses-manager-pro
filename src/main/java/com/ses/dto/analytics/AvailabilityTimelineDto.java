package com.ses.dto.analytics;

import lombok.Data;

import java.util.List;

@Data
public class AvailabilityTimelineDto {
    private List<EngineerTimelineDto> engineers;
}
