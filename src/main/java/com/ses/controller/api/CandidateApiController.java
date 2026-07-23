package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.constant.StatusConstants;
import com.ses.common.util.PageUtils;
import com.ses.dto.candidate.CandidateEngineerInitialDto;
import com.ses.entity.Candidate;
import com.ses.entity.CandidateActivity;
import com.ses.service.CandidateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 候補者APIコントローラー(/api/candidates)。
 *
 * 権限: m_menu/t_role_menuに候補者管理メニュー(candidate)を登録済みで、
 * 管理者/営業/HRのみアクセス可能(MenuPermissionFilterによる制御。V16マイグレーション参照)。
 */
@RestController
@RequestMapping("/api/candidates")
@RequiredArgsConstructor
public class CandidateApiController {

    private final CandidateService candidateService;

    /**
     * 候補者一覧(ページネーション・ステータス/スキルキーワード検索)
     */
    @GetMapping
    public ApiResult<Page<Candidate>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String skillKeyword) {

        // A7-11: PageUtils.safePage で size<=0 の全件取得と上限超過を防ぐ
        Page<Candidate> page = PageUtils.safePage(current, size);
        LambdaQueryWrapper<Candidate> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(name)) {
            wrapper.like(Candidate::getName, name);
        }
        if (StringUtils.hasText(stage)) {
            wrapper.eq(Candidate::getCurrentStage, stage);
        }
        if (StringUtils.hasText(skillKeyword)) {
            wrapper.like(Candidate::getSkillSummary, skillKeyword);
        }

        wrapper.orderByDesc(Candidate::getId);
        return ApiResult.success(candidateService.page(page, wrapper));
    }

    /**
     * 期限超過(次アクション予定日が過去)の候補者一覧。一覧画面の強調表示用。
     */
    @GetMapping("/overdue")
    public ApiResult<List<Candidate>> getOverdue() {
        LambdaQueryWrapper<Candidate> wrapper = new LambdaQueryWrapper<>();
        wrapper.le(Candidate::getNextActionDate, LocalDate.now())
               .notIn(Candidate::getCurrentStage, 
                       StatusConstants.CANDIDATE_STAGE_HIRED, 
                       StatusConstants.CANDIDATE_STAGE_REJECTED, 
                       StatusConstants.CANDIDATE_STAGE_OFFER_DECLINED)
               .orderByAsc(Candidate::getNextActionDate);
        return ApiResult.success(candidateService.list(wrapper));
    }

    /**
     * 候補者詳細
     */
    @GetMapping("/{id}")
    public ApiResult<Candidate> getById(@PathVariable Long id) {
        var entity = candidateService.getById(id);
        if (entity == null) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(entity);
    }

    /**
     * 候補者新規登録
     */
    @PostMapping
    public ApiResult<Boolean> save(@Valid @RequestBody com.ses.dto.candidate.CandidateSaveDto dto) {
        Candidate candidate = new Candidate();
        org.springframework.beans.BeanUtils.copyProperties(dto, candidate);
        candidate.setId(null);
        candidate.setConvertedEngineerId(null);
        candidate.setCurrentStage(null);
        com.ses.common.util.EntityProtectUtil.protectForCreate(candidate);
        return ApiResult.success(candidateService.save(candidate));
    }

    /**
     * 候補者基本情報更新(ステージ変更はこのエンドポイントでは行わない。/activities を使うこと)
     */
    @PutMapping("/{id}")
    public ApiResult<Boolean> update(@PathVariable Long id, @Valid @RequestBody com.ses.dto.candidate.CandidateSaveDto dto) {
        Candidate candidate = new Candidate();
        org.springframework.beans.BeanUtils.copyProperties(dto, candidate);
        candidate.setId(id);
        // currentStageの直接上書きを防ぐ(ステージ変更は必ずchangeStage経由にする)
        candidate.setCurrentStage(null);
        // convertedEngineerIdの直接上書きを防ぐ
        candidate.setConvertedEngineerId(null);
        boolean success = candidateService.updateById(candidate);
        if (!success) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(true);
    }

    /**
     * 候補者削除(論理削除)
     */
    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long id) {
        boolean success = candidateService.removeById(id);
        if (!success) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(true);
    }

    /**
     * ステージ変更履歴取得
     */
    @GetMapping("/{id}/activities")
    public ApiResult<List<CandidateActivity>> getActivities(@PathVariable Long id) {
        return ApiResult.success(candidateService.getActivities(id));
    }

    /**
     * ステージ変更。stageが不採用/内定辞退の場合はreasonが必須(CandidateServiceで検証)。
     */
    @PostMapping("/{id}/activities")
    public ApiResult<Boolean> changeStage(@PathVariable Long id, @RequestBody Map<String, String> request) {
        candidateService.changeStage(id, request.get("stage"), request.get("reason"), request.get("remarks"));
        return ApiResult.success(true);
    }

    /**
     * 入社→エンジニア変換用の初期値取得(自動保存はしない)。
     */
    @PostMapping("/{id}/convert-to-engineer")
    public ApiResult<CandidateEngineerInitialDto> convertToEngineer(@PathVariable Long id) {
        return ApiResult.success(candidateService.getEngineerInitialDto(id));
    }

    /**
     * エンジニア新規作成画面での「確認・補完の手動操作」完了後に呼び出され、
     * 候補者へconvertedEngineerIdを紐付ける(候補者レコードは削除しない)。
     */
    @PutMapping("/{id}/converted-engineer")
    public ApiResult<Boolean> linkConvertedEngineer(@PathVariable Long id, @RequestBody Map<String, Long> request) {
        candidateService.linkConvertedEngineer(id, request.get("engineerId"));
        return ApiResult.success(true);
    }
}
