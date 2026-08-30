package com.ses.entity.integrationhub;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** NF-05 client scope × operation permission × data scope binding。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("m_api_client_scope")
public class ApiClientScope {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long apiClientId;
    private String scopeCode;
    private String operationCode;
    private String dataScopeJson;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Integer version;
}
