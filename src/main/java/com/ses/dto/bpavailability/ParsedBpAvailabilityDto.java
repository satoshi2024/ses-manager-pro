package com.ses.dto.bpavailability;

import lombok.Data;
import java.util.List;

@Data
public class ParsedBpAvailabilityDto {
    private ReviewedBpAvailabilityDto availability;
    private List<String> warnings;
}
