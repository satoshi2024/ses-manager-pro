package com.ses.entity.integrationhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** NF-05専用のbounded request audit。raw request/body/IP/secretは保持しない。 */
@Data
@TableName("t_external_api_audit")
public class ExternalApiAudit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String preAuthPrincipal;
    private String postAuthPrincipal;
    private String clientId;
    private Integer credentialVersion;
    private String keyId;
    private String correlationId;
    private String method;
    private String routeTemplate;
    private String authenticationDecision;
    private String scopeDecision;
    private String dataScopeDecision;
    private String commandDecision;
    private String rateDecision;
    private Integer status;
    private String resultCode;
    private Boolean successFlag;
    private LocalDateTime createdAt;
}
