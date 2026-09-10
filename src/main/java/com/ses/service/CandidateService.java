package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.candidate.CandidateEngineerInitialDto;
import com.ses.entity.Candidate;
import com.ses.entity.CandidateActivity;

import java.time.LocalDate;
import java.util.List;

/**
 * 候補者サービスインターフェース
 */
public interface CandidateService extends IService<Candidate> {

    Page<Candidate> pageForCurrentTenant(Page<Candidate> page, String name, String stage, String skillKeyword);

    List<Candidate> overdueForCurrentTenant(LocalDate today);

    Candidate getForCurrentTenant(Long candidateId);

    boolean createForCurrentTenant(Candidate candidate);

    boolean updateForCurrentTenant(Long candidateId, Candidate candidate, Integer expectedVersion);

    boolean deleteForCurrentTenant(Long candidateId, Integer expectedVersion);

    List<CandidateActivity> getActivitiesForCurrentTenant(Long candidateId);

    CandidateEngineerInitialDto getEngineerInitialDtoForCurrentTenant(Long candidateId);

    /**
     * 候補者のステージを変更し、変更履歴({@code t_candidate_activity})を記録する。
     * {@code t_candidate.currentStage}は同一トランザクションで同期更新される。
     * ステージが「不採用」または「内定辞退」の場合、reasonは必須。
     *
     * @param candidateId 候補者ID
     * @param newStage    変更後ステージ
     * @param reason      理由(不採用/内定辞退時は必須)
     * @param remarks     備考
     */
    void changeStage(Long candidateId, String newStage, String reason, String remarks);

    void changeStage(Long candidateId, String newStage, String reason, String remarks, Integer expectedVersion);

    /**
     * 候補者のステージ変更履歴を変更日時降順で取得する。
     *
     * @param candidateId 候補者ID
     * @return 履歴一覧
     */
    List<CandidateActivity> getActivities(Long candidateId);

    /**
     * 入社→エンジニア変換用の初期値DTOを取得する(自動保存はしない)。
     *
     * @param candidateId 候補者ID
     * @return 初期値DTO
     */
    CandidateEngineerInitialDto getEngineerInitialDto(Long candidateId);

    /**
     * エンジニア新規作成完了後、候補者にconvertedEngineerIdを紐付ける。
     * 候補者レコード自体は削除せず、採用実績のトレーサビリティとして残す。
     *
     * @param candidateId 候補者ID
     * @param engineerId  変換後のt_engineer.id
     */
    void linkConvertedEngineer(Long candidateId, Long engineerId);

    void linkConvertedEngineer(Long candidateId, Long engineerId, Integer expectedVersion);
}
