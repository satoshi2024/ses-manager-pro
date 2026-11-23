package com.ses.featureenablement.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.feature.Feature;
import com.ses.entity.FeatureSignOff;
import com.ses.entity.FeatureSignOffBundle;
import com.ses.entity.FeatureSignOffBundleMember;
import com.ses.featureenablement.catalog.BundleKind;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.SignOffKind;
import com.ses.featureenablement.signoff.FeatureSignOffKindValidator;
import com.ses.featureenablement.state.FeatureEnablementTenant;
import com.ses.mapper.FeatureSignOffBundleMapper;
import com.ses.mapper.FeatureSignOffBundleMemberMapper;
import com.ses.mapper.FeatureSignOffMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 署名の組の流れ（feature-enablement-console design §2.6.1・§6.5。review P1-2）。
 *
 * <ul>
 *   <li>登録: 新しい署名（文書つき）と引き継ぐ確認済みの署名を1回の要求で組にする。新しい署名と組は {@code REGISTERED}。</li>
 *   <li>確認: 組ごとに1回、登録者と別の管理者（NF07 の組は privacy-admin。段階 C）。全ての新しい member の署名日が今日以前、
 *       種別の検査、組の種類の整合の検査（{@link FeatureSignOffBundleConsistencyCheck}）。旧い組と置き換わる署名を先に
 *       {@code SUPERSEDED} にしてから新しい署名と組を {@code CONFIRMED} にし（一意索引）、それを要する開いている開放単位を
 *       評価し直す。組の一部だけを {@code CONFIRMED} にする経路は無い（1件ずつの確認は 409）。</li>
 *   <li>取消し: 組を {@code REVOKED} にし、組の種類を要する開いている開放単位を {@code AUTO_CLOSED(SIGN_OFF_REVOKED)}。member の
 *       署名の1件の取消しは {@link FeatureSignOffService#revoke}（組は {@code CONFIRMED} のまま）。</li>
 *   <li>lock の順: 世代 → 依存する開放状態 → 組の行（id の昇順）→ 署名の行（id の昇順）。全て READ COMMITTED。</li>
 * </ul>
 */
@Service
public class FeatureSignOffBundleServiceImpl implements FeatureSignOffBundleService {

    private final FeatureEnablementWriteSupport write;
    private final FeatureSignOffTransitions transitions;
    private final FeatureSignOffRegistrar registrar;
    private final FeatureEnablementActorPolicy actorPolicy;
    private final FeatureEnablementCatalog catalog;
    private final FeatureSignOffMapper signOffMapper;
    private final FeatureSignOffBundleMapper bundleMapper;
    private final FeatureSignOffBundleMemberMapper memberMapper;
    private final FeatureEnablementTenant tenant;
    private final ObjectProvider<FeatureSignOffBundleConsistencyCheck> consistencyChecks;

    public FeatureSignOffBundleServiceImpl(FeatureEnablementWriteSupport write, FeatureSignOffTransitions transitions,
                                           FeatureSignOffRegistrar registrar, FeatureEnablementActorPolicy actorPolicy,
                                           FeatureEnablementCatalog catalog, FeatureSignOffMapper signOffMapper,
                                           FeatureSignOffBundleMapper bundleMapper,
                                           FeatureSignOffBundleMemberMapper memberMapper, FeatureEnablementTenant tenant,
                                           ObjectProvider<FeatureSignOffBundleConsistencyCheck> consistencyChecks) {
        this.write = write;
        this.transitions = transitions;
        this.registrar = registrar;
        this.actorPolicy = actorPolicy;
        this.catalog = catalog;
        this.signOffMapper = signOffMapper;
        this.bundleMapper = bundleMapper;
        this.memberMapper = memberMapper;
        this.tenant = tenant;
        this.consistencyChecks = consistencyChecks;
    }

    // ---------------------------------------------------------------- 登録

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public FeatureSignOffBundle register(String bundleKindCode, List<NewMember> newMembers, Map<String, Long> carriedOver,
                                         FeatureEnablementActor actor) {
        FeatureEnablementActor checked = requireActor(actor);
        BundleKind bundleKind = bundleKindCode == null ? null : catalog.bundleKind(bundleKindCode).orElse(null);
        if (bundleKind == null) {
            throw BusinessException.of(400, "error.featureEnablement.bundleKindUnknown");
        }
        actorPolicy.requirePrivacyAdminIfRequired(bundleKind.privacy(), checked);
        List<NewMember> fresh = newMembers == null ? List.of() : List.copyOf(newMembers);
        Map<String, Long> carried = carriedOver == null ? Map.of() : new TreeMap<>(carriedOver);

        // 種別の検査と組の構成（必須の member を全て含み、組の種類に無い種別・重複を含まない）
        Map<String, SignOffKind> freshKinds = new LinkedHashMap<>();
        for (NewMember member : fresh) {
            if (member == null || member.registration() == null) {
                throw BusinessException.of(400, "error.featureEnablement.bundleMembersInvalid", "-");
            }
            SignOffKind kind = FeatureSignOffKindValidator.validate(catalog, input(member.registration()));
            requireNotExpired(member.registration().expiresOn());
            if (!bundleKind.code().equals(kind.bundleKind()) || freshKinds.put(kind.code(), kind) != null
                    || carried.containsKey(kind.code())) {
                throw BusinessException.of(400, "error.featureEnablement.bundleMembersInvalid", kind.code());
            }
            FeatureSignOffRegistrar.optionalName(member.registration().signerName());
        }
        Set<String> all = new TreeSet<>(freshKinds.keySet());
        all.addAll(carried.keySet());
        for (String kind : all) {
            if (!bundleKind.allMembers().contains(kind)) {
                throw BusinessException.of(400, "error.featureEnablement.bundleMembersInvalid", kind);
            }
        }
        for (String required : bundleKind.requiredMembers()) {
            if (!all.contains(required)) {
                throw BusinessException.of(400, "error.featureEnablement.bundleMembersInvalid", required);
            }
        }
        if (freshKinds.isEmpty()) {
            // 何も替えない組は登録しない（引継ぎだけの組は旧い組と同じ）
            throw BusinessException.of(400, "error.featureEnablement.bundleMembersInvalid", "-");
        }

        // 文書の保存（scan）は世代の lock の前
        List<FeatureSignOffRegistrar.StoredDocument> documents = new ArrayList<>();
        for (NewMember member : fresh) {
            documents.add(registrar.storeDocument(member.document(), member.originalName()));
        }

        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.of(checked));
        for (Map.Entry<String, Long> entry : carried.entrySet()) {
            FeatureSignOff row = entry.getValue() == null ? null : signOffMapper.selectById(entry.getValue());
            if (row == null || !context.tenantId().equals(row.getTenantId()) || !entry.getKey().equals(row.getKindCode())
                    || !"CONFIRMED".equals(row.getStatus())) {
                throw BusinessException.of(400, "error.featureEnablement.bundleMembersInvalid", entry.getKey());
            }
        }
        FeatureSignOffBundle bundle = new FeatureSignOffBundle();
        bundle.setTenantId(context.tenantId());
        bundle.setBundleKind(bundleKind.code());
        bundle.setStatus("REGISTERED");
        bundle.setRegisteredBy(checked.userId());
        bundle.setRegisteredAt(context.now());
        bundle.setVersion(0);
        bundleMapper.insert(bundle);
        for (int i = 0; i < fresh.size(); i++) {
            NewMember member = fresh.get(i);
            SignOffKind kind = freshKinds.get(member.registration().kindCode());
            FeatureSignOff row = registrar.insertRegistered(context, kind, member.registration(),
                    FeatureSignOffRegistrar.optionalName(member.registration().signerName()), documents.get(i), checked);
            insertMember(bundle, kind.code(), row.getId(), false);
        }
        carried.forEach((kind, signOffId) -> insertMember(bundle, kind, signOffId, true));
        write.event(context, "BUNDLE", bundleKind.code(), bundle.getId(), "REGISTERED", null, "REGISTERED", null,
                "members=" + String.join(",", all));
        write.audit(context, "FEATURE_SIGN_OFF_BUNDLE_REGISTERED", bundle.getId(), null,
                "{\"bundle\":" + bundle.getId() + ",\"kind\":\"" + bundleKind.code() + "\",\"status\":\"REGISTERED\"}");
        context.markChanged(null);
        write.finish(context);
        return bundle;
    }

    // ---------------------------------------------------------------- 確認

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public FeatureSignOffBundle confirm(long bundleId, int expectedVersion, FeatureEnablementActor actor) {
        FeatureEnablementActor checked = requireActor(actor);
        FeatureSignOffBundle found = find(bundleId);
        BundleKind bundleKind = bundleKindOf(found);
        actorPolicy.requirePrivacyAdminIfRequired(bundleKind.privacy(), checked);

        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.of(checked));
        transitions.lockDependents(context, bundleKind.allMembers());
        FeatureSignOffBundle previousFound = bundleMapper.selectOne(new QueryWrapper<FeatureSignOffBundle>()
                .eq("tenant_id", context.tenantId()).eq("bundle_kind", bundleKind.code()).eq("status", "CONFIRMED"));
        FeatureSignOffBundle previous = null;
        if (previousFound != null && previousFound.getId() < bundleId) {
            previous = bundleMapper.selectByIdForUpdate(previousFound.getId());
        }
        FeatureSignOffBundle bundle = lockBundle(bundleId, expectedVersion, "REGISTERED",
                "error.featureEnablement.bundleNotRegistered");
        if (previousFound != null && previousFound.getId() > bundleId) {
            previous = bundleMapper.selectByIdForUpdate(previousFound.getId());
        }
        if (checked.userId().equals(bundle.getRegisteredBy())) {
            throw BusinessException.of(403, "error.featureEnablement.signOffSelfConfirm");
        }

        // member の署名と、置き換わる確認済みの署名を id の昇順で lock する
        List<FeatureSignOffBundleMember> members = memberMapper.selectByBundle(bundle.getId());
        Map<String, Long> replaced = new TreeMap<>();
        TreeSet<Long> lockIds = new TreeSet<>();
        for (FeatureSignOffBundleMember member : members) {
            lockIds.add(member.getSignOffId());
            if (!Integer.valueOf(1).equals(member.getCarriedOver())) {
                FeatureSignOff confirmedNow = signOffMapper.selectOne(new QueryWrapper<FeatureSignOff>()
                        .eq("tenant_id", context.tenantId()).eq("kind_code", member.getKindCode())
                        .eq("status", "CONFIRMED"));
                if (confirmedNow != null) {
                    replaced.put(member.getKindCode(), confirmedNow.getId());
                    lockIds.add(confirmedNow.getId());
                }
            }
        }
        Map<Long, FeatureSignOff> locked = new LinkedHashMap<>();
        for (Long id : lockIds) {
            locked.put(id, signOffMapper.selectByIdForUpdate(id));
        }
        Map<String, FeatureSignOff> memberRows = new TreeMap<>();
        for (FeatureSignOffBundleMember member : members) {
            FeatureSignOff row = locked.get(member.getSignOffId());
            boolean carried = Integer.valueOf(1).equals(member.getCarriedOver());
            if (row == null || !(carried ? "CONFIRMED" : "REGISTERED").equals(row.getStatus())) {
                throw BusinessException.of(409, "error.featureEnablement.bundleMembersInvalid", member.getKindCode());
            }
            if (!row.getExpiresOn().isAfter(context.today())) {
                // 失効した署名を組に入れない（確認の後に依存する開放単位が閉じる誤操作を防ぐ。review rb-fe-a1 P3-2）
                throw BusinessException.of(400, "error.featureEnablement.signOffAlreadyExpired");
            }
            if (!carried) {
                if (row.getSignedOn().isAfter(context.today())) {
                    throw BusinessException.of(400, "error.featureEnablement.signOffNotYetSigned");
                }
                FeatureSignOffKindValidator.validate(catalog, new FeatureSignOffKindValidator.Input(row.getKindCode(),
                        row.getReference(), row.getSignerRole(), row.getSignedOn(), row.getExpiresOn(),
                        FeatureSignOffRegistrar.structuredMap(row.getStructuredValues())));
            }
            memberRows.put(member.getKindCode(), row);
        }
        List<FeatureSignOffBundleConsistencyCheck> checks = consistencyChecks.orderedStream()
                .filter(check -> bundleKind.code().equals(check.bundleKind())).toList();
        if (bundleKind.consistencyCheckRequired() && checks.isEmpty()) {
            // 整合の検査を要する組の種類で実装が無ければ確認しない（evaluator.missing と同じく fail-closed。review rb-fe-a1 P3-3）
            throw BusinessException.of(409, "error.featureEnablement.bundleCheckMissing");
        }
        checks.forEach(check -> check.verify(context.tenantId(), Map.copyOf(memberRows)));

        // 旧い組 → 置き換わる署名 → 新しい署名 → 新しい組の順（一意索引）
        if (previous != null && "CONFIRMED".equals(previous.getStatus())) {
            endBundle(context, previous, "SUPERSEDED", null, "REPLACED");
        }
        for (Map.Entry<String, Long> entry : replaced.entrySet()) {
            FeatureSignOff old = locked.get(entry.getValue());
            if (old != null && "CONFIRMED".equals(old.getStatus())) {
                transitions.endSignOff(context, old, "SUPERSEDED", null, "REPLACED",
                        memberRows.get(entry.getKey()).getId());
            }
        }
        for (FeatureSignOffBundleMember member : members) {
            if (Integer.valueOf(1).equals(member.getCarriedOver())) {
                continue;
            }
            FeatureSignOff row = memberRows.get(member.getKindCode());
            UpdateWrapper<FeatureSignOff> update = new UpdateWrapper<>();
            update.eq("id", row.getId()).eq("status", "REGISTERED").eq("version", row.getVersion())
                    .set("status", "CONFIRMED").set("confirmed_by", checked.userId()).set("confirmed_at", context.now())
                    .set("version", row.getVersion() + 1);
            if (signOffMapper.update(null, update) != 1) {
                throw BusinessException.of(409, "error.featureEnablement.concurrentChange");
            }
            write.event(context, "SIGN_OFF", row.getKindCode(), row.getId(), "CONFIRMED", "REGISTERED", "CONFIRMED",
                    "BUNDLE_" + bundle.getId(), null);
        }
        UpdateWrapper<FeatureSignOffBundle> confirmed = new UpdateWrapper<>();
        confirmed.eq("id", bundle.getId()).eq("status", "REGISTERED").eq("version", bundle.getVersion())
                .set("status", "CONFIRMED").set("confirmed_by", checked.userId()).set("confirmed_at", context.now())
                .set("version", bundle.getVersion() + 1);
        if (bundleMapper.update(null, confirmed) != 1) {
            throw BusinessException.of(409, "error.featureEnablement.concurrentChange");
        }
        write.event(context, "BUNDLE", bundleKind.code(), bundle.getId(), "CONFIRMED", "REGISTERED", "CONFIRMED",
                previous == null ? null : "REPLACES_" + previous.getId(), null);
        write.audit(context, "FEATURE_SIGN_OFF_BUNDLE_CONFIRMED", bundle.getId(),
                "{\"bundle\":" + bundle.getId() + ",\"status\":\"REGISTERED\"}",
                "{\"bundle\":" + bundle.getId() + ",\"kind\":\"" + bundleKind.code() + "\",\"status\":\"CONFIRMED\""
                        + (previous == null ? "" : ",\"replaces\":" + previous.getId()) + "}");
        context.markChanged(null);
        List<Feature> closed = transitions.reevaluateDependents(context, bundleKind.allMembers(),
                "SIGN_OFF_REPLACED_INVALID");
        write.finish(context);
        return bundleMapper.selectById(bundle.getId());
    }

    // ---------------------------------------------------------------- 却下・取下げ・取消し

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void reject(long bundleId, int expectedVersion, String reasonCode, FeatureEnablementActor actor) {
        FeatureEnablementActor checked = requireActor(actor);
        String reason = FeatureEnablementServiceImpl.requiredReasonCode(reasonCode);
        actorPolicy.requirePrivacyAdminIfRequired(bundleKindOf(find(bundleId)).privacy(), checked);
        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.of(checked));
        FeatureSignOffBundle bundle = lockBundle(bundleId, expectedVersion, "REGISTERED",
                "error.featureEnablement.bundleNotRegistered");
        if (checked.userId().equals(bundle.getRegisteredBy())) {
            throw BusinessException.of(403, "error.featureEnablement.signOffSelfConfirm");
        }
        endRegistered(context, bundle, checked.userId(), reason);
        write.finish(context);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void withdraw(long bundleId, int expectedVersion, FeatureEnablementActor actor) {
        FeatureEnablementActor checked = requireActor(actor);
        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.of(checked));
        FeatureSignOffBundle bundle = lockBundle(bundleId, expectedVersion, "REGISTERED",
                "error.featureEnablement.bundleNotRegistered");
        if (!checked.userId().equals(bundle.getRegisteredBy())) {
            throw BusinessException.of(403, "error.featureEnablement.withdrawOnlyRegistrant");
        }
        endRegistered(context, bundle, checked.userId(), "WITHDRAWN_BY_REGISTRANT");
        write.finish(context);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void revoke(long bundleId, int expectedVersion, String reasonCode, FeatureEnablementActor actor) {
        FeatureEnablementActor checked = requireActor(actor);
        String reason = FeatureEnablementServiceImpl.requiredReasonCode(reasonCode);
        BundleKind bundleKind = bundleKindOf(find(bundleId));
        actorPolicy.requirePrivacyAdminIfRequired(bundleKind.privacy(), checked);
        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.of(checked));
        transitions.lockDependents(context, bundleKind.allMembers());
        FeatureSignOffBundle bundle = lockBundle(bundleId, expectedVersion, "CONFIRMED",
                "error.featureEnablement.bundleNotConfirmed");
        endBundle(context, bundle, "REVOKED", checked.userId(), reason);
        List<Feature> closed = transitions.closeDependents(context, bundleKind.allMembers(), "SIGN_OFF_REVOKED");
        write.finish(context);
        write.notifier().notifyAdministrators("bundleRevoked", new Object[]{bundleKind.code()},
                "FEATURE_ENABLEMENT:BUNDLE_REVOKED:" + bundle.getId(), List.of());
    }

    // ---------------------------------------------------------------- helper

    /** 未確認の組と、その新しい署名（引継ぎでない member）を {@code REJECTED} にする。 */
    private void endRegistered(FeatureEnablementWriteSupport.WriteContext context, FeatureSignOffBundle bundle,
                               Long endedBy, String reasonCode) {
        List<FeatureSignOffBundleMember> members = new ArrayList<>(memberMapper.selectByBundle(bundle.getId()));
        members.sort(Comparator.comparing(FeatureSignOffBundleMember::getSignOffId));
        for (FeatureSignOffBundleMember member : members) {
            if (Integer.valueOf(1).equals(member.getCarriedOver())) {
                continue;
            }
            FeatureSignOff row = signOffMapper.selectByIdForUpdate(member.getSignOffId());
            if (row != null && "REGISTERED".equals(row.getStatus())) {
                transitions.endSignOff(context, row, "REJECTED", endedBy, reasonCode, null);
            }
        }
        endBundle(context, bundle, "REJECTED", endedBy, reasonCode);
    }

    private void endBundle(FeatureEnablementWriteSupport.WriteContext context, FeatureSignOffBundle bundle, String status,
                           Long endedBy, String reasonCode) {
        UpdateWrapper<FeatureSignOffBundle> update = new UpdateWrapper<>();
        update.eq("id", bundle.getId()).eq("status", bundle.getStatus()).eq("version", bundle.getVersion())
                .set("status", status).set("ended_by", endedBy).set("ended_at", context.now())
                .set("end_reason", reasonCode).set("version", bundle.getVersion() + 1);
        if (bundleMapper.update(null, update) != 1) {
            throw BusinessException.of(409, "error.featureEnablement.concurrentChange");
        }
        write.event(context, "BUNDLE", bundle.getBundleKind(), bundle.getId(), status, bundle.getStatus(), status,
                reasonCode, null);
        write.audit(context, "FEATURE_SIGN_OFF_BUNDLE_" + status, bundle.getId(),
                "{\"bundle\":" + bundle.getId() + ",\"status\":\"" + bundle.getStatus() + "\"}",
                "{\"bundle\":" + bundle.getId() + ",\"kind\":\"" + bundle.getBundleKind() + "\",\"status\":\"" + status
                        + "\"" + (reasonCode == null ? "" : ",\"reason\":\"" + reasonCode + "\"") + "}");
        context.markChanged(null);
    }

    private void insertMember(FeatureSignOffBundle bundle, String kind, Long signOffId, boolean carried) {
        FeatureSignOffBundleMember member = new FeatureSignOffBundleMember();
        member.setBundleId(bundle.getId());
        member.setTenantId(bundle.getTenantId());
        member.setKindCode(kind);
        member.setSignOffId(signOffId);
        member.setCarriedOver(carried ? 1 : 0);
        memberMapper.insert(member);
    }

    private FeatureSignOffBundle lockBundle(long id, int expectedVersion, String expectedStatus, String statusMessage) {
        FeatureSignOffBundle bundle = bundleMapper.selectByIdForUpdate(id);
        if (bundle == null || !tenant.tenantId().equals(bundle.getTenantId())) {
            throw BusinessException.of(404, "error.featureEnablement.bundleNotFound");
        }
        if (!expectedStatus.equals(bundle.getStatus())) {
            throw BusinessException.of(409, statusMessage);
        }
        if (bundle.getVersion() == null || bundle.getVersion() != expectedVersion) {
            throw BusinessException.of(409, "error.featureEnablement.signOffVersionMismatch");
        }
        return bundle;
    }

    private FeatureSignOffBundle find(long id) {
        FeatureSignOffBundle bundle = bundleMapper.selectById(id);
        if (bundle == null || !tenant.tenantId().equals(bundle.getTenantId())) {
            throw BusinessException.of(404, "error.featureEnablement.bundleNotFound");
        }
        return bundle;
    }

    private BundleKind bundleKindOf(FeatureSignOffBundle bundle) {
        return catalog.bundleKind(bundle.getBundleKind())
                .orElseThrow(() -> BusinessException.of(409, "error.featureEnablement.bundleKindUnknown"));
    }

    /** 失効日が今日以前の署名は登録しない（review rb-fe-a1 P3-2）。 */
    private void requireNotExpired(java.time.LocalDate expiresOn) {
        if (expiresOn != null && !expiresOn.isAfter(
                com.ses.featureenablement.condition.FeatureEvaluationContext.businessDate(write.clock()))) {
            throw BusinessException.of(400, "error.featureEnablement.signOffAlreadyExpired");
        }
    }

    private FeatureEnablementActor requireActor(FeatureEnablementActor actor) {
        return actorPolicy.requireActiveAdministrator(actor == null ? null : actor.userId());
    }

    private static FeatureSignOffKindValidator.Input input(FeatureSignOffService.Registration registration) {
        return new FeatureSignOffKindValidator.Input(registration.kindCode(), registration.reference(),
                registration.signerRole(), registration.signedOn(), registration.expiresOn(),
                registration.structuredValues());
    }
}
