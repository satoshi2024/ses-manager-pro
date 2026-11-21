package com.ses.config;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
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
            "com.ses.service.integrationhub.HttpsIntegrationHubWebhookTransport:CloseableHttpAsyncClient.start",
            "NF05のHTTPS有効時だけ生成するI/O reactor。周期実行はなくclose()で即時停止する",
            "com.ses.service.integrationhub.IntegrationHubHttpsEndpointGuard:ThreadPoolExecutor.<init>",
            "NF05のDNS解決を二thread・待機八件へ制限する。周期実行はなくclose()で中断し最大二秒待つ",
            "com.ses.service.integrationhub.IntegrationHubHttpsEndpointGuard:Thread.<init>",
            "DNS専用daemon thread。外部I/Oは要求起点だけで、schedulerの停止と独立にclose()で停止要求する",
            "com.ses.service.integrationhub.IntegrationHubReplayTlsPreflight:ScheduledThreadPoolExecutor.<init>",
            "replay総期限timerはadmission四件まで。取消し時に除去しcloseで停止する",
            "com.ses.service.integrationhub.IntegrationHubReplayTlsPreflight:Thread.<init>",
            "replay期限を守るdaemon一thread。closeで停止し最大二秒待つ",
            "com.ses.service.skillgap.AiLearningCandidateServiceImpl:CompletableFuture.supplyAsync",
            "request内のAI呼出をcommonPoolでtimeout付き実行する。timeout後もtaskは継続しうるがshutdown待ちは無い",
            "com.ses.privacy.production.retention.trust.KmsPrivacyTrustSource:Thread.<init>",
            "NF07 trust文書の起動時検査（prod・flag ON・KMS設定時に起動ごと一度）をinspectTimeout（20秒）で打ち切るdaemon一thread。"
                    + "fileのstat・読込みだけでDB書込み・周期実行は無い",
            "com.ses.privacy.production.retention.trust.KmsPrivacyTrustSource:Thread.start",
            "上の検査threadの起動。I/Oが止まり打ち切るとthreadは残りうるが、起動検査の失敗として起動を中止する"
                    + "（daemonのためJVMの終了は妨げない）",
            // RB-QUIESCE BWQ-003: backup の書込み静止の2つの専用 thread（heartbeat・状態機械）。@Scheduled を使わないため
            // APP_SCHEDULING_ENABLED=false でも動く（静止の要求に応答するため。design.md §5.2）。ScheduledExecutorService は bean にしない
            "com.ses.backup.quiesce.BackupQuiesceTicker:Executors.newSingleThreadScheduledExecutor",
            "静止応答の heartbeat（5秒ごと、heartbeat 用の接続で信号表の1行を UPDATE）と状態機械（200ms の tick、1 tick に短い文を"
                    + "高々1つ）の専用 daemon thread。app.backup-quiesce.enabled=false の prod 以外では作らない",
            "com.ses.backup.quiesce.BackupQuiesceTicker:Thread.<init>",
            "上の2つの thread（backup-quiesce-heartbeat・backup-quiesce-responder）。daemon で JVM の終了を妨げない",
            "com.ses.backup.quiesce.BackupQuiesceTicker:ScheduledExecutorService.scheduleWithFixedDelay",
            "上の周期実行。処理の例外は捕まえて次の周期へ回す（周期が黙って止まらない）",
            "com.ses.backup.quiesce.BackupQuiesceTicker:ScheduledExecutorService.isShutdown",
            "上の thread が動いているかの確認（診断用）",
            "com.ses.backup.quiesce.BackupQuiesceTicker:ScheduledExecutorService.shutdownNow",
            "停止。状態機械は静止応答の破棄で、heartbeat は STOPPED を書く前と anchor の破棄（業務の DataSource の後）で止める",
            "com.ses.backup.quiesce.BackupQuiesceTicker:ScheduledExecutorService.awaitTermination",
            "停止の待ち（最長12秒。実行中の文は socket の timeout 10秒までに終わる）");

    /** 起動時に一度だけ動くhook（{@code app.scheduling.enabled} の外。各自の条件で起動する）。 */
    private static final Map<String, String> STARTUP_HOOK_INVENTORY = inventory(
            "com.ses.service.integrationhub.IntegrationHubOutboundStartupBarrier:ApplicationRunner",
            "起動直後に隔離を一batch進める。例外はNF05内で捕捉しアプリ起動を妨げない",
            "com.ses.service.integrationhub.IntegrationHubOutboundStartupBarrier:Lifecycle",
            "web serverより前のphaseでOFF/ONを永続化し高水位を確定する。定期復旧は全体scheduler停止に従う",
            "com.ses.config.ContractDocumentBackfillRunner:ApplicationRunner",
            "app.upload.contract-backfill-enabled（未設定=有効）。契約PDFのmetadataを起動時にbackfillする（DB書込あり）",
            "com.ses.config.ContractDocumentDispatchBackfill:ApplicationRunner",
            "app.contract-document.backfill-enabled（未設定=有効）。契約書配送工程を起動時に分類する（DB書込あり）",
            "com.ses.config.LegacyUploadMigrationRunner:ApplicationRunner",
            "app.upload.legacy-migration-enabled（未設定=有効）。旧uploadを起動時に移行する（file移動・DB書込あり）",
            "com.ses.config.ProductionSecurityConfigurationValidator:ApplicationRunner",
            "prod profileだけ。設定検証と、完全な検査の初回通過時だけbootstrap完了markerを書く（DB書込あり・冪等）",
            "com.ses.config.ProductionBootstrapMode:ApplicationRunner",
            "prodで完了済みのbootstrapを承認ticketつきで再開する時だけ、必須監査BOOTSTRAP_REOPENEDを書いてから再開を有効にする"
                    + "（DB書込あり。bean生成時から移した。RB-QUIESCE 2a review P1-1）。それ以外は何もしない",
            "com.ses.service.migration.DocumentMigrationScript:CommandLineRunner",
            "起動引数 --migrate-documents のときだけ文書台帳移行を行う",
            "com.ses.config.LegalEntityReadinessStartupValidator#validate:EventListener",
            "ApplicationReadyEvent。公開API（inbound）/外部transport（outbound）/Copilotのどれかが有効な時だけ法人readinessを読取検証する（DB書込なし）",
            "com.ses.service.impl.BpCompanyServiceImpl#validateBankKeyConfig:EventListener",
            "ApplicationReadyEvent。prodの口座暗号化key設定を検証するだけ",
            // RB-QUIESCE BWQ-003: 静止応答の起動と停止の hook（design.md §6.8・§6.11）。登録・有効な要求の読取り・startup permit の
            // 取得は SmartInitializingSingleton（port を開く前）、破棄は DisposableBean（状態機械の thread を止める）で行う
            "com.ses.backup.quiesce.BackupQuiesceResponder#onApplicationReady:EventListener",
            "ApplicationReadyEvent。instance を RUNNING にし（次の heartbeat で書く）、起動の startup permit を返す",
            "com.ses.backup.quiesce.BackupQuiesceResponder#onContextClosed:EventListener",
            "ContextClosedEvent（lifecycle の停止より前）。gate を SHUTDOWN にし、呼んだ thread の permit を返す（DB 書込なし）",
            // RB-QUIESCE BWQ-006: 起動の最後（SmartInitializingSingleton）と破棄（DisposableBean・DestructionAwareBeanPostProcessor）の
            // hook も、app.scheduling.enabled の外で動くため棚卸しする（走査は com.ses の class が callback を実装したものだけを数え、
            // GenericFilterBean 等の framework の基底 class から継承しただけの何もしない callback は数えない）
            "com.ses.backup.quiesce.BackupQuiesceResponder:SmartInitializingSingleton",
            "静止応答の起動（port を開く前）。instance を heartbeat 用の接続で登録し（信号表への書込み）、有効な要求を読み、"
                    + "APP_STOPPED の保持なら起動を中止、無ければ startup permit を取って開き、ONLINE なら解除まで待つ（design.md §6.8）",
            "com.ses.backup.quiesce.BackupQuiesceResponder:DisposableBean",
            "状態機械の thread を止める（最長12秒）。業務の DB へは書かない",
            "com.ses.backup.quiesce.BackupQuiesceLifecycleAnchor:DisposableBean",
            "業務の DataSource の後に破棄される。heartbeat の thread を止め、制御用・heartbeat 用の pool を閉じる（書込みなし）",
            "com.ses.backup.quiesce.BackupQuiesceDataSourcePostProcessor:DestructionAwareBeanPostProcessor",
            "業務の dataSource の破棄の時に SHUTDOWN にし、permit と書込み可能な接続が0になるのを最長10秒待ち、Hikari を閉じてから"
                    + " instance を STOPPED にする（信号表への書込み。design.md §6.11）",
            // RB-QUIESCE BWQ-007: prod の起動時検査（静止応答が登録の前にも呼ぶ。1回だけ検査する）
            "com.ses.backup.quiesce.BackupQuiesceProductionValidator:SmartInitializingSingleton",
            "停止口の ticket の形式と、業務の DataSource の readOnlyPropagatesToServer を検査する（設定を読むだけ。DB 書込なし。"
                    + "違反は起動を中止。design.md §12.2）",
            "com.ses.config.ProductionSchemaVersionGuard:SmartInitializingSingleton",
            "prod の起動時に flyway_schema_history を読んで schema の版を確かめる（読むだけ。合わなければ起動を中止）",
            "com.ses.privacy.production.PrivacyDecisionWindowMonitor:SmartInitializingSingleton",
            "prod profile だけ。NF07 の決定の期限の metric を登録し、状態の変化を log に出す（DB 書込なし）",
            "com.ses.privacy.production.PrivacyProductionConfigurationValidator:SmartInitializingSingleton",
            "prod profile だけ。NF07 の ON の時の設定を検査する（DB 書込なし）",
            "com.ses.privacy.production.retention.hold.PrivacyJournalStartupVerifier:SmartInitializingSingleton",
            "NF07 の Journal（application DB と別の MySQL instance）の権限と migration を読んで検査する（書込みなし）",
            "com.ses.privacy.production.retention.trust.PrivacyTrustStartupValidator:SmartInitializingSingleton",
            "prod profile だけ。NF07 の trust 文書の設定を検査する（DB 書込なし）");

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

        /**
         * 起動の最後と破棄の callback（型 → callback の method 名）。com.ses の class が callback を実装した時だけ数える
         * （{@code OncePerRequestFilter} が {@code GenericFilterBean} から継承する何もしない {@code destroy} は数えない）。
         */
        private static final Map<Class<?>, String> CALLBACK_TYPES = Map.of(
                SmartInitializingSingleton.class, "afterSingletonsInstantiated",
                DisposableBean.class, "destroy",
                DestructionAwareBeanPostProcessor.class, "postProcessBeforeDestruction");

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
                    for (Map.Entry<Class<?>, String> callback : CALLBACK_TYPES.entrySet()) {
                        if (callback.getKey().isAssignableFrom(type) && implementsInApp(type, callback.getValue())) {
                            scan.startupHooks.add(topLevel + ":" + callback.getKey().getSimpleName());
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

        /** {@code methodName} の callback を com.ses の class（自分か親）が実装しているか。 */
        private static boolean implementsInApp(Class<?> type, String methodName) {
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
                if (!current.getName().startsWith("com.ses.")) {
                    return false;
                }
                for (Method method : current.getDeclaredMethods()) {
                    if (method.getName().equals(methodName) && !method.isBridge() && !method.isSynthetic()) {
                        return true;
                    }
                }
            }
            return false;
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
