package com.ses.controller.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.config.feature.Feature;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.entity.FeatureSignOff;
import com.ses.entity.FeatureSignOffBundle;
import com.ses.featureenablement.service.FeatureEnablementActor;
import com.ses.featureenablement.service.FeatureEnablementActorPolicy;
import com.ses.featureenablement.service.FeatureEnablementApprovalResult;
import com.ses.featureenablement.service.FeatureEnablementQueryService;
import com.ses.featureenablement.service.FeatureEnablementService;
import com.ses.featureenablement.service.FeatureEnablementSettingChangeApprover;
import com.ses.featureenablement.service.FeatureEnablementSettingChangeService;
import com.ses.featureenablement.service.FeatureSignOffBundleService;
import com.ses.featureenablement.service.FeatureSignOffService;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 機能の開放の API（feature-enablement-console design §15.1・FE-RQ-011・012）。全て {@link ApiResult}（文書の取得だけ PDF）。
 *
 * <ul>
 *   <li>管理者だけ: {@code SecurityConfig} が {@code hasRole("管理者")}、{@code MenuPermissionFilter} が {@code feature-enablement.*}
 *       （非管理者の group へ seed しない。{@code isAdminOnlyAction}）。行為者の細かな規則（OIDC＋MFA、break-glass・bootstrap の
 *       session の拒否、有効な管理者の読直し、申請者≠承認者、NF07 の privacy-admin）は {@link FeatureEnablementActorPolicy} と
 *       service が判定する。閲覧も同じ行為者の規則にする（J-13。条件と署名の参照は安全上の構成を表す）。</li>
 *   <li>承認の結果が {@code STALE}・{@code EXPIRED}・{@code CONDITIONS_UNSATISFIED} の時は、service の transaction の commit の後に
 *       409 にする（申請の終了と監査を残す。design §5.3 の review P3-8）。</li>
 *   <li>一覧に署名者の氏名・元の file 名・保存名・digest の全体を返さない（氏名は署名の詳細だけ。FE-AC-011-02）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/feature-enablement")
public class FeatureEnablementApiController {

    /** 署名の組の登録の文書の part の名前の接頭辞（{@code file.<種別>}）。 */
    static final String BUNDLE_FILE_PREFIX = "file.";

    private final FeatureEnablementActorPolicy actorPolicy;
    private final FeatureEnablementService service;
    private final FeatureSignOffService signOffs;
    private final FeatureSignOffBundleService bundles;
    private final FeatureEnablementQueryService query;
    private final ObjectMapper objectMapper;
    /** 設定の変更（{@code SETTING_CHANGE}。FE-B03）。 */
    private final FeatureEnablementSettingChangeService settingChanges;

    public FeatureEnablementApiController(FeatureEnablementActorPolicy actorPolicy, FeatureEnablementService service,
                                          FeatureSignOffService signOffs, FeatureSignOffBundleService bundles,
                                          FeatureEnablementQueryService query, ObjectMapper objectMapper,
                                          FeatureEnablementSettingChangeService settingChanges) {
        this.actorPolicy = actorPolicy;
        this.service = service;
        this.signOffs = signOffs;
        this.bundles = bundles;
        this.query = query;
        this.objectMapper = objectMapper;
        this.settingChanges = settingChanges;
    }

    // ---------------------------------------------------------------- 要求の形

    /**
     * 申請（{@code type}: {@code OPEN}・{@code SIGN_OFF_EXTENSION}・{@code SETTING_CHANGE}。設定の変更は {@code target} が設定の key、
     * {@code value} が新しい値（null・空は未設定へ戻す）。FE-B03）。
     */
    public record OpenRequestBody(String type, String target, String value, String reason) {
    }

    /** 承認・却下・取下げ・確認・取消し（版と理由）。 */
    public record DecisionBody(Integer expectedVersion, String reason) {
    }

    /** 閉鎖（理由の区分。{@code MANUAL_*}）。 */
    public record CloseBody(String reason) {
    }

    /** 証拠の類の延長の申請。 */
    public record ExtensionBody(LocalDate newExpiresOn, String reason) {
    }

    /** 書いた行の参照（氏名を含めない）。 */
    public record Ref(long id, int version, String status) {
    }

