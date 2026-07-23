package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 案件メール取込ジョブエンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_project_ingestion")
public class ProjectIngestion extends BaseEntity {

    /** PASTE / EML */
    private String sourceType;

    /** アップロード時の元ファイル名（emlの場合） */
    private String originalFileName;

    /** 保存名（UUID.eml）。/api/files で参照 */
    private String storedFileName;

    /**
     * ステータス: 取込待ち/抽出中/要確認/確定済/却下/失敗
     */
    private String status;

    /** 貼付/抽出したプレーンテキスト */
    private String rawText;

    /** AI構造化結果 + レビュー編集後の内容（JSON） */
    private String parsedJson;

    /** 解析に使ったプロバイダ */
    private String aiProvider;

    /** 解析に使ったモデル */
    private String aiModel;

    /** 失敗理由（サニタイズ済み） */
    private String errorMessage;

    /** 確定で生成した t_project.id */
    private Long convertedProjectId;

    /** レビュー担当メモ */
    private String reviewNote;

    /** 登録者ID（自動設定） */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}
