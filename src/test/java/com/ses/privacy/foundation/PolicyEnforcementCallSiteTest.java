package com.ses.privacy.foundation;

import com.ses.testsupport.JavaSourceText;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NF07-NF08-LINK の「送信の許可」を作れる場所を main の source で固定する（RB-NF08-LINK review P3-1）。
 *
 * <p>許可の判定（{@link PolicyEnforcementDecision} の許可）は外部送信を許す根拠になる。REG・REL の検証結果と同じく、作れる場所を
 * 次の3段で閉じる。
 * <ul>
 *   <li>型: {@code PolicyEnforcementDecision} は public な constructor を持たず、{@code permit()} は package-private（compile で
 *       foundation の外から作れない）。foundation の中で {@code permit()} を呼べるのは本番の内部判定
 *       {@link ProductionPolicyEnforcementService} だけ。</li>
 *   <li>port: main で {@link PolicyEnforcementPort} を参照・実装できる class を一覧に固定する（一覧外の class が偽の port を作って
 *       gate へ渡す・本番の port を別の key で問い合わせ直す経路を作らせない）。</li>
 *   <li>NF08: {@code AiExternalSendPrivacyPolicyCheck} と、check を受け取る {@code AiProductionApprovalGate} の constructor は
 *       package-private。check を作れるのは gate だけで、gate を手動構築できる場所（check を受け取らない拒否だけの constructor）も
 *       一覧に固定する。gate は final で、継承（{@code extends AiProductionApprovalGate}）の一覧は空（RB-NF08-LINK delta review
 *       D-P3-1）。呼出し・構築の規則は method 参照（{@code ::permit}・{@code ::new}）も拾う。</li>
 * </ul>
 * test の source（{@code PolicyEnforcementTestDecisions}・{@code AiExternalSendApprovalFixtures}）は main の classpath に入らないため
 * 対象外。<b>静的な走査で検出しないもの</b>: reflection（{@code setAccessible} 等）による呼出し。一覧を変えるときは review を通す。
 */
class PolicyEnforcementCallSiteTest {

    private static final Pattern PORT_REFERENCE = Pattern.compile("\\bPolicyEnforcementPort\\b");
    private static final Pattern PORT_IMPLEMENTATION = Pattern.compile("\\bimplements\\s+[^{]*\\bPolicyEnforcementPort\\b");
    // 呼出しと構築は method 参照（::permit・::new）でも書けるため、どちらの形も同じ規則で拾う（RB-NF08-LINK delta review D-P3-1）。
    private static final Pattern PERMIT_CALL = Pattern.compile(
            "\\bPolicyEnforcementDecision\\s*(?:\\.\\s*permit\\s*\\(|::\\s*permit\\b)");
    private static final Pattern PERMIT_STATIC_IMPORT = Pattern.compile(
            "import\\s+static\\s+[\\w.]*PolicyEnforcementDecision\\s*\\.\\s*(?:permit|\\*)\\s*;");
    private static final Pattern DECISION_CONSTRUCTION = Pattern.compile(
            "\\bnew\\s+PolicyEnforcementDecision\\s*\\(|\\bPolicyEnforcementDecision\\s*::\\s*new\\b");
    private static final Pattern CHECK_CONSTRUCTION = Pattern.compile(
            "\\bnew\\s+AiExternalSendPrivacyPolicyCheck\\s*\\(|\\bAiExternalSendPrivacyPolicyCheck\\s*::\\s*new\\b");
    private static final Pattern GATE_CONSTRUCTION = Pattern.compile(
            "\\bnew\\s+AiProductionApprovalGate\\s*\\(|\\bAiProductionApprovalGate\\s*::\\s*new\\b");
    /** gate を継承して承認判定を上書きする class（final にしたため compile も通らないが、一覧でも空に固定する）。 */
    private static final Pattern GATE_SUBCLASS = Pattern.compile("\\bextends\\s+(?:[\\w.]+\\.)?AiProductionApprovalGate\\b");

    /** 規則ごとの、main で一致してよい file（src/main/java の相対 path）。一覧と実際の出現が完全に一致することを求める。 */
    private static final Map<Pattern, Set<String>> ALLOWED = new LinkedHashMap<>();

    static {
        ALLOWED.put(PORT_REFERENCE, Set.of(
                "com/ses/privacy/foundation/PolicyEnforcementPort.java",
                "com/ses/privacy/foundation/ProductionPolicyEnforcementService.java",
                "com/ses/privacy/foundation/ProductionPrivacyComponents.java",
                "com/ses/privacy/production/PrivacyPolicyEnforcementConfiguration.java",
                "com/ses/privacy/production/retention/policy/UnavailablePolicyEnforcementPort.java",
                "com/ses/service/ai/AiProductionApprovalGate.java",
                "com/ses/service/ai/AiExternalSendPrivacyPolicyCheck.java"));
        ALLOWED.put(PORT_IMPLEMENTATION, Set.of(
                "com/ses/privacy/foundation/ProductionPolicyEnforcementService.java",
                "com/ses/privacy/production/retention/policy/UnavailablePolicyEnforcementPort.java"));
        ALLOWED.put(PERMIT_CALL, Set.of("com/ses/privacy/foundation/ProductionPolicyEnforcementService.java"));
        ALLOWED.put(PERMIT_STATIC_IMPORT, Set.of());
        ALLOWED.put(DECISION_CONSTRUCTION, Set.of("com/ses/privacy/foundation/PolicyEnforcementDecision.java"));
        ALLOWED.put(CHECK_CONSTRUCTION, Set.of(
                "com/ses/service/ai/AiExternalSendPrivacyPolicyCheck.java",
                "com/ses/service/ai/AiProductionApprovalGate.java"));
        // 手動構築は check を受け取らない（NF07 の判定が常に拒否の）constructor だけ。呼出し元を足すときは review を通す。
        ALLOWED.put(GATE_CONSTRUCTION, Set.of(
                "com/ses/config/AiProductionSafetyValidator.java",
                "com/ses/service/ai/copilot/CopilotFeatureGate.java"));
        ALLOWED.put(GATE_SUBCLASS, Set.of());
    }