    /** 署名の詳細（氏名を含む。元の file 名・保存名を含めず、digest は先頭 16 文字だけ）。 */
    public record SignOffDetail(long id, String kindCode, String kindClass, String reference, String signerRole,
                                String signerName, LocalDate signedOn, LocalDate expiresOn,
                                Map<String, String> structuredValues, String status, int version, Long registeredBy,
                                LocalDateTime registeredAt, Long confirmedBy, LocalDateTime confirmedAt, Long endedBy,
                                LocalDateTime endedAt, String endReason, Long supersededById, LocalDate purgeAfter,
                                String contentSha256Prefix, Long sizeBytes) {
    }

    /** 署名の組の登録（新しい member と引き継ぐ署名。文書は {@code file.<種別>} の part）。 */
    public record BundleRegistrationBody(String bundleKind, List<MemberBody> members, Map<String, Long> carriedOver) {
    }

    public record MemberBody(String kindCode, String reference, String signerRole, String signerName, LocalDate signedOn,
                             LocalDate expiresOn, Map<String, String> structuredValues) {
    }

    // ---------------------------------------------------------------- 開放単位

    @GetMapping("/units")
    public ApiResult<List<FeatureEnablementQueryService.UnitSummary>> units(Authentication authentication,
                                                                            HttpSession session) {
        return ApiResult.success(query.units(actor(authentication, session)));
    }

    @GetMapping("/units/{key}")
    public ApiResult<FeatureEnablementQueryService.UnitDetail> unit(@PathVariable String key,
                                                                    Authentication authentication, HttpSession session) {
        return ApiResult.success(query.unit(key, actor(authentication, session)));
    }

    /** その場で1件を評価する（状態を遷移させない。1分に1回まで）。 */
    @PostMapping("/units/{key}/evaluate")
    public ApiResult<FeatureEnablementQueryService.EvaluationView> evaluate(@PathVariable String key,
                                                                            Authentication authentication,
                                                                            HttpSession session) {
        return ApiResult.success(query.evaluate(key, actor(authentication, session)));
    }

    @PostMapping("/units/{key}/close")
    public ApiResult<Void> close(@PathVariable String key, @RequestBody CloseBody body, Authentication authentication,
                                 HttpSession session) {
        service.close(feature(key), body == null ? null : body.reason(), actor(authentication, session));
        return ApiResult.success(null);
    }

    // ---------------------------------------------------------------- 申請

    @GetMapping("/requests")
    public ApiResult<FeatureEnablementQueryService.PageView<FeatureEnablementQueryService.RequestView>> requests(
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status, Authentication authentication, HttpSession session) {
        return ApiResult.success(query.requests(page, size, status, actor(authentication, session)));
    }

    @PostMapping("/requests")
    public ApiResult<FeatureEnablementQueryService.RequestView> request(@RequestBody OpenRequestBody body,
                                                                        Authentication authentication,
                                                                        HttpSession session) {
        FeatureEnablementActor actor = actor(authentication, session);
        if (body == null || body.type() == null) {
            throw BusinessException.of(400, "error.featureEnablement.requestTypeUnsupported");
        }
        FeatureEnablementRequest created = switch (body.type()) {
            case "OPEN" -> service.requestOpen(feature(body.target()), body.reason(), actor);
            case FeatureSignOffService.REQUEST_TYPE_EXTENSION -> signOffs.requestExtension(extensionTarget(body.target()),
                    date(body.value()), body.reason(), actor);
            case FeatureEnablementSettingChangeApprover.REQUEST_TYPE -> settingChanges.request(body.target(), body.value(),
                    body.reason(), actor);
            default -> throw BusinessException.of(400, "error.featureEnablement.requestTypeUnsupported");
        };
        return ApiResult.success(query.view(created, actor));
    }

    /** 承認で変える設定（画面「設定」tab。今の値・決定の署名の値・未決の申請。FE-B03）。 */
    @GetMapping("/settings")
    public ApiResult<List<FeatureEnablementSettingChangeService.SettingView>> settings(Authentication authentication,
                                                                                      HttpSession session) {
        return ApiResult.success(settingChanges.settings(actor(authentication, session)));
    }

    /** 承認する。結果が承認でなければ（申請の終了・監査は commit 済み）409。 */
    @PostMapping("/requests/{id}/approve")
    public ApiResult<Ref> approve(@PathVariable long id, @RequestBody DecisionBody body, Authentication authentication,
                                  HttpSession session) {
        FeatureEnablementApprovalResult result = service.approve(id, expectedVersion(body), reason(body),
                actor(authentication, session));
        if (!result.approved()) {
            throw BusinessException.of(409, result.outcome().messageKey(), String.join(",", result.codes()));
        }
        return ApiResult.success(new Ref(id, expectedVersion(body) + 1, "APPROVED"));
    }

