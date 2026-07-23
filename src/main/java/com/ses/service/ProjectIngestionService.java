package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.projectingestion.ReviewedProjectDto;
import com.ses.entity.ProjectIngestion;
import org.springframework.web.multipart.MultipartFile;

/**
 * 案件メール取込サービス。
 */
public interface ProjectIngestionService extends IService<ProjectIngestion> {

    /**
     * アップロードされたファイルからジョブを作成し、非同期で解析を開始する。
     */
    ProjectIngestion createJob(MultipartFile file);

    /**
     * 貼付テキストからジョブを作成し、非同期で解析を開始する。
     */
    ProjectIngestion createJobFromPaste(String text);

    /**
     * 指定されたジョブの抽出・解析を非同期で行う（再解析用も兼ねる）。
     */
    void parseAsync(Long id);

    /**
     * 失敗/要確認状態のジョブを再解析キューに入れる。
     */
    void reparse(Long id);

    /**
     * レビュー結果を一時保存する。
     */
    void saveReview(Long id, ReviewedProjectDto dto);

    /**
     * レビュー結果を確定し、Projectエンティティを生成する。
     * @return 生成された Project の ID
     */
    Long confirm(Long id, ReviewedProjectDto dto);

    /**
     * 取込を却下する。
     */
    void reject(Long id, String reason);
}