    @Test
    void 送信の許可を作れる場所と内部判定portを参照できる場所はmainで固定した一覧だけ() throws Exception {
        Path root = Path.of("src/main/java");
        Map<Pattern, Set<String>> found = new LinkedHashMap<>();
        ALLOWED.keySet().forEach(pattern -> found.put(pattern, new TreeSet<>()));
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                String source = JavaSourceText.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                for (Pattern pattern : ALLOWED.keySet()) {
                    if (pattern.matcher(source).find()) {
                        found.get(pattern).add(relative);
                    }
                }
            }
        }
        for (Map.Entry<Pattern, Set<String>> entry : ALLOWED.entrySet()) {
            assertEquals(new TreeSet<>(entry.getValue()), found.get(entry.getKey()),
                    "許可されていない場所、または一覧に無い場所があります: " + entry.getKey().pattern());
        }
    }

    @Test
    void 許可はpublicに作れずcheckとcheckを受け取るgateのconstructorはpackage_private() throws Exception {
        assertEquals(0, PolicyEnforcementDecision.class.getConstructors().length);
        assertFalse(java.lang.reflect.Modifier.isPublic(
                PolicyEnforcementDecision.class.getDeclaredMethod("permit").getModifiers()));
        Class<?> check = Class.forName("com.ses.service.ai.AiExternalSendPrivacyPolicyCheck");
        assertEquals(0, check.getConstructors().length, "checkにpublic constructorがない");
        Class<?> gate = Class.forName("com.ses.service.ai.AiProductionApprovalGate");
        for (java.lang.reflect.Constructor<?> constructor : gate.getConstructors()) {
            assertFalse(java.util.Arrays.asList(constructor.getParameterTypes()).contains(check),
                    "checkを受け取るgateのconstructorはpublicにしない");
        }
        assertTrue(java.lang.reflect.Modifier.isFinal(gate.getModifiers()),
                "gateはfinal（継承して承認判定のpublic methodを上書きした別beanを作らせない。D-P3-1）");
    }

    @Test
    void 検出規則は呼出しと実装と静的importを拾いcommentと別名は拾わない() {
        assertTrue(PERMIT_CALL.matcher("return PolicyEnforcementDecision . permit();").find());
        assertTrue(PERMIT_STATIC_IMPORT.matcher(
                "import static com.ses.privacy.foundation.PolicyEnforcementDecision.permit;").find());
        assertTrue(PERMIT_STATIC_IMPORT.matcher(
                "import static com.ses.privacy.foundation.PolicyEnforcementDecision.*;").find());
        assertTrue(PORT_IMPLEMENTATION.matcher("final class X implements Foo,\n    PolicyEnforcementPort {").find());
        assertFalse(PORT_REFERENCE.matcher("class UnavailablePolicyEnforcementPortHolder {}").find());
        assertFalse(PERMIT_CALL.matcher(JavaSourceText.stripComments(
                "/** {@link PolicyEnforcementDecision.permit()} */ int x;")).find());
        assertTrue(CHECK_CONSTRUCTION.matcher("new AiExternalSendPrivacyPolicyCheck(() -> port, () -> null)").find());
        assertTrue(GATE_CONSTRUCTION.matcher("new AiProductionApprovalGate(config)").find());
        // method 参照（D-P3-1）
        assertTrue(PERMIT_CALL.matcher("Supplier<PolicyEnforcementDecision> s = PolicyEnforcementDecision::permit;").find());
        assertTrue(PERMIT_CALL.matcher("PolicyEnforcementDecision :: permit").find());
        assertFalse(PERMIT_CALL.matcher("PolicyEnforcementDecision::permitted").find(), "読取りの accessor は拾わない");
        assertTrue(DECISION_CONSTRUCTION.matcher("map(PolicyEnforcementDecision::new)").find());
        assertTrue(CHECK_CONSTRUCTION.matcher("Factory f = AiExternalSendPrivacyPolicyCheck::new;").find());
        assertTrue(GATE_CONSTRUCTION.matcher("Function<AiConfig, AiProductionApprovalGate> f = AiProductionApprovalGate::new;")
                .find());
        assertTrue(GATE_SUBCLASS.matcher("class Lenient extends AiProductionApprovalGate {").find());
        assertTrue(GATE_SUBCLASS.matcher("class Lenient extends com.ses.service.ai.AiProductionApprovalGate {").find());
        assertFalse(GATE_SUBCLASS.matcher("class X extends AiProductionApprovalGateSupport {").find());
    }
}