    @PostMapping("/requests/{id}/reject")
    public ApiResult<Void> reject(@PathVariable long id, @RequestBody DecisionBody body, Authentication authentication,
                                  HttpSession session) {
        service.reject(id, expectedVersion(body), reason(body), actor(authentication, session));
        return ApiResult.success(null);
    }

    @PostMapping("/requests/{id}/withdraw")
    public ApiResult<Void> withdraw(@PathVariable long id, @RequestBody DecisionBody body,
                                    Authentication authentication, HttpSession session) {
        service.withdraw(id, expectedVersion(body), actor(authentication, session));
        return ApiResult.success(null);
    }

    // ---------------------------------------------------------------- 署名

    @GetMapping("/sign-offs")
    public ApiResult<List<FeatureSignOffService.Summary>> signOffs(Authentication authentication, HttpSession session) {
        return ApiResult.success(signOffs.list(actor(authentication, session)));
    }

    @GetMapping("/sign-offs/{id}")
    public ApiResult<SignOffDetail> signOff(@PathVariable long id, Authentication authentication, HttpSession session) {
        return ApiResult.success(detail(signOffs.detail(id, actor(authentication, session))));
    }

    @PostMapping(value = "/sign-offs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<Ref> register(@RequestParam(value = "file", required = false) MultipartFile file,
                                   @RequestParam String kindCode,
                                   @RequestParam(required = false) String reference, @RequestParam String signerRole,
                                   @RequestParam String signerName,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate signedOn,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresOn,
                                   @RequestParam(required = false) String structuredValues,
                                   Authentication authentication, HttpSession session) {
        FeatureEnablementActor actor = actor(authentication, session);
        // file の部を欠いた要求も Spring の既定の 400（MissingServletRequestPartException）にせず、
        // 文書が必要という業務の 400 にそろえる。bytes が先に止めるので file.getOriginalFilename は null にならない。
        byte[] document = bytes(file);
        FeatureSignOff registered = signOffs.register(new FeatureSignOffService.Registration(kindCode, reference,
                        signerRole, signerName, signedOn, expiresOn, structured(structuredValues)), document,
                file.getOriginalFilename(), actor);
        return ApiResult.success(ref(registered));
    }

    @PostMapping("/sign-offs/{id}/confirm")
    public ApiResult<Ref> confirm(@PathVariable long id, @RequestBody DecisionBody body, Authentication authentication,
                                  HttpSession session) {
        return ApiResult.success(ref(signOffs.confirm(id, expectedVersion(body), actor(authentication, session))));
    }

    @PostMapping("/sign-offs/{id}/reject")
    public ApiResult<Void> rejectSignOff(@PathVariable long id, @RequestBody DecisionBody body,
                                         Authentication authentication, HttpSession session) {
        signOffs.reject(id, expectedVersion(body), reason(body), actor(authentication, session));
        return ApiResult.success(null);
    }

    @PostMapping("/sign-offs/{id}/withdraw")
    public ApiResult<Void> withdrawSignOff(@PathVariable long id, @RequestBody DecisionBody body,
                                           Authentication authentication, HttpSession session) {
        signOffs.withdraw(id, expectedVersion(body), actor(authentication, session));
        return ApiResult.success(null);
    }

    @PostMapping("/sign-offs/{id}/revoke")
    public ApiResult<Void> revoke(@PathVariable long id, @RequestBody DecisionBody body, Authentication authentication,
                                  HttpSession session) {
        signOffs.revoke(id, expectedVersion(body), reason(body), actor(authentication, session));
        return ApiResult.success(null);
    }

    /** 証拠の類の延長を申請する（別の管理者が申請の承認で同じ行の失効日を延ばす）。 */
    @PostMapping("/sign-offs/{id}/extend")
    public ApiResult<FeatureEnablementQueryService.RequestView> extend(@PathVariable long id,
                                                                       @RequestBody ExtensionBody body,
                                                                       Authentication authentication,
                                                                       HttpSession session) {
        FeatureEnablementActor actor = actor(authentication, session);
        FeatureEnablementRequest created = signOffs.requestExtension(id, body == null ? null : body.newExpiresOn(),
                body == null ? null : body.reason(), actor);
        return ApiResult.success(query.view(created, actor));
    }

