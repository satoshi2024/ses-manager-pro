package com.ses.dto.engineer;

import com.ses.entity.Engineer;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class EngineerListDto extends Engineer {
    private Long primarySalesUserId;
    private String primarySalesUserName;
}
