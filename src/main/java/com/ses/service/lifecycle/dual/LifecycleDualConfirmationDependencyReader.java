package com.ses.service.lifecycle.dual;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.Engineer;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleDualSubmission;
import com.ses.entity.LifecycleDualSubmissionDocument;
import com.ses.entity.LifecycleDualSubmissionOrgFence;
import com.ses.entity.LifecycleTask;
import com.ses.entity.LifecycleTaskDep;
import com.ses.entity.UserOrganization;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.LifecycleDualSubmissionDocumentMapper;
import com.ses.mapper.LifecycleDualSubmissionMapper;
import com.ses.mapper.LifecycleDualSubmissionOrgFenceMapper;
import com.ses.mapper.LifecycleTaskDepMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.LockKey;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.Mode;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.Rank;
import com.ses.service.security.ExplicitActorAuthorizationEvaluator;
import com.ses.service.security.ExplicitActorAuthorizationEvaluator.ExplicitActor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 二者確認のlock対象（依存先）を列挙する部品（NF01細粒度epoch計画 §3.4「CONFIRM submit/confirm attempt」・§5.4・§5.5）。
 *
 * <p>coordinatorのtransaction外discovery（autocommitの単文読取）と、attemptのlock取得後の再読込の両方が同じメソッドを呼ぶ。
 * attemptはREAD_COMMITTEDのためlock後の通常読取は最新のcommit済みの値を返し、lockした行（と、anchorのS lock下で
 * writerが変えられない子行）は以後変わらない。両者のplanが一致しなければ {@link LifecycleLockPlan#requireSameDependencies}
 * がattemptを中止し、新しいdiscoveryからやり直す（§5.6）。</p>
 *
 * <p>本classはtenantを引数で受け取り、SecurityContext・tenant contextを読まない。認可・状態の判定はしない（行の有無だけ）。</p>
 */
@Component
@RequiredArgsConstructor
public class LifecycleDualConfirmationDependencyReader {

    private final LifecycleTaskMapper taskMapper;
    private final LifecycleCaseMapper caseMapper;
    private final EngineerMapper engineerMapper;
    private final UserOrganizationMapper userOrganizationMapper;
    private final LifecycleTaskDepMapper taskDepMapper;
    private final LifecycleDualSubmissionMapper submissionMapper;
    private final LifecycleDualSubmissionDocumentMapper submissionDocumentMapper;
    private final LifecycleDualSubmissionOrgFenceMapper orgFenceMapper;
    private final ExplicitActorAuthorizationEvaluator evaluator;

    /** tenant内のタスク・所属案件・案件の要員。 */
    public record TaskContext(LifecycleTask task, LifecycleCase lcCase, Engineer engineer) {
        public long taskId() {
            return task.getId();
        }

        public long caseId() {
            return lcCase.getId();
        }

        public long engineerId() {
            return engineer.getId();
        }
    }

    /** 提出のlock対象。 */
    public record SubmitDependencies(String tenantId, TaskContext context, long submitterUserId,
                                     SortedSet<Long> organizations, long documentId, long documentVersionId) {
        public SubmitDependencies {
            organizations = Collections.unmodifiableSortedSet(new TreeSet<>(organizations));
        }

        /** 1 S → 3 S{提出者} → 4 X{案件} → 5 X{タスク} → 6 S{提出者の主所属} → 7 S{要員} → 15 S{文書} → 16 S{版}。 */
        public LifecycleLockPlan plan() {
            List<LockKey> keys = new ArrayList<>();
            keys.add(LockKey.global(Mode.SHARED));
            keys.add(LockKey.shared(Rank.SYS_USER, tenantId, submitterUserId));
            keys.add(LockKey.exclusive(Rank.LIFECYCLE_CASE, tenantId, context.caseId()));
            keys.add(LockKey.exclusive(Rank.LIFECYCLE_TASK, tenantId, context.taskId()));
            organizations.forEach(id -> keys.add(LockKey.shared(Rank.ORGANIZATION, "", id)));
            keys.add(LockKey.shared(Rank.ENGINEER, tenantId, context.engineerId()));
            keys.add(LockKey.shared(Rank.DOCUMENT, tenantId, documentId));
            keys.add(LockKey.shared(Rank.DOCUMENT_VERSION, tenantId, documentVersionId));
            return LifecycleLockPlan.from(keys);
        }
    }

    /**
     * 確認・差戻しのlock対象。
     *
     * @param currentSubmitter 現在の提出者（無効化・削除・未知roleで解決できなければnull。planには影響しない）
     */
    public record ConfirmDependencies(String tenantId, TaskContext context, LifecycleDualSubmission submission,
                                      LifecycleDualSubmissionDocument document, long confirmerUserId,
                                      ExplicitActor currentSubmitter,
                                      SortedSet<Long> fenceOrganizations, SortedSet<Long> currentSubmitterOrganizations,
                                      Map<Long, List<Long>> predecessorsBySuccessor) {
        public ConfirmDependencies {
            fenceOrganizations = Collections.unmodifiableSortedSet(new TreeSet<>(fenceOrganizations));
            currentSubmitterOrganizations = Collections.unmodifiableSortedSet(new TreeSet<>(currentSubmitterOrganizations));
            TreeMap<Long, List<Long>> copy = new TreeMap<>();
            predecessorsBySuccessor.forEach((successor, predecessors) -> copy.put(successor, List.copyOf(predecessors)));
            predecessorsBySuccessor = Collections.unmodifiableMap(copy);
        }

        public long submitterUserId() {
            return submission.getSubmitterUserId();
        }

        /** 後続と後続の全先行（確認するタスク自身を含み得る）。 */
        public SortedSet<Long> relatedTaskIds() {
            TreeSet<Long> ids = new TreeSet<>(predecessorsBySuccessor.keySet());
            predecessorsBySuccessor.values().forEach(ids::addAll);
            return ids;
        }

        /**
         * 1 S → 3 S{提出者, 確認者} → 4 X → 5 X{タスク, 後続, 後続の先行} → 6 S{提出時 ∪ 現在の組織} → 7 S → 15 S → 16 S。
         * 後続昇格（L3）の行も順5でまとめて取り、順6以降の後で順5へ戻らない。
         */
        public LifecycleLockPlan plan() {
            List<LockKey> keys = new ArrayList<>();
            keys.add(LockKey.global(Mode.SHARED));
            keys.add(LockKey.shared(Rank.SYS_USER, tenantId, submitterUserId()));
            keys.add(LockKey.shared(Rank.SYS_USER, tenantId, confirmerUserId));
            keys.add(LockKey.exclusive(Rank.LIFECYCLE_CASE, tenantId, context.caseId()));
            keys.add(LockKey.exclusive(Rank.LIFECYCLE_TASK, tenantId, context.taskId()));
            relatedTaskIds().forEach(id -> keys.add(LockKey.exclusive(Rank.LIFECYCLE_TASK, tenantId, id)));
            TreeSet<Long> organizations = new TreeSet<>(fenceOrganizations);
            organizations.addAll(currentSubmitterOrganizations);
            organizations.forEach(id -> keys.add(LockKey.shared(Rank.ORGANIZATION, "", id)));
            keys.add(LockKey.shared(Rank.ENGINEER, tenantId, context.engineerId()));
            keys.add(LockKey.shared(Rank.DOCUMENT, tenantId, document.getDocumentId()));
            keys.add(LockKey.shared(Rank.DOCUMENT_VERSION, tenantId, document.getDocumentVersionId()));
            return LifecycleLockPlan.from(keys);
        }
    }

    /** tenant内のタスク・案件・要員を読む。どれかが無い（他tenant・論理削除を含む）場合は空。 */
    public Optional<TaskContext> readTaskContext(String tenantId, long taskId) {
        LifecycleTask task = taskMapper.selectByIdAndTenant(taskId, tenantId);
        if (task == null || task.getCaseId() == null) {
            return Optional.empty();
        }
        LifecycleCase lcCase = caseMapper.selectByIdAndTenant(task.getCaseId(), tenantId);
        if (lcCase == null || lcCase.getEngineerId() == null) {
            return Optional.empty();
        }
        Engineer engineer = engineerMapper.selectOne(new LambdaQueryWrapper<Engineer>()
                .eq(Engineer::getId, lcCase.getEngineerId())
                .eq(Engineer::getTenantId, tenantId));
        if (engineer == null) {
            return Optional.empty();
        }
        return Optional.of(new TaskContext(task, lcCase, engineer));
    }

    /**
     * 組織fenceの対象＝提出者の有効主所属組織（マネージャーかつ組織scope有効時だけ。計画 §2.2「readerは提出者の主所属組織だけを
     * lockすればよい」）。所属の判定は {@code OrganizationScopeServiceImpl#allowedOrganizationIdsFor} と同じ
     * （基準日に有効・主所属）。組織scopeを使わない提出者では空。
     */
    public SortedSet<Long> submitterOrganizations(ExplicitActor submitter) {
        TreeSet<Long> organizations = new TreeSet<>();
        if (submitter == null || evaluator.hasOrganizationFullAccess(submitter)) {
            return organizations;
        }
        LocalDate asOf = submitter.asOf();
        List<UserOrganization> assignments = userOrganizationMapper.selectList(new LambdaQueryWrapper<UserOrganization>()
                .eq(UserOrganization::getUserId, submitter.userId())
                .le(UserOrganization::getValidFrom, asOf)
                .and(w -> w.isNull(UserOrganization::getValidTo).or().ge(UserOrganization::getValidTo, asOf))
                .orderByAsc(UserOrganization::getId));
        for (UserOrganization assignment : assignments) {
            if (assignment.getOrganizationId() != null && Integer.valueOf(1).equals(assignment.getPrimaryFlag())) {
                organizations.add(assignment.getOrganizationId());
            }
        }
        return organizations;
    }

    /** 提出のlock対象を読む。タスク・案件・要員のどれかが無ければ空。 */
    public Optional<SubmitDependencies> readSubmit(String tenantId, long taskId, ExplicitActor submitter,
                                                   long documentId, long documentVersionId) {
        Objects.requireNonNull(submitter, "submitter");
        return readTaskContext(tenantId, taskId).map(context -> new SubmitDependencies(tenantId, context,
                submitter.userId(), submitterOrganizations(submitter), documentId, documentVersionId));
    }

    /**
     * 確認のlock対象を読む。提出がtenant内に無い・指定タスクの提出でない・提出の文書行が1件でない・タスク等が無い場合は空。
     * 提出者の現在の組織集合は、提出者を {@code asOf} 時点の明示actorとして解決できた場合だけ求める（解決できなければ空集合。
     * その場合の拒否はattemptの提出者認可が行う）。
     */
    public Optional<ConfirmDependencies> readConfirm(String tenantId, long taskId, long submissionId,
                                                     long confirmerUserId, LocalDate asOf) {
        LifecycleDualSubmission submission = findSubmission(tenantId, taskId, submissionId);
        if (submission == null || submission.getSubmitterUserId() == null) {
            return Optional.empty();
        }
        ExplicitActor currentSubmitter = evaluator.resolveActor(tenantId, submission.getSubmitterUserId(), asOf)
                .orElse(null);
        List<LifecycleDualSubmissionDocument> documents = submissionDocumentMapper.selectList(
                new LambdaQueryWrapper<LifecycleDualSubmissionDocument>()
                        .eq(LifecycleDualSubmissionDocument::getTenantId, tenantId)
                        .eq(LifecycleDualSubmissionDocument::getSubmissionId, submissionId));
        if (documents == null || documents.size() != 1) {
            return Optional.empty();
        }
        Optional<TaskContext> context = readTaskContext(tenantId, taskId);
        if (context.isEmpty() || !Objects.equals(context.get().caseId(), submission.getCaseId())) {
            return Optional.empty();
        }
        SortedSet<Long> fence = new TreeSet<>();
        for (LifecycleDualSubmissionOrgFence row : orgFenceMapper.selectList(
                new LambdaQueryWrapper<LifecycleDualSubmissionOrgFence>()
                        .eq(LifecycleDualSubmissionOrgFence::getTenantId, tenantId)
                        .eq(LifecycleDualSubmissionOrgFence::getSubmissionId, submissionId))) {
            fence.add(row.getOrganizationId());
        }
        SortedSet<Long> current = currentSubmitter == null ? new TreeSet<>() : submitterOrganizations(currentSubmitter);
        return Optional.of(new ConfirmDependencies(tenantId, context.get(), submission, documents.get(0),
                confirmerUserId, currentSubmitter, fence, current, successorsWithPredecessors(tenantId, taskId)));
    }

    /** tenant・タスクを条件にした提出（{@code engineer_id} 等DBで束縛されない列は検索の根拠にしない。W3-18 review P3-8）。 */
    public LifecycleDualSubmission findSubmission(String tenantId, long taskId, long submissionId) {
        return submissionMapper.selectOne(new LambdaQueryWrapper<LifecycleDualSubmission>()
                .eq(LifecycleDualSubmission::getTenantId, tenantId)
                .eq(LifecycleDualSubmission::getId, submissionId)
                .eq(LifecycleDualSubmission::getTaskId, taskId));
    }

    /** 後続タスク → その全先行タスク（同じ案件のtenant内の依存定義）。 */
    private Map<Long, List<Long>> successorsWithPredecessors(String tenantId, long taskId) {
        Map<Long, List<Long>> result = new TreeMap<>();
        List<LifecycleTaskDep> successors = taskDepMapper.selectByPredecessorTaskIdAndTenant(taskId, tenantId);
        if (successors == null) {
            return result;
        }
        for (LifecycleTaskDep dep : successors) {
            Long successorId = dep.getSuccessorTaskId();
            if (successorId == null || result.containsKey(successorId)) {
                continue;
            }
            List<Long> predecessors = new ArrayList<>();
            List<LifecycleTaskDep> deps = taskDepMapper.selectBySuccessorTaskIdAndTenant(successorId, tenantId);
            if (deps != null) {
                deps.stream().map(LifecycleTaskDep::getPredecessorTaskId).filter(Objects::nonNull)
                        .sorted().distinct().forEach(predecessors::add);
            }
            result.put(successorId, predecessors);
        }
        return result;
    }
}
