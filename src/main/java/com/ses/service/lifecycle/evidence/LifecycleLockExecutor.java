package com.ses.service.lifecycle.evidence;

import com.ses.mapper.AuthorizationEpochMapper;
import com.ses.mapper.LifecycleLockMapper;
import com.ses.service.security.epoch.TransactionBoundState;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * {@link LifecycleLockPlan} をDB lockとして取得する（NF01細粒度epoch計画 §3.3・§3.5）。rankごとに1文のlocking SQL
 * （{@link LifecycleLockMapper}、全体epoch行は {@link AuthorizationEpochMapper}）を、planの順（rank → partition → ID）に実行する。
 *
 * <ul>
 *   <li>現在の実transactionの中でだけ呼ぶ（{@link Propagation#MANDATORY}）。lockはtransactionの終わりまで保持される。</li>
 *   <li>§3.5 規則1・3: 1 transactionの中で、既に取得したkeyより前（rank・partition・IDの順で同じか前）のkeyを後から取らない。
 *       不足に気付いた場合は途中で足さず、transaction全体をrollbackして新しいdiscoveryからやり直す。
 *       依存先は1つのplanにまとめて渡すこと。</li>
 *   <li>§3.5 規則2: 同じ行のmodeはplan構築時に1つへ畳まれている（S→X昇格を起こさない）。</li>
 *   <li>タスク行は、同じplanで同じtenantの案件をEXCLUSIVEで取る場合だけ受け付け、その案件に属する行だけをlockする
 *       （case X → task X、batch 0 L1）。</li>
 *   <li>対象行が無い（tenant不一致を含む）場合は {@link LifecycleLockPlan.DependencyChangedException} を投げる。
 *       discovery後に依存先が変わったことを意味するため、呼出側はrollbackして再discoveryする。</li>
 * </ul>
 *
 * <p>W3-10では定義と実MySQL試験だけで、業務経路からは呼ばない（W3-16b・W3-21aが接続する）。</p>
 */
@Component
public class LifecycleLockExecutor {

    private static final Object LAST_ACQUIRED_KEY = new Object();

    private final LifecycleLockMapper lockMapper;
    private final AuthorizationEpochMapper epochMapper;

    public LifecycleLockExecutor(LifecycleLockMapper lockMapper, AuthorizationEpochMapper epochMapper) {
        this.lockMapper = lockMapper;
        this.epochMapper = epochMapper;
    }

    /**
     * planの全keyを順にlockする。
     *
     * @throws LifecycleLockPlan.DependencyChangedException 対象行が無い場合
     * @throws IllegalStateException transaction外の呼出し、または同じtransactionで既に取得したkey以前のkeyを含む場合
     * @throws IllegalArgumentException 案件を伴わないタスクのlockを含む場合
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void acquire(LifecycleLockPlan plan) {
        Objects.requireNonNull(plan, "plan");
        TransactionBoundState.requireActualTransaction();
        Map<String, List<Long>> exclusiveCasesByTenant = exclusiveCasesByTenant(plan);
        LastAcquired last = TransactionBoundState.current(LAST_ACQUIRED_KEY, LastAcquired::new);
        for (LifecycleLockPlan.LockKey key : plan.locks()) {
            if (last.key != null && LifecycleLockPlan.ROW_ORDER.compare(key, last.key) <= 0) {
                throw new IllegalStateException("lock順に違反します（取得済み " + last.key.rank() + " の後に "
                        + key.rank() + " を取ろうとしました）。依存先は1つのplanにまとめてください");
            }
            if (!lock(key, exclusiveCasesByTenant)) {
                throw new LifecycleLockPlan.DependencyChangedException();
            }
            last.key = key;
        }
    }

    private boolean lock(LifecycleLockPlan.LockKey key, Map<String, List<Long>> exclusiveCasesByTenant) {
        boolean exclusive = key.mode() == LifecycleLockPlan.Mode.EXCLUSIVE;
        String tenant = key.partition();
        long id = key.id();
        return switch (key.rank()) {
            case GLOBAL_AUTHORIZATION_EPOCH -> (exclusive
                    ? epochMapper.selectGlobalForUpdate() : epochMapper.selectGlobalForShare()) != null;
            case APPROVAL_REQUEST -> lockMapper.lockApprovalRequest(id, tenant) != null;
            case SYS_USER -> lockMapper.lockSysUser(id, tenant, exclusive) != null;
            case LIFECYCLE_CASE -> lockMapper.lockLifecycleCase(id, tenant) != null;
            case LIFECYCLE_TASK -> lockMapper.lockLifecycleTask(id, exclusiveCasesByTenant.get(tenant)) != null;
            case ORGANIZATION -> lockMapper.lockOrganization(id, exclusive) != null;
            case ENGINEER -> lockMapper.lockEngineer(id, tenant, exclusive) != null;
            case USER_ORGANIZATION -> lockMapper.lockUserOrganization(id, tenant) != null;
            case ENGINEER_ACCOUNT_LINK -> lockMapper.lockEngineerAccountLink(id, tenant) != null;
            case ENGINEER_SALES -> lockMapper.lockEngineerSales(id) != null;
            case ENGINEER_ACCOUNTING_HISTORY -> lockMapper.lockEngineerAccountingHistory(id) != null;
            case PORTAL_ENGINEER_LINK -> lockMapper.lockPortalEngineerLink(id, tenant) != null;
            case CUSTOMER -> lockMapper.lockCustomer(id, tenant) != null;
            case CONTRACT -> lockMapper.lockContract(id, tenant) != null;
            case PROPOSAL -> lockMapper.lockProposal(id) != null;
            case PROJECT -> lockMapper.lockProject(id) != null;
            case SALES_ACTIVITY -> lockMapper.lockSalesActivity(id) != null;
            case ASSET -> lockMapper.lockAsset(id) != null;
            case ASSET_ASSIGNMENT -> lockMapper.lockAssetAssignment(id) != null;
            case ACCEPTANCE -> lockMapper.lockAcceptance(id) != null;
            case SERVICE_REQUEST -> lockMapper.lockServiceRequest(id, tenant) != null;
            case EXTERNAL_ACCOUNT_REFERENCE -> lockMapper.lockExternalAccountReference(id, tenant) != null;
            case DOCUMENT -> lockMapper.lockDocument(id, tenant, exclusive) != null;
            case DOCUMENT_VERSION -> lockMapper.lockDocumentVersion(id, tenant, exclusive) != null;
            case DOCUMENT_LINK -> lockMapper.lockDocumentLink(id, tenant) != null;
            case DOCUMENT_DISPOSAL_REQUEST -> lockMapper.lockDocumentDisposalRequest(id) != null;
        };
    }

    /** タスクのlockを含む場合、同じtenantの案件をEXCLUSIVEで取ることを要求し、tenantごとの案件IDを返す。 */
    private static Map<String, List<Long>> exclusiveCasesByTenant(LifecycleLockPlan plan) {
        Map<String, List<Long>> cases = new TreeMap<>();
        for (LifecycleLockPlan.LockKey key : plan.locks()) {
            if (key.rank() == LifecycleLockPlan.Rank.LIFECYCLE_CASE && key.mode() == LifecycleLockPlan.Mode.EXCLUSIVE) {
                cases.computeIfAbsent(key.partition(), ignored -> new ArrayList<>()).add(key.id());
            }
        }
        for (LifecycleLockPlan.LockKey key : plan.locks()) {
            if (key.rank() == LifecycleLockPlan.Rank.LIFECYCLE_TASK && !cases.containsKey(key.partition())) {
                throw new IllegalArgumentException("タスク行は同じtenantの案件をEXCLUSIVEでlockするplanでだけ取れます");
            }
        }
        cases.replaceAll((tenant, ids) -> List.copyOf(ids));
        return cases;
    }

    /** 同じtransactionで最後に取得したkey。 */
    private static final class LastAcquired {
        private LifecycleLockPlan.LockKey key;
    }
}