    /** 文書を専用の経路で取得する（大きさと SHA-256 を照合し、取得を監査する）。 */
    @GetMapping("/sign-offs/{id}/document")
    public ResponseEntity<byte[]> document(@PathVariable long id, Authentication authentication, HttpSession session) {
        FeatureSignOffService.Document document = signOffs.document(id, actor(authentication, session));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(document.fileName(), StandardCharsets.UTF_8).build()
                                .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(document.content());
    }

    // ---------------------------------------------------------------- 署名の組

    @GetMapping("/sign-off-bundles")
    public ApiResult<List<FeatureEnablementQueryService.BundleView>> bundles(Authentication authentication,
                                                                             HttpSession session) {
        return ApiResult.success(query.bundles(actor(authentication, session)));
    }

    /**
     * 署名の組を登録する。part {@code request} に {@link BundleRegistrationBody} の JSON、新しい member ごとに文書の part
     * {@code file.<種別>}。
     */
    @PostMapping(value = "/sign-off-bundles", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<Ref> registerBundle(@RequestParam("request") String request, MultipartHttpServletRequest multipart,
                                         Authentication authentication, HttpSession session) {
        FeatureEnablementActor actor = actor(authentication, session);
        BundleRegistrationBody body;
        try {
            body = objectMapper.readValue(request, BundleRegistrationBody.class);
        } catch (JsonProcessingException e) {
            throw BusinessException.of(400, "error.featureEnablement.bundleMembersInvalid", "request");
        }
        List<FeatureSignOffBundleService.NewMember> members = new ArrayList<>();
        for (MemberBody member : body.members() == null ? List.<MemberBody>of() : body.members()) {
            MultipartFile file = member.kindCode() == null ? null
                    : multipart.getFile(BUNDLE_FILE_PREFIX + member.kindCode());
            if (file == null || file.isEmpty()) {
                throw BusinessException.of(400, "error.featureEnablement.signOffDocumentRequired");
            }
            members.add(new FeatureSignOffBundleService.NewMember(new FeatureSignOffService.Registration(
                    member.kindCode(), member.reference(), member.signerRole(), member.signerName(), member.signedOn(),
                    member.expiresOn(), member.structuredValues()), bytes(file), file.getOriginalFilename()));
        }
        FeatureSignOffBundle registered = bundles.register(body.bundleKind(), members,
                body.carriedOver() == null ? Map.of() : body.carriedOver(), actor);
        return ApiResult.success(new Ref(registered.getId(), version(registered.getVersion()), registered.getStatus()));
    }

    @PostMapping("/sign-off-bundles/{id}/confirm")
    public ApiResult<Ref> confirmBundle(@PathVariable long id, @RequestBody DecisionBody body,
                                        Authentication authentication, HttpSession session) {
        FeatureSignOffBundle confirmed = bundles.confirm(id, expectedVersion(body), actor(authentication, session));
        return ApiResult.success(new Ref(confirmed.getId(), version(confirmed.getVersion()), confirmed.getStatus()));
    }

    @PostMapping("/sign-off-bundles/{id}/reject")
    public ApiResult<Void> rejectBundle(@PathVariable long id, @RequestBody DecisionBody body,
                                        Authentication authentication, HttpSession session) {
        bundles.reject(id, expectedVersion(body), reason(body), actor(authentication, session));
        return ApiResult.success(null);
    }

    @PostMapping("/sign-off-bundles/{id}/withdraw")
    public ApiResult<Void> withdrawBundle(@PathVariable long id, @RequestBody DecisionBody body,
                                          Authentication authentication, HttpSession session) {
        bundles.withdraw(id, expectedVersion(body), actor(authentication, session));
        return ApiResult.success(null);
    }

    @PostMapping("/sign-off-bundles/{id}/revoke")
    public ApiResult<Void> revokeBundle(@PathVariable long id, @RequestBody DecisionBody body,
                                        Authentication authentication, HttpSession session) {
        bundles.revoke(id, expectedVersion(body), reason(body), actor(authentication, session));
        return ApiResult.success(null);
    }

    // ---------------------------------------------------------------- 履歴・目録

    @GetMapping("/events")
    public ApiResult<FeatureEnablementQueryService.PageView<FeatureEnablementQueryService.EventView>> events(
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String subjectType, @RequestParam(required = false) String subjectKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication authentication, HttpSession session) {
        return ApiResult.success(query.events(page, size, subjectType, subjectKey, from, to,
                actor(authentication, session)));
    }

    @GetMapping("/catalog")
    public ApiResult<FeatureEnablementQueryService.CatalogView> catalog(Authentication authentication,
                                                                        HttpSession session) {
        return ApiResult.success(query.catalog(actor(authentication, session)));
    }

    // ---------------------------------------------------------------- helper

    private FeatureEnablementActor actor(Authentication authentication, HttpSession session) {
        return actorPolicy.resolve(authentication, session);
    }

    private static Feature feature(String key) {
        if (key == null) {
            throw BusinessException.of(404, "error.featureEnablement.unknownUnit");
        }
        try {
            return Feature.valueOf(key.trim());
        } catch (IllegalArgumentException e) {
            throw BusinessException.of(404, "error.featureEnablement.unknownUnit");
        }
    }

    private static long extensionTarget(String target) {
        String prefix = FeatureSignOffService.EXTENSION_TARGET_PREFIX;
        if (target == null || !target.startsWith(prefix)) {
            throw BusinessException.of(400, "error.featureEnablement.signOffNotFound");
        }
        try {
            return Long.parseLong(target.substring(prefix.length()));
        } catch (NumberFormatException e) {
            throw BusinessException.of(400, "error.featureEnablement.signOffNotFound");
        }
    }

    private static LocalDate date(String value) {
        try {
            return value == null ? null : LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw BusinessException.of(400, "error.featureEnablement.signOffExtensionInvalid", 1095);
        }
    }

    private static int expectedVersion(DecisionBody body) {
        if (body == null || body.expectedVersion() == null || body.expectedVersion() < 0) {
            throw BusinessException.of(400, "error.featureEnablement.requestVersionMismatch");
        }
        return body.expectedVersion();
    }

    private static String reason(DecisionBody body) {
        return body == null ? null : body.reason();
    }

    private Map<String, String> structured(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> values = objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {
            });
            return values == null ? Map.of() : values;
        } catch (JsonProcessingException e) {
            throw BusinessException.of(400, "error.featureEnablement.signOffStructuredInvalid", "structuredValues");
        }
    }

    private static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.of(400, "error.featureEnablement.signOffDocumentRequired");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw BusinessException.of(400, "error.file.readFailed");
        }
    }

    private static Ref ref(FeatureSignOff signOff) {
        return new Ref(signOff.getId(), version(signOff.getVersion()), signOff.getStatus());
    }

    private static int version(Integer version) {
        return version == null ? 0 : version;
    }

    private SignOffDetail detail(FeatureSignOff signOff) {
        Map<String, String> values = new LinkedHashMap<>();
        if (signOff.getStructuredValues() != null && !signOff.getStructuredValues().isBlank()) {
            try {
                Map<String, String> parsed = objectMapper.readValue(signOff.getStructuredValues(),
                        new TypeReference<LinkedHashMap<String, String>>() {
                        });
                if (parsed != null) {
                    // digest の全体を応答に出さない（先頭 16 文字だけ。design §15.2）
                    parsed.forEach((field, value) -> values.put(field,
                            field.toLowerCase(java.util.Locale.ROOT).endsWith("digest") ? prefix(value) : value));
                }
            } catch (JsonProcessingException e) {
                values.put("_", "unreadable");
            }
        }
        return new SignOffDetail(signOff.getId(), signOff.getKindCode(), signOff.getKindClass(), signOff.getReference(),
                signOff.getSignerRole(), signOff.getSignerName(), signOff.getSignedOn(), signOff.getExpiresOn(), values,
                signOff.getStatus(), version(signOff.getVersion()), signOff.getRegisteredBy(), signOff.getRegisteredAt(),
                signOff.getConfirmedBy(), signOff.getConfirmedAt(), signOff.getEndedBy(), signOff.getEndedAt(),
                signOff.getEndReason(), signOff.getSupersededById(), signOff.getPurgeAfter(),
                prefix(signOff.getContentSha256()), signOff.getSizeBytes());
    }

    private static String prefix(String value) {
        return value == null ? null : value.length() <= 16 ? value : value.substring(0, 16);
    }
}
