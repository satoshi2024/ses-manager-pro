package com.ses.dto.project;

import com.ses.entity.Project;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ProjectListDto extends Project {
    private String customerName;
}
