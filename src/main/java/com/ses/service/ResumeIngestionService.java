package com.ses.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.resume.ReviewedResumeDto;
import com.ses.entity.ResumeIngestion;
import org.springframework.web.multipart.MultipartFile;

/**
 * スキルシート取込サービスインターフェース。
 */
public interface ResumeIngestionService extends IService<ResumeIngestion> {

    /**
     * 取込ジョブを作成し、非同期解析を開始する。
     */
    ResumeIngestion createJob(MultipartFile file, Long candidateId);

    /**
     * 非同期でテキスト抽出・AI解析を実行する。
     */
    void parseAsync(Long id);

    /**
     * 要確認/失敗ジョブを再解析する。
     */
    void reparse(Long id);

    /**
     * レビュー中間保存（parsed_json 更新。状態は要確認のまま）。
     */
    void saveReview(Long id, ReviewedResumeDto dto);

    /**
     * 確定：要員+スキル+経歴を一括生成する。
     *
     * @return 生成した要員ID
     * @throws com.ses.common.exception.BusinessException 409=二重確定
     */
    Long confirm(Long id, ReviewedResumeDto dto);

    /**
     * 却下：状態を「却下」へ更新する。
     */
    void reject(Long id, String reason);
}
