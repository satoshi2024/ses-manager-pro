package com.ses.config.feature;

import com.ses.service.security.ActionPermissionResolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 機能スイッチの登録漏れガード（Docker・DB不要）。
 *
 * <p>NF配下の入口を「依存するservice package」と「ActionPermissionResolverのresource」の2系統で検出し、
 * {@link Feature} の遮断表へ登録されているかを確認する。新しいNFのAPI root・画面・schedulerを追加して
 * 遮断表へ登録し忘れると、このテストが失敗する。意図的に対象外とする入口は理由付きで下の表へ登録する。
 */
class FeatureSwitchRouteCoverageTest {

    /** NF機能のservice package → 所属機能（controller/schedulerの依存型・自身のpackageで判定）。 */
    private static final Map<String, Feature> NF_SERVICE_PACKAGES = Map.of(
            "com.ses.service.lifecycle", Feature.NF01,
            "com.ses.service.servicedesk", Feature.NF02,
            "com.ses.service.certificationlearninggap", Feature.NF03,
            "com.ses.service.certification", Feature.NF03,
            "com.ses.service.training", Feature.NF03,
            "com.ses.service.skillgap", Feature.NF03,
            "com.ses.service.pwa", Feature.NF04,
            "com.ses.service.report", Feature.NF10);

    /** ActionPermissionResolverのresource名 → 所属機能（API root・画面rootの解決結果で判定）。 */
    private static final Map<String, Feature> NF_ACTION_RESOURCES = Map.of(
            "lifecycle", Feature.NF01,
            "service-desk", Feature.NF02,
            "customer-health", Feature.NF02,
            "customer-qbr", Feature.NF02,
            "certification-learning-gap", Feature.NF03,
            "management-report", Feature.NF10);

    /** NF配下だが意図的に機能スイッチの対象外とする入口（パス区切り前方一致）と理由。 */
    private static final Map<String, String> UNGATED_ROUTE_PREFIXES = Map.of(
            "/api/my/pwa/attendance", "既存の要員勤怠画面の通常保存経路（遮断すると日次入力が止まる）",
            "/api/my/pwa/timesheet", "既存の要員作業報告画面の通常保存経路（遮断すると日次入力が止まる）",
            "/api/my/pwa/expenses", "既存の要員経費画面の下書き保存経路",
            "/api/my/pwa/change-requests", "既存の要員プロフィール変更申請の下書き保存経路",
            "/api/my/session-context", "上記保存経路が使うuser scope bootstrap",
            "/api/my/pwa/restore", "ロック済み下書きの唯一の復元経路（パスワード再認証必須。止めると未同期の入力がlogoutで失われる）");

    /** NF配下だが意図的に機能スイッチの対象外とする静的ファイル（完全一致）と理由。 */
    private static final Map<String, String> UNGATED_STATIC_PATHS = Map.of(
            "/offline.html", "ロック済み下書きの復元画面（日次入力queueの一部。Service Workerが無くても到達できる）",
            "/js/pwa-queue.js", "既存セルフサービス画面の日次入力queue本体");

    /** 機能スイッチで {@code @Scheduled} 入口を止めるscheduler。 */
    private static final Map<String, Feature> GATED_SCHEDULERS = Map.of(
            "com.ses.service.scheduler.LifecycleSlaScheduler", Feature.NF01,
            "com.ses.service.scheduler.CustomerHealthScheduler", Feature.NF02,
            "com.ses.service.scheduler.ServiceSlaScheduler", Feature.NF02,
            "com.ses.service.scheduler.ServiceRequestAttachmentCompensationScheduler", Feature.NF02,
            "com.ses.service.certification.CertificationExpiryNotificationScheduler", Feature.NF03,
            "com.ses.service.scheduler.ManagementReportScheduler", Feature.NF10);

    /**
     * 子スイッチ → その遮断表に一致してよいcontroller（simple name）。入口を持たない子は空集合。
     * 子スイッチの遮断範囲を広げる・狭めるときはここを更新する（意図しない入口の遮断を検出するため）。
     */
    private static final Map<Feature, Set<String>> CHILD_FEATURE_CONTROLLERS = Map.of(
            Feature.NF02_CUSTOMER_PORTAL, Set.of("PortalCustomerServiceDeskApiController"),
            Feature.NF03_CERTIFICATION_PII, Set.of());

