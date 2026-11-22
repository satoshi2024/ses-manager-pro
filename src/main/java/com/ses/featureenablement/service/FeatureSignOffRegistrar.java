package com.ses.featureenablement.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.enums.FileKind;
import com.ses.common.exception.BusinessException;
import com.ses.dto.file.StoredFile;
import com.ses.entity.FeatureSignOff;
import com.ses.featureenablement.catalog.SignOffKind;
import com.ses.featureenablement.state.FeatureEnablementTenant;
import com.ses.mapper.FeatureSignOffMapper;
import com.ses.service.FileStorageService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 署名の文書の保存と {@code REGISTERED} の行の作成（1件の署名と組の登録が同じ規則で書く。feature-enablement-console design §6.1）。
 *
 * <ul>
 *   <li>文書は {@code FileStorageService.store(byte[], …, FEATURE_SIGN_OFF)} で保存する。scan が {@code CLEAN} でなければ保存せず
 *       400（{@code error.file.scanRejected}。実体と metadata を残さない）。SHA-256 は bytes から計算する。</li>
 *   <li>登録の transaction が rollback すれば保存した文書を消す（誰も claim しない file を残さない。残っても孤児の清掃が消す）。</li>
 *   <li>行・履歴・監査に氏名を書かない（行の {@code signer_name} だけ）。</li>
 * </ul>
 */
@Slf4j
@Component
public class FeatureSignOffRegistrar {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 保存した文書（行に写す値）。 */
    public record StoredDocument(String storedName, String originalName, String sha256, long size) {
    }

    private final FeatureEnablementWriteSupport write;
    private final FeatureSignOffMapper signOffMapper;
    private final FileStorageService fileStorage;
    private final FeatureEnablementTenant tenant;

    public FeatureSignOffRegistrar(FeatureEnablementWriteSupport write, FeatureSignOffMapper signOffMapper,
                                   FileStorageService fileStorage, FeatureEnablementTenant tenant) {
        this.write = write;
        this.signOffMapper = signOffMapper;
        this.fileStorage = fileStorage;
        this.tenant = tenant;
    }

    /** 文書を保存する（世代の lock の前に呼ぶ。scan の間に他の書込みを待たせない）。 */
    public StoredDocument storeDocument(byte[] document, String originalName) {
        if (document == null || document.length == 0) {
            throw BusinessException.of(400, "error.featureEnablement.signOffDocumentRequired");
        }
        String sha256 = sha256(document);
        StoredFile stored = AccountingTenantContextHolder.runWithTenant(tenant.tenantId(),
                () -> fileStorage.store(document, originalName, FileKind.FEATURE_SIGN_OFF));
        deleteOnRollback(stored.getStoredName());
        return new StoredDocument(stored.getStoredName(), stored.getOriginalName(), sha256, document.length);
    }

    /** {@code REGISTERED} の行を作り、履歴と監査を書く。 */
    public FeatureSignOff insertRegistered(FeatureEnablementWriteSupport.WriteContext context, SignOffKind kind,
                                           FeatureSignOffService.Registration registration, String signerName,
                                           StoredDocument document, FeatureEnablementActor actor) {
        FeatureSignOff row = new FeatureSignOff();
        row.setTenantId(context.tenantId());
        row.setKindCode(kind.code());
        row.setReference(registration.reference());
        row.setSignerRole(registration.signerRole());
        row.setSignerName(signerName);
        row.setSignedOn(registration.signedOn());
        row.setExpiresOn(registration.expiresOn());
        row.setStructuredValues(structuredJson(registration.structuredValues()));
        row.setStoredName(document.storedName());
        row.setOriginalName(document.originalName());
        row.setKindClass(kind.kindClass().name());
        row.setContentSha256(document.sha256());
        row.setSizeBytes(document.size());
        row.setStatus("REGISTERED");
        row.setRegisteredBy(actor.userId());
        row.setRegisteredAt(context.now());
        row.setVersion(0);
        signOffMapper.insert(row);
        write.event(context, "SIGN_OFF", kind.code(), row.getId(), "REGISTERED", null, "REGISTERED", null, null);
        write.audit(context, "FEATURE_SIGN_OFF_REGISTERED", row.getId(), null,
                "{\"signOff\":" + row.getId() + ",\"kind\":\"" + kind.code() + "\",\"status\":\"REGISTERED\",\"sha256\":\""
                        + document.sha256() + "\"}");
        context.markChanged(null);
        return row;
    }

    /** 氏名（任意。前後の空白を除き {@value FeatureSignOffService#SIGNER_NAME_MAX} 文字まで）。 */
    public static String optionalName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.length() > FeatureSignOffService.SIGNER_NAME_MAX) {
            throw BusinessException.of(400, "error.featureEnablement.signOffNameTooLong",
                    FeatureSignOffService.SIGNER_NAME_MAX);
        }
        return trimmed;
    }

    /** 構造化された値の JSON（key の昇順。空なら null）。 */
    public static String structuredJson(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(new TreeMap<>(values));
        } catch (JsonProcessingException e) {
            throw BusinessException.of(400, "error.featureEnablement.signOffStructuredInvalid", "-");
        }
    }

    /** 構造化された値の JSON を読む。 */
    public static Map<String, String> structuredMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<TreeMap<String, String>>() { });
        } catch (JsonProcessingException e) {
            throw BusinessException.of(409, "error.featureEnablement.signOffStructuredInvalid", "-");
        }
    }

    /** SHA-256（小文字 hex）。 */
    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 を使えません", e);
        }
    }

    private void deleteOnRollback(String storedName) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) {
                    return;
                }
                try {
                    AccountingTenantContextHolder.runWithTenant(tenant.tenantId(), () -> fileStorage.delete(storedName));
                } catch (RuntimeException e) {
                    log.warn("[機能の開放] 登録を取り消した署名の文書を消せません（孤児の清掃が消す）: exceptionClass={}",
                            e.getClass().getSimpleName());
                }
            }
        });
    }
}
