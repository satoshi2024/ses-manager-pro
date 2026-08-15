package com.ses.service.staffing;

import com.ses.entity.ProjectPosition;

import java.util.List;

/**
 * 案件ポジション（募集枠）の管理と状態機械。
 *
 * <p>状態機械（design §5.4・確定済み）:
 * <pre>
 *   募集中 → 候補選定 / 取消
 *   候補選定 → 充足 / 保留 / 取消
 *   充足 → 募集中（欠員発生）
 *   保留 → 募集中
 *   取消 → 募集中
 * </pre>
 * 遷移は状態CAS（status+version条件付きUPDATE）で実行する。
 */
public interface PositionService {

    /** ポジションを募集中で登録する。 */
    ProjectPosition create(ProjectPosition position);

    /** 内容を更新する（version CAS・楽観ロック）。状態はchangeStatusでのみ変更する。 */
    ProjectPosition update(ProjectPosition position);

    /** 状態遷移（状態CAS）。許可されない遷移はBusinessException。 */
    ProjectPosition changeStatus(Long id, String toStatus);

    /** 論理削除。充足済みポジションは削除できない。 */
    void delete(Long id);

    ProjectPosition get(Long id);

    /** 案件配下のポジション一覧。 */
    List<ProjectPosition> listByProject(Long projectId);
}