    /** NF配下だが意図的に止めないscheduler（理由必須）。 */
    private static final Map<String, String> UNGATED_SCHEDULERS = Map.of(
            "com.ses.service.pwa.PwaClientMutationCleanupScheduler",
            "常時有効な保存経路が書くidempotency ledgerの保持期限を守るため、NF-04 OFF中も削除を続ける");

    @Test
    void NF配下のcontroller入口は全て対応する機能スイッチへ登録されている() throws Exception {
        List<Route> routes = controllerRoutes();
        List<String> violations = new ArrayList<>();
        Set<Feature> ownedFeatures = EnumSet.noneOf(Feature.class);

        for (Route route : routes) {
            Set<Feature> owners = owners(route);
            if (owners.isEmpty()) {
                continue;
            }
            ownedFeatures.addAll(owners);
            String path = route.normalized();
            if (isUngated(path)) {
                continue;
            }
            Optional<Feature> gate = Feature.forPath(path);
            // 子スイッチの入口は親NFに属する（所属は最上位の機能で比較する）。
            if (owners.size() != 1 || gate.isEmpty() || !owners.contains(gate.get().root())) {
                violations.add(route.controller().getSimpleName() + " " + route.path()
                        + " 所属=" + owners + " 遮断=" + gate.map(Enum::name).orElse("なし"));
            }
        }

        assertThat(routes).as("controller入口を1件も検出できない").isNotEmpty();
        assertThat(ownedFeatures).as("全NFの入口を検出できること（検出規則の空振り防止）")
                .containsExactlyInAnyOrderElementsOf(Feature.topLevel());
        assertThat(violations)
                .as("NF配下の入口がFeatureの遮断表に未登録です。Featureへprefixを追加するか、"
                        + "意図的な対象外なら理由付きでUNGATED_ROUTE_PREFIXESへ登録してください: %s", violations)
                .isEmpty();
    }

    @Test
    void 遮断表に一致するcontroller入口は全てその機能に属し非NFの入口を誤遮断しない() throws Exception {
        List<Route> routes = controllerRoutes();
        List<String> misBlocked = new ArrayList<>();
        int gatedRoutes = 0;

        for (Route route : routes) {
            Optional<Feature> gate = Feature.forPath(route.normalized());
            if (gate.isEmpty()) {
                continue;
            }
            gatedRoutes++;
            Set<Feature> owners = owners(route);
            if (!owners.contains(gate.get().root())) {
                misBlocked.add(route.controller().getSimpleName() + " " + route.path()
                        + " 遮断=" + gate.get() + " 所属=" + owners);
            }
        }

        assertThat(gatedRoutes).as("遮断対象のcontroller入口を1件も検出できない").isPositive();
        assertThat(misBlocked)
                .as("機能スイッチの遮断表がNF外（または別NF）のcontroller入口に一致しています。"
                        + "OFF時に既存機能が404になるため、prefixを見直してください: %s", misBlocked)
                .isEmpty();
    }

    @Test
    void 子スイッチの遮断表は意図したcontrollerだけに一致し顧客ポータルのNF02入口は全て子スイッチに属する() throws Exception {
        List<Route> routes = controllerRoutes();
        Map<Feature, Set<String>> actual = new TreeMap<>();
        for (Feature feature : Feature.values()) {
            if (!feature.isTopLevel()) {
                actual.put(feature, new TreeSet<>());
            }
        }
        List<String> violations = new ArrayList<>();

        for (Route route : routes) {
            String path = route.normalized();
            Optional<Feature> gate = Feature.forPath(path);
            if (gate.isPresent() && !gate.get().isTopLevel()) {
                actual.get(gate.get()).add(route.controller().getSimpleName());
            }
            // 顧客ポータル配下のNF-02入口が親スイッチだけで公開されると、初回リリースで顧客に問い合わせが開く。
            if (path.startsWith("/api/portal/") && owners(route).contains(Feature.NF02)
                    && !gate.equals(Optional.of(Feature.NF02_CUSTOMER_PORTAL))) {
                violations.add(route.controller().getSimpleName() + " " + route.path()
                        + " 遮断=" + gate.map(Enum::name).orElse("なし"));
            }
        }

        assertThat(actual).as("子スイッチが一致するcontroller（意図しない入口を子スイッチで止めていないか）")
                .isEqualTo(CHILD_FEATURE_CONTROLLERS);
        assertThat(violations)
                .as("顧客ポータルのNF-02入口がNF02_CUSTOMER_PORTALで遮断されていません: %s", violations)
                .isEmpty();
        for (Feature child : CHILD_FEATURE_CONTROLLERS.keySet()) {
            assertThat(child.parent()).as("%s の親", child).isPresent();
            assertThat(child.menuKeys()).as("子スイッチはメニューを持たない: %s", child).isEmpty();
        }
    }

