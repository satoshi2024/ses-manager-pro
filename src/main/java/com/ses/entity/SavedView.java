package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 保存ビューエンティティ
 */
@Data
@TableName("m_saved_view")
public class SavedView {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;
    /**
     * 所有ユーザーID。NULLの場合は全ユーザー共有ビューを表す（業務上の意味を持つNULL）。
     */
    private Long ownerUserId;

    private String pageKey;
    private String name;
    private String filterJson;
    private String sortJson;
    private String columnsJson;
    private Integer pageSize;
    private Integer sharedFlag;

    @Version
    private Integer version;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deletedFlag;
}
