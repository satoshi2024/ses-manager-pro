package com.ses.config;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.Lifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XA-5（テスト側）: {@code app.scheduling.enabled=false} のとき、{@code @Scheduled} methodが登録も実行も
 * されないことを示す。全停止配備で旧instanceのschedulerを先に止め（drain）、migration jobを単独で流す前提。
 *
 * <p>機構（{@link SchedulingConfig}）: {@code @EnableScheduling} を
 * {@code @ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)}
 * で包んでいる。falseのときは {@link ScheduledAnnotationBeanPostProcessor} が登録されないため、
 * {@code @Scheduled} を持つbean自体は生成されても、methodはtaskとして登録されない。
 * <b>未設定は有効扱い（matchIfMissing=true）</b>なので、drainには明示的なfalse指定が必要である。
 *
 * <p>test profileとの関係: {@code application-test.yml} が既にfalseを設定しているため、実アプリcontextの
 * 検証だけでは「最初から止まっている」ことしか示せない。そこで本testは三層で示す。
 * <ol>
 *   <li>最小context（{@link SchedulingConfig} + probe bean）で true / 未設定 / false / 環境変数false を対照し、
 *       同じ観測方法が「登録され実行される」状態を検出できることを先に確かめる（検証の非空虚性）。</li>
 *   <li>実アプリcontext（test profile = false）で、登録機構・scheduler・登録済みtaskが無く、
 *       {@code @Scheduled} を持つbeanは実在することを確かめる。</li>
 *   <li>本番classのbytecodeを走査し、このスイッチの対象外で動きうるもの（自前のthread/executor生成、
 *       起動時hook、{@code @Async}）を既知一覧と突き合わせる。一覧に無いものが増えたら失敗し、
 *       drain手順の見直しを要求する。対象外とする理由は一覧の理由欄に記す。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SchedulingDisabledDrainTest {

    private static final String PROPERTY = "app.scheduling.enabled";

    /** 無効時の不発観測窓。probeは20ms間隔なので、有効なら窓内に必ず発火する。 */
    private static final long DISABLED_OBSERVATION_MILLIS = 500;

    /**
     * 自前でthread/executorを生成・起動する呼出し（{@code app.scheduling.enabled} の外）。
     * key = 最上位class名 + ":" + 呼出し先API。
     */
    private static final Map<String, String> THREAD_API_INVENTORY = inventory(
            "com.ses.config.AsyncConfig:ThreadPoolTaskExecutor.<init>",
            "@Async用taskExecutor。周期実行は持たない。shutdown時はwaitForTasksToCompleteOnShutdown=true・最大30秒",
            "com.ses.service.ai.impl.CopilotBoundedHttpTransport:CloseableHttpAsyncClient.start",
            "HttpClient5のI/O reactor（@Lazy bean、Copilot送信時に初期化）。周期実行は持たない",
            "com.ses.service.integrationhub.LoopbackTlsIntegrationHubWebhookTransportCandidate:CloseableHttpAsyncClient.start",
            "NF05段階A候補のHttpClient5 I/O reactor。package-privateでBean未登録、testからのみ生成する。周期実行は持たず、close()で停止する",
            "com.ses.service.skillgap.AiLearningCandidateServiceImpl:CompletableFuture.supplyAsync",
            "request内のAI呼出をcommonPoolでtimeout付き実行する。timeout後もtaskは継続しうるがshutdown待ちは無い");

    /** 起動時に一度だけ動くhook（{@code app.scheduling.enabled} の外。各自の条件で起動する）。 */
    private static final Map<String, String> STARTUP_HOOK_INVENTORY = inventory(
            "com.ses.config.ContractDocumentBackfillRunner:ApplicationRunner",
            "app.upload.contract-backfill-enabled（未設定=有効）。契約PDFのmetadataを起動時にbackfillする（DB書込あり）",
            "com.ses.config.ContractDocumentDispatchBackfill:ApplicationRunner",
            "app.contract-document.backfill-enabled（未設定=有効）。契約書配送工程を起動時に分類する（DB書込あり）",
            "com.ses.config.LegacyUploadMigrationRunner:ApplicationRunner",
            "app.upload.legacy-migration-enabled（未設定=有効）。旧uploadを起動時に移行する（file移動・DB書込あり）",
            "com.ses.config.ProductionSecurityConfigurationValidator:ApplicationRunner",
            "prod profileだけ。設定検証のみ",
            "com.ses.service.migration.DocumentMigrationScript:CommandLineRunner",
            "起動引数 --migrate-documents のときだけ文書台帳移行を行う",
            "com.ses.config.LegalEntityReadinessStartupValidator#validate:EventListener",
            "ApplicationReadyEvent。公開API/Copilot有効時だけ法人readinessを読取検証する",
            "com.ses.service.impl.BpCompanyServiceImpl#validateBankKeyConfig:EventListener",
            "ApplicationReadyEvent。prodの口座暗号化key設定を検証するだけ");

    /** {@code @Async} method（taskExecutor上で実行。起点はrequestまたはscheduler）。 */
    private static final Map<String, String> ASYNC_INVENTORY = inventory(
            "com.ses.service.attendance.overtime.OvertimeComplianceServiceImpl#notifyFindingsAsync",
            "残業判定の通知。scheduler停止中もrequest起点の判定からは呼ばれうる",
            "com.ses.service.impl.BpAvailabilityIngestionServiceImpl#parseAsync",
            "BP空き取込の解析（FR-08）。画面からの取込起点",
            "com.ses.service.impl.MailServiceImpl#executeSend",
            "mail送信。画面操作・scheduler双方が起点",
            "com.ses.service.impl.ProjectIngestionServiceImpl#parseAsync",
            "案件取込の解析（FR-01）。画面からの取込起点",
            "com.ses.service.impl.ResumeIngestionServiceImpl#parseAsync",
            "履歴書取込の解析。画面からの取込起点",
            "com.ses.service.notification.WebhookNotifier#notify",
            "Webhook通知転送。通知作成時に呼ばれる");

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    // ---------------------------------------------------------------------
    // 1. 最小contextでの対照
    // ---------------------------------------------------------------------

    private final ApplicationContextRunner schedulingOnly = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class)
            .withBean(ScheduledProbe.class);

    @Test
    void 明示trueでは登録機構がScheduledメソッドを登録し実行する_観測方法の対照() {
        schedulingOnly.withPropertyValues(PROPERTY + "=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SchedulingConfig.class);
            assertThat(context).hasBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
            assertThat(registeredTaskCount(context)).isEqualTo(1);
            assertThat(context.getBean(ScheduledProbe.class).firstInvocation.await(10, TimeUnit.SECONDS))
                    .as("有効時はprobeが実行されること")
                    .isTrue();
        });
    }

    @Test
    void 未設定は有効扱いなのでdrainには明示的なfalseが必要() {
        ConditionalOnProperty condition = SchedulingConfig.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).isNotNull();
        assertThat(condition.name()).containsExactly(PROPERTY);
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();

        schedulingOnly.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SchedulingConfig.class);
            assertThat(registeredTaskCount(context)).isEqualTo(1);
            assertThat(context.getBean(ScheduledProbe.class).firstInvocation.await(10, TimeUnit.SECONDS))
                    .as("propertyが無い環境ではschedulerが動くこと")
                    .isTrue();
        });
    }

    @Test
    void 明示falseではScheduledメソッドが登録も実行もされない() {
        schedulingOnly.withPropertyValues(PROPERTY + "=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertDisabled(context);
        });
    }

    @Test
    void 環境変数APP_SCHEDULING_ENABLEDのfalseでも無効になる() {
        schedulingOnly
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("drainEnvironment",
                                Map.<String, Object>of("APP_SCHEDULING_ENABLED", "false"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty(PROPERTY)).isEqualTo("false");
                    assertDisabled(context);
                });
    }

    private static void assertDisabled(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context) throws Exception {
        assertThat(context).doesNotHaveBean(SchedulingConfig.class);
        assertThat(context).doesNotHaveBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
        assertThat(context).doesNotHaveBean(ScheduledTaskHolder.class);
        assertThat(context).doesNotHaveBean(TaskScheduler.class);
        assertThat(context).hasSingleBean(ScheduledProbe.class);
        ScheduledProbe probe = context.getBean(ScheduledProbe.class);
        assertThat(probe.firstInvocation.await(DISABLED_OBSERVATION_MILLIS, TimeUnit.MILLISECONDS))
                .as("無効時はprobeが一度も実行されないこと")
                .isFalse();
        assertThat(probe.invocations.get()).isZero();
    }

    private static int registeredTaskCount(ApplicationContext context) {
        int count = 0;
        for (ScheduledTaskHolder holder : context.getBeansOfType(ScheduledTaskHolder.class).values()) {
            count += holder.getScheduledTasks().size();
        }
        return count;
    }

    // ---------------------------------------------------------------------
    // 2. 実アプリcontext（test profile: app.scheduling.enabled=false）
    // ---------------------------------------------------------------------

    @Test
    void 実アプリcontextではScheduled登録機構とschedulerが無くScheduled_beanは実在する() throws Exception {
        assertThat(environment.getProperty(PROPERTY))
                .as("本testの前提: test profileでschedulingが明示的にfalseであること")
                .isEqualTo("false");

        assertThat(applicationContext.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                .isFalse();
        assertThat(beanNames(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(beanNames(SchedulingConfig.class)).isEmpty();
        assertThat(beanNames(ScheduledTaskHolder.class)).isEmpty();
        assertThat(beanNames(TaskScheduler.class)).isEmpty();
        assertThat(beanNames(ScheduledExecutorService.class)).isEmpty();
        // Spring Boot actuator の ScheduledTasksObservabilityAutoConfiguration は scheduling の有効・無効に
        // 関係なく ObservabilitySchedulingConfigurer を登録する。これは ScheduledAnnotationBeanPostProcessor
        // から呼ばれて初めて registrar に observation 設定を載せるだけで、BPP・ScheduledTaskHolder・
        // TaskScheduler が無い（上で確認済み）限り何も実行しない。アプリ自身の SchedulingConfigurer は 0 件であること。
        Set<String> appSchedulingConfigurers = new TreeSet<>();
        for (String name : beanNames(SchedulingConfigurer.class)) {
            Class<?> type = applicationContext.getType(name);
            String typeName = type == null ? "" : type.getName();
            if (!typeName.equals("org.springframework.boot.actuate.autoconfigure.scheduling."
                    + "ScheduledTasksObservabilityAutoConfiguration$ObservabilitySchedulingConfigurer")) {
                appSchedulingConfigurers.add(name + "=" + typeName);
            }
        }
        assertThat(appSchedulingConfigurers)
                .as("フレームワークの observability configurer 以外の SchedulingConfigurer が存在しないこと")
                .isEmpty();

        MainClassScan scan = MainClassScan.scan();
        Set<String> presentScheduledMethods = scheduledMethodsOfPresentBeans();
        assertThat(presentScheduledMethods)
                .as("@Scheduledを持つbeanが実アプリcontextに実在すること（無効化の検証が空虚でない）")
                .isNotEmpty();
        assertThat(scan.scheduledMethods)
                .as("実在beanの@Scheduledは本番classの走査結果に含まれること")
                .containsAll(presentScheduledMethods);

        // スイッチ対象外の起動時hookは、scheduling=falseでもbeanとして実在する（下の棚卸しの実地確認）。
        Set<String> runnerClasses = new TreeSet<>();
        for (Class<?> type : new Class<?>[]{ApplicationRunner.class, CommandLineRunner.class}) {
            for (String name : beanNames(type)) {
                Class<?> beanType = applicationContext.getType(name, false);
                if (beanType != null) {
                    String className = ClassUtils.getUserClass(beanType).getName();
                    if (className.startsWith("com.ses.")) {
                        runnerClasses.add(className + ":" + type.getSimpleName());
                    }
                }
            }
        }
        assertThat(STARTUP_HOOK_INVENTORY.keySet()).containsAll(runnerClasses);
        assertThat(runnerClasses)
                .as("scheduling=falseでも起動時にDBへ書き込むrunnerが動くこと（drain対象外の明示）")
                .contains("com.ses.config.ContractDocumentDispatchBackfill:ApplicationRunner");
    }

    private Set<String> beanNames(Class<?> type) {
        return new TreeSet<>(Set.of(applicationContext.getBeanNamesForType(type, true, false)));
    }

    private Set<String> scheduledMethodsOfPresentBeans() {
        Set<String> methods = new TreeSet<>();
        for (String name : applicationContext.getBeanDefinitionNames()) {
            Class<?> type;
            try {
                type = applicationContext.getType(name, false);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (type == null) {
                continue;
            }
            Class<?> userClass = ClassUtils.getUserClass(type);
            if (!userClass.getName().startsWith("com.ses.")) {
                continue;
            }
            for (Method method : ReflectionUtils.getUniqueDeclaredMethods(userClass)) {
                if (AnnotatedElementUtils.hasAnnotation(method, Scheduled.class)
                        || AnnotatedElementUtils.hasAnnotation(method, Schedules.class)) {
                    methods.add(method.getDeclaringClass().getName() + "#" + method.getName());
                }
            }
        }
        return methods;
    }

    // ---------------------------------------------------------------------
    // 3. 本番classの棚卸し（bytecode走査）
    // ---------------------------------------------------------------------

    @Test
    void EnableSchedulingはSchedulingConfigだけでスイッチ対象外の非同期実行は既知一覧と一致する() throws Exception {
        MainClassScan scan = MainClassScan.scan();

        assertThat(scan.scannedClasses).as("本番classを走査できること").isGreaterThan(100);
        assertThat(scan.scheduledMethods).as("本番の@Scheduled methodを検出できること").isNotEmpty();
        assertThat(scan.enableSchedulingClasses)
                .as("@EnableSchedulingはスイッチ付きのSchedulingConfigだけであること")
                .containsExactly(SchedulingConfig.class.getName());
        assertThat(scan.schedulingConfigurers)
                .as("SchedulingConfigurerで登録経路を足すclassが無いこと")
                .isEmpty();

        assertInventory("自前のthread/executor生成・起動", THREAD_API_INVENTORY, scan.threadApiUsages);
        assertInventory("起動時hook", STARTUP_HOOK_INVENTORY, scan.startupHooks);
        assertInventory("@Async method", ASYNC_INVENTORY, scan.asyncMethods);
    }

    private static void assertInventory(String kind, Map<String, String> expected, Set<String> actual) {
        Set<String> added = new TreeSet<>(actual);
        added.removeAll(expected.keySet());
        Set<String> removed = new TreeSet<>(expected.keySet());
        removed.removeAll(actual);
        assertThat(added)
                .as("%sに一覧外のものがあります。app.scheduling.enabledで止まらないため、drain手順と本一覧を見直してください: %s",
                        kind, added)
                .isEmpty();
        assertThat(removed)
                .as("%sの一覧に存在しないものがあります。一覧から削除してください: %s", kind, removed)
                .isEmpty();
    }

    private static Map<String, String> inventory(String... keyAndReason) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyAndReason.length; i += 2) {
            map.put(keyAndReason[i], keyAndReason[i + 1]);
        }
        return Map.copyOf(map);
    }

    /** {@code com.ses} の本番classだけ（test classは含めない）を走査した結果。 */
    private static final class MainClassScan {

        private static final Class<?>[] HOOK_TYPES = {
                ApplicationRunner.class, CommandLineRunner.class, ApplicationListener.class, Lifecycle.class};

        private int scannedClasses;
        private final Set<String> scheduledMethods = new TreeSet<>();
        private final Set<String> enableSchedulingClasses = new TreeSet<>();
        private final Set<String> schedulingConfigurers = new TreeSet<>();
        private final Set<String> threadApiUsages = new TreeSet<>();
        private final Set<String> startupHooks = new TreeSet<>();
        private final Set<String> asyncMethods = new TreeSet<>();

        static MainClassScan scan() throws Exception {
            URL location = SchedulingConfig.class.getProtectionDomain().getCodeSource().getLocation();
            String root = location.toString();
            String pattern = root.endsWith(".jar")
                    ? "jar:" + root + "!/com/ses/**/*.class"
                    : (root.endsWith("/") ? root : root + "/") + "com/ses/**/*.class";
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            MetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);
            ClassLoader loader = SchedulingConfig.class.getClassLoader();

            MainClassScan scan = new MainClassScan();
            for (Resource resource : resolver.getResources(pattern)) {
                if (!resource.isReadable()) {
                    continue;
                }
                scan.scannedClasses++;
                MetadataReader reader = factory.getMetadataReader(resource);
                AnnotationMetadata metadata = reader.getAnnotationMetadata();
                String className = metadata.getClassName();
                String topLevel = topLevel(className);

                for (String annotation : new String[]{Scheduled.class.getName(), Schedules.class.getName()}) {
                    for (MethodMetadata method : metadata.getAnnotatedMethods(annotation)) {
                        scan.scheduledMethods.add(className + "#" + method.getMethodName());
                    }
                }
                if (metadata.hasAnnotation(EnableScheduling.class.getName())
                        || metadata.hasMetaAnnotation(EnableScheduling.class.getName())) {
                    scan.enableSchedulingClasses.add(className);
                }
                for (MethodMetadata method : metadata.getAnnotatedMethods(Async.class.getName())) {
                    scan.asyncMethods.add(topLevel + "#" + method.getMethodName());
                }
                if (metadata.hasAnnotation(Async.class.getName())) {
                    scan.asyncMethods.add(topLevel + "#*");
                }
                for (MethodMetadata method : metadata.getAnnotatedMethods(EventListener.class.getName())) {
                    scan.startupHooks.add(topLevel + "#" + method.getMethodName() + ":EventListener");
                }

                Class<?> type = load(className, loader);
                if (type != null && !type.isInterface()) {
                    for (Class<?> hook : HOOK_TYPES) {
                        if (hook.isAssignableFrom(type)) {
                            scan.startupHooks.add(topLevel + ":" + hook.getSimpleName());
                        }
                    }
                    if (SchedulingConfigurer.class.isAssignableFrom(type)) {
                        scan.schedulingConfigurers.add(className);
                    }
                }

                try (InputStream in = resource.getInputStream()) {
                    new ClassReader(in).accept(new ThreadApiVisitor(topLevel, scan.threadApiUsages),
                            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
            return scan;
        }

        private static String topLevel(String className) {
            int nested = className.indexOf('$');
            return nested < 0 ? className : className.substring(0, nested);
        }

        private static Class<?> load(String className, ClassLoader loader) {
            try {
                return Class.forName(className, false, loader);
            } catch (Throwable ignored) {
                return null; // 任意依存が無いclassは型判定できない（annotation/bytecodeの走査は継続する）
            }
        }
    }

    /** thread/executor/schedulerを生成・起動するAPI呼出し（method参照を含む）を記録する。 */
    private static final class ThreadApiVisitor extends ClassVisitor {

        private final String owner;
        private final Set<String> sink;

        ThreadApiVisitor(String owner, Set<String> sink) {
            super(SpringAsmInfo.ASM_VERSION);
            this.owner = owner;
            this.sink = sink;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                         String[] exceptions) {
            return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                @Override
                public void visitMethodInsn(int opcode, String calleeOwner, String calleeName,
                                            String calleeDescriptor, boolean isInterface) {
                    record(calleeOwner, calleeName);
                }

                @Override
                public void visitInvokeDynamicInsn(String invokedName, String invokedDescriptor,
                                                   Handle bootstrap, Object... bootstrapArguments) {
                    for (Object argument : bootstrapArguments) {
                        if (argument instanceof Handle handle) {
                            record(handle.getOwner(), handle.getName());
                        }
                    }
                }
            };
        }

        private void record(String calleeOwner, String calleeName) {
            String api = threadApi(calleeOwner, calleeName);
            if (api != null) {
                sink.add(owner + ":" + api);
            }
        }

        private static String threadApi(String owner, String name) {
            String simple = owner.substring(owner.lastIndexOf('/') + 1);
            boolean constructor = "<init>".equals(name);
            switch (owner) {
                case "java/util/concurrent/Executors":
                case "java/util/concurrent/ScheduledExecutorService":
                case "org/springframework/scheduling/TaskScheduler":
                case "org/springframework/scheduling/config/ScheduledTaskRegistrar":
                case "org/springframework/scheduling/annotation/ScheduledAnnotationBeanPostProcessor":
                    return simple + "." + name;
                case "java/lang/Thread":
                    return Set.of("<init>", "start", "ofVirtual", "ofPlatform", "startVirtualThread").contains(name)
                            ? simple + "." + name : null;
                case "java/util/Timer":
                case "java/util/concurrent/ThreadPoolExecutor":
                case "java/util/concurrent/ScheduledThreadPoolExecutor":
                case "org/springframework/core/task/SimpleAsyncTaskExecutor":
                    return constructor ? simple + "." + name : null;
                case "java/util/concurrent/ForkJoinPool":
                    return constructor || "commonPool".equals(name) ? simple + "." + name : null;
                case "java/util/concurrent/CompletableFuture":
                    return name.endsWith("Async") ? simple + "." + name : null;
                case "org/apache/hc/client5/http/impl/async/CloseableHttpAsyncClient":
                    return "start".equals(name) ? simple + "." + name : null;
                default:
                    if (owner.startsWith("org/springframework/scheduling/concurrent/") && constructor) {
                        return simple + "." + name;
                    }
                    return null;
            }
        }
    }

    /**
     * 最小contextで登録・実行を観測するprobe。{@code ScheduledMethodsHaveSchedulerLockTest} はtest classも
     * 走査するため、本番の規約どおり {@code @SchedulerLock} を付ける（最小contextにShedLockは無く不活性）。
     */
    static class ScheduledProbe {

        private final AtomicInteger invocations = new AtomicInteger();
        private final CountDownLatch firstInvocation = new CountDownLatch(1);

        @Scheduled(fixedDelay = 20)
        @SchedulerLock(name = "scheduling-disabled-drain-probe")
        public void tick() {
            invocations.incrementAndGet();
            firstInvocation.countDown();
        }
    }
}