    @Test
    void 遮断表に一致する静的ファイルは意図して登録した完全一致pathだけである() throws IOException {
        Path staticRoot = Path.of("src/main/resources/static");
        List<String> misBlocked = new ArrayList<>();
        try (Stream<Path> files = Files.walk(staticRoot)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String path = "/" + staticRoot.relativize(file).toString().replace('\\', '/');
                Optional<Feature> gate = Feature.forPath(path);
                if (gate.isPresent() && !gate.get().exactPaths().contains(path)) {
                    misBlocked.add(path + " 遮断=" + gate.get());
                }
            }
        }
        for (Map.Entry<String, String> ungated : UNGATED_STATIC_PATHS.entrySet()) {
            assertThat(new ClassPathResource("static" + ungated.getKey()).exists())
                    .as("対象外の静的ファイル %s が存在しない（古い登録）", ungated.getKey()).isTrue();
            assertThat(Feature.forPath(ungated.getKey())).as("対象外 %s が遮断表と矛盾", ungated.getKey()).isEmpty();
        }

        assertThat(misBlocked).as("意図しない静的ファイルが機能スイッチで遮断される: %s", misBlocked).isEmpty();
    }

    @Test
    void 遮断表のprefixは実在する入口に対応し機能間で重複しない() throws Exception {
        Set<String> routePaths = controllerRoutes().stream().map(Route::normalized).collect(Collectors.toSet());

        for (Feature feature : Feature.values()) {
            for (String prefix : feature.apiPrefixes()) {
                assertThat(prefix).as(feature + " API prefix").startsWith("/api/");
                assertThat(routePaths.stream().anyMatch(path -> Feature.matchesSegmentPrefix(path, prefix)))
                        .as("%s の %s に対応するcontroller入口がない（古い登録）", feature, prefix).isTrue();
            }
            for (String prefix : feature.pagePrefixes()) {
                assertThat(prefix).as(feature + " 画面prefix").doesNotStartWith("/api/");
                assertThat(routePaths.stream().anyMatch(path -> Feature.matchesSegmentPrefix(path, prefix)))
                        .as("%s の %s に対応するcontroller入口がない（古い登録）", feature, prefix).isTrue();
            }
            for (String exact : feature.exactPaths()) {
                assertThat(new ClassPathResource("static" + exact).exists())
                        .as("%s の静的入口 %s が存在しない", feature, exact).isTrue();
            }
        }
        for (Feature left : Feature.values()) {
            for (Feature right : Feature.values()) {
                if (left == right) {
                    continue;
                }
                for (String a : entries(left)) {
                    for (String b : entries(right)) {
                        assertThat(Feature.matchesSegmentPrefix(a, b) || Feature.matchesSegmentPrefix(b, a))
                                .as("%s の %s と %s の %s が重複", left, a, right, b).isFalse();
                    }
                }
            }
        }
        for (String ungated : UNGATED_ROUTE_PREFIXES.keySet()) {
            assertThat(Feature.forPath(ungated)).as("対象外 %s が遮断表と矛盾", ungated).isEmpty();
            assertThat(routePaths.stream().anyMatch(path -> Feature.matchesSegmentPrefix(path, ungated)))
                    .as("対象外 %s に対応する入口がない（古い登録）", ungated).isTrue();
        }
    }

    @Test
    void NF配下のschedulerは遮断対象か理由付きの対象外として分類されている() throws Exception {
        Map<String, Set<Feature>> nfSchedulers = new TreeMap<>();
        for (Class<?> type : scheduledClasses()) {
            Set<Feature> owners = ownersByDependency(type);
            if (!owners.isEmpty()) {
                nfSchedulers.put(type.getName(), owners);
            }
        }

        Set<String> unclassified = new TreeSet<>(nfSchedulers.keySet());
        unclassified.removeAll(GATED_SCHEDULERS.keySet());
        unclassified.removeAll(UNGATED_SCHEDULERS.keySet());
        assertThat(unclassified)
                .as("NF配下の@Scheduledが未分類です。入口でFeatureSwitches.allowsを確認してGATED_SCHEDULERSへ、"
                        + "止めない場合は理由付きでUNGATED_SCHEDULERSへ登録してください: %s", unclassified)
                .isEmpty();
        for (Map.Entry<String, Feature> gated : GATED_SCHEDULERS.entrySet()) {
            assertThat(nfSchedulers).as("古い登録: %s", gated.getKey()).containsKey(gated.getKey());
            assertThat(nfSchedulers.get(gated.getKey())).containsExactly(gated.getValue());
            assertThat(hasFeatureSwitchesField(Class.forName(gated.getKey())))
                    .as("%s はFeatureSwitchesを注入して入口で判定すること", gated.getKey()).isTrue();
        }
        for (String ungated : UNGATED_SCHEDULERS.keySet()) {
            assertThat(nfSchedulers).as("古い登録: %s", ungated).containsKey(ungated);
            assertThat(hasFeatureSwitchesField(Class.forName(ungated))).as(ungated).isFalse();
        }
    }

    @Test
    void サイドバーのNF導線はその機能のmenuKeyで表示制御されている() throws IOException {
        String sidebar = read("src/main/resources/templates/layout/sidebar.html");
        Pattern hrefPattern = Pattern.compile("th:href=\"@\\{([^}]+)}\"");
        Pattern keyPattern = Pattern.compile("allowedMenus\\.contains\\('([^']+)'\\)");
        List<String> violations = new ArrayList<>();
        Set<Feature> linkedFeatures = EnumSet.noneOf(Feature.class);

        for (String chunk : sidebar.split("<li")) {
            int end = chunk.indexOf("</li>");
            String item = end >= 0 ? chunk.substring(0, end) : chunk;
            Matcher href = hrefPattern.matcher(item);
            while (href.find()) {
                Optional<Feature> feature = Feature.forPath(href.group(1));
                if (feature.isEmpty()) {
                    continue;
                }
                linkedFeatures.add(feature.get());
                Set<String> keys = new HashSet<>();
                Matcher key = keyPattern.matcher(item);
                while (key.find()) {
                    keys.add(key.group(1));
                }
                if (keys.stream().noneMatch(feature.get().menuKeys()::contains)) {
                    violations.add(href.group(1) + " menuKey=" + keys);
                }
            }
        }

        assertThat(linkedFeatures).containsExactlyInAnyOrder(Feature.NF01, Feature.NF02, Feature.NF03, Feature.NF10);
        assertThat(violations).as("NF導線がFeature.menuKeys以外の条件で表示されている: %s", violations).isEmpty();
    }

    @Test
    void サイドバー以外の埋込み導線も機能スイッチで表示制御されている() throws IOException {
        String base = read("src/main/resources/templates/layout/base.html");
        String portal = read("src/main/resources/templates/portal/customer/index.html");
        String engineerDetail = read("src/main/resources/templates/engineer/detail.html");
        String common = read("src/main/resources/static/js/common.js");
        String myCertification = read("src/main/resources/templates/my/certification-learning-skill-gap.html");
        String myCertificationJs = read("src/main/resources/static/js/modules/my-certification-learning-skill-gap.js");

        // NF-04: manifestとSW登録フラグだけ。下書き復元リンクはOFFでも出す（ロック済み下書きの復元経路）
        assertThat(count(base, "disabledFeatures.contains('nf04')")).isEqualTo(2);
        assertThat(base).contains("name=\"ses-pwa-shell\" content=\"off\"");
        assertThat(base).contains("<a id=\"pwa-restore-link\" href=\"/offline.html\"");
        assertThat(common).contains("shellDisabled: function()", "this.unregisterShell();", "'ses-pwa-shell-'");
        // NF-02の子（顧客ポータルの問い合わせ）: portalの問い合わせタブ・画面・モーダル2件・script。
        // 親キー（nf02）だけで判定するとNF-02 ON・子OFFのときに顧客へ問い合わせ導線が出るため、子キーだけで判定する。
        assertThat(count(portal, "disabledFeatures.contains('nf02-customer-portal')")).isEqualTo(5);
        assertThat(count(portal, "disabledFeatures.contains('nf02')")).isZero();
        assertThat(portal).contains("if (typeof PortalServiceDesk !== 'undefined') PortalServiceDesk.init();");
        // NF-03の子（資格番号の入力）: 本人画面の資格番号入力欄1件。JSは入力欄の有無で申請・再申請の送信値を切り替える
        assertThat(count(myCertification, "disabledFeatures.contains('nf03-certification-pii')")).isEqualTo(1);
        assertThat(count(myCertification, "id=\"my-cert-number\"")).isEqualTo(1);
        assertThat(myCertification).contains("<input th:unless=\"${disabledFeatures != null and "
                + "disabledFeatures.contains('nf03-certification-pii')}\" id=\"my-cert-number\"");
        assertThat(myCertificationJs).contains("certificateNumberEntryEnabled() ? certificateNumberInput().value : null",
                "certificateNumber: withNumber ? result.value : null");
        assertThat(count(myCertificationJs, "getElementById('my-cert-number')")).isEqualTo(1);
        // NF-01: 要員詳細のライフサイクル欄
        assertThat(count(engineerDetail, "disabledFeatures.contains('nf01')")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private record Route(Class<?> controller, String path) {
        String normalized() {
            return path.replaceAll("\\{[^}]*}", "1");
        }
    }

    private static boolean isUngated(String path) {
        return UNGATED_ROUTE_PREFIXES.keySet().stream().anyMatch(prefix -> Feature.matchesSegmentPrefix(path, prefix));
    }

    private static List<String> entries(Feature feature) {
        return Stream.of(feature.pagePrefixes(), feature.apiPrefixes(), feature.exactPaths())
                .flatMap(List::stream).toList();
    }

    private static Set<Feature> owners(Route route) {
        Set<Feature> owners = ownersByDependency(route.controller());
        Feature byResource = ownerByActionResource(route.normalized());
        if (byResource != null) {
            owners.add(byResource);
        }
        return owners;
    }

    /** 自身のpackageと、field・constructor引数の型（generic引数を含む）がNF service packageに属するか。 */
    private static Set<Feature> ownersByDependency(Class<?> type) {
        Set<Feature> owners = EnumSet.noneOf(Feature.class);
        ownerOfPackage(type.getPackageName()).ifPresent(owners::add);
        for (Field field : type.getDeclaredFields()) {
            collectOwners(field.getGenericType(), owners);
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            for (Type parameter : constructor.getGenericParameterTypes()) {
                collectOwners(parameter, owners);
            }
        }
        return owners;
    }

    private static void collectOwners(Type type, Set<Feature> owners) {
        if (type instanceof Class<?> clazz) {
            Class<?> component = clazz;
            while (component.isArray()) {
                component = component.getComponentType();
            }
            if (!component.isPrimitive()) {
                ownerOfPackage(component.getPackageName()).ifPresent(owners::add);
            }
        } else if (type instanceof ParameterizedType parameterized) {
            collectOwners(parameterized.getRawType(), owners);
            for (Type argument : parameterized.getActualTypeArguments()) {
                collectOwners(argument, owners);
            }
        }
    }

    private static Optional<Feature> ownerOfPackage(String packageName) {
        return NF_SERVICE_PACKAGES.entrySet().stream()
                .filter(entry -> packageName.equals(entry.getKey()) || packageName.startsWith(entry.getKey() + "."))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /** API root・画面root（本人向け /my 配下は管理側rootへ読み替え）のaction resourceで所属を判定する。 */
    private static Feature ownerByActionResource(String path) {
        for (String candidate : List.of(path, withoutMyPrefix(path))) {
            String root = rootOf(candidate);
            if (root == null) {
                continue;
            }
            String action = ActionPermissionResolver.resolve("GET", root);
            if (action == null) {
                continue;
            }
            int dot = action.indexOf('.');
            Feature feature = NF_ACTION_RESOURCES.get(dot < 0 ? action : action.substring(0, dot));
            if (feature != null) {
                return feature;
            }
        }
        return null;
    }

    private static String withoutMyPrefix(String path) {
        if (path.startsWith("/api/my/")) {
            return "/api/" + path.substring("/api/my/".length());
        }
        if (path.startsWith("/my/")) {
            return "/" + path.substring("/my/".length());
        }
        return path;
    }

    private static String rootOf(String path) {
        String[] segments = path.split("/");
        if (path.startsWith("/api/")) {
            return segments.length > 2 && !segments[2].isEmpty() ? "/api/" + segments[2] : null;
        }
        return segments.length > 1 && !segments[1].isEmpty() ? "/" + segments[1] : null;
    }

    private static List<Route> controllerRoutes() throws Exception {
        List<Route> routes = new ArrayList<>();
        for (Class<?> type : mainClasses("classpath*:com/ses/controller/**/*.class")) {
            if (!AnnotatedElementUtils.hasAnnotation(type, Controller.class)) {
                continue;
            }
            String[] classPaths = paths(AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class));
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                for (String base : classPaths) {
                    for (String sub : paths(mapping)) {
                        routes.add(new Route(type, join(base, sub)));
                    }
                }
            }
        }
        return routes;
    }

    private static String[] paths(RequestMapping mapping) {
        return mapping == null || mapping.path().length == 0 ? new String[]{""} : mapping.path();
    }

    private static String join(String base, String sub) {
        String left = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (sub.isEmpty()) {
            return left.isEmpty() ? "/" : left;
        }
        return left + (sub.startsWith("/") ? sub : "/" + sub);
    }

    /** {@code @Scheduled} メソッドを持つ本番class（ASMで絞り込んでからloadする）。 */
    private static List<Class<?>> scheduledClasses() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);
        List<Class<?>> classes = new ArrayList<>();
        for (Resource resource : mainClassResources(resolver, "classpath*:com/ses/**/*.class")) {
            MetadataReader reader = factory.getMetadataReader(resource);
            if (reader.getAnnotationMetadata().hasAnnotatedMethods(Scheduled.class.getName())) {
                classes.add(Class.forName(reader.getClassMetadata().getClassName(), false,
                        Thread.currentThread().getContextClassLoader()));
            }
        }
        assertThat(classes).as("@Scheduled classを1件も検出できない").isNotEmpty();
        return classes;
    }

    private static List<Class<?>> mainClasses(String pattern) throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Class<?>> classes = new ArrayList<>();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        for (Resource resource : mainClassResources(resolver, pattern)) {
            String url = resource.getURL().toString().replace('\\', '/');
            String fqn = url.substring(url.indexOf("com/ses/")).replace('/', '.').replace(".class", "");
            classes.add(Class.forName(fqn, false, classLoader));
        }
        return classes;
    }

    /** test-classes・匿名/内部classを除いた本番classのresource。 */
    private static List<Resource> mainClassResources(PathMatchingResourcePatternResolver resolver, String pattern)
            throws IOException {
        List<Resource> resources = new ArrayList<>();
        for (Resource resource : resolver.getResources(pattern)) {
            if (!resource.isReadable()) {
                continue;
            }
            String url = resource.getURL().toString().replace('\\', '/');
            String filename = resource.getFilename();
            if (url.contains("/test-classes/") || url.contains("-tests.jar") || !url.contains("com/ses/")
                    || filename == null || filename.contains("$")) {
                continue;
            }
            resources.add(resource);
        }
        return resources;
    }

    private static boolean hasFeatureSwitchesField(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).anyMatch(field -> field.getType() == FeatureSwitches.class);
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }
}
