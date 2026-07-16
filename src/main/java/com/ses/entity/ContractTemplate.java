package com.ses.entity;
import com.ses.common.base.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data; import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper=true) @TableName("m_contract_template")
public class ContractTemplate extends BaseEntity { private String name; private String contractType; private String htmlContent; private Integer version; private Integer activeFlag; private Long createdBy; }
