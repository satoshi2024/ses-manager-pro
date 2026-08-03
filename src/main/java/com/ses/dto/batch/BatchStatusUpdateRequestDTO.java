package com.ses.dto.batch;

import lombok.Data;

import java.util.List;

@Data
public class BatchStatusUpdateRequestDTO {
    private List<Long> ids;
    private String status;
}
