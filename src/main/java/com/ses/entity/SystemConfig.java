package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * システム設定エンティティ（m_system_config）。
 * 主キーは文字列の config_key。BaseEntityは継承しない（ID・論理削除・日時列を持たない）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("m_system_config")
public class SystemConfig {

    @TableId(value = "config_key", type = IdType.INPUT)
    private String configKey;

    private String configValue;

    private String description;
}
