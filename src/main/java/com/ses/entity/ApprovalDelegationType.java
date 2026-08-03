package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** 代理対象種別の正規化子行。子行0件は全種別対象を表す。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_approval_delegation_type")
public class ApprovalDelegationType implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long delegationId;
    private String requestType;
}
