package com.ses.service.impl;

import com.ses.config.AsyncConfig;
import com.ses.dto.mail.MailDispatchResult;
import com.ses.entity.MailDelivery;
import com.ses.mapper.MailDeliveryMapper;
import com.ses.service.EmailTemplateService;
import com.ses.service.MailService;
import com.ses.service.SystemConfigService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.portal.PortalMailService;
import com.ses.service.portal.impl.PortalMailServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RB-PRERUN 2: portal招待メールの配信がQUEUEDのまま止まる不具合（{@code BeanNotOfRequiredTypeException}）の回帰。
 *
 * <p>アプリと同じ {@link AsyncConfig}（{@code @EnableAsync} 既定のinterface proxy）と、Spring Bootの
 * {@link AopAutoConfiguration}（{@code spring.aop.proxy-target-class=true}）で最小contextを起動し、実際のSpring proxyを通して
 * 招待メールを送る。{@code MailServiceImpl} は {@code @Transactional} 等を持たず、{@code @Async} だけで
 * proxyされるためJDK proxyになる。旧実装の {@code @Lazy MailServiceImpl self} はこのproxyへ解決できず、初回送信で例外になり
 * 配信履歴がQUEUEDのまま残った。送信は {@link JavaMailSender} の替身で受け、実際のメールは送らない。DBは使わない
 * （配信履歴のmapperも替身で、insert・updateByIdの時点の状態とthreadを記録する）。</p>
 */
class MailServiceAsyncSelfInvocationTest {

    private static final String INVITEE = "invitee@example.com";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class))
            .withUserConfiguration(AsyncConfig.class, MailFixture.class)
            // host が空だとドライランになるため、送信経路（JavaMailSender）を通す値を入れる。接続はしない（替身）。
            .withPropertyValues("spring.mail.host=smtp.invalid.test", "app.mail.from=noreply@example.com");

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 招待メールの配信はproxy経由の非同期送信でQUEUEDからSENTへ進む() {
        runner.run(context -> {
            MailService mailService = context.getBean(MailService.class);
            MailFixture fixture = context.getBean(MailFixture.class);
            assertThat(AopUtils.isAopProxy(mailService)).as("@Asyncのproxyが掛かっていること").isTrue();

            AccountingTenantContextHolder.setTenantId("default");
            String callerThread = Thread.currentThread().getName();
            context.getBean(PortalMailService.class).sendInvitation(INVITEE, "token-for-test");

            assertThat(fixture.updated.await(10, TimeUnit.SECONDS))
                    .as("配信履歴がQUEUEDから更新されること").isTrue();
            assertThat(fixture.insertedStatuses).containsExactly("QUEUED");
            assertThat(fixture.updatedStatuses).containsExactly("SENT");
            assertThat(fixture.updateThreads).hasSize(1);
            assertThat(fixture.updateThreads.get(0))
                    .as("送信はtaskExecutor（async-notify-）で実行され、呼出元threadで同期実行しないこと")
                    .startsWith("async-notify-")
                    .isNotEqualTo(callerThread);
            assertThat(fixture.sentMessages).hasSize(1);
            assertThat(fixture.sentMessages.get(0).getTo()).containsExactly(INVITEE);
            assertThat(fixture.sentMessages.get(0).getText()).contains("/portal/accept-invitation?token=");
        });
    }

    /**
     * RB-PRERUN 追加 P2-1: send() が返すのは受付結果で、status は非同期送信の進み具合に関係なく常に QUEUED。
     * 非同期threadには複製を渡し、呼出元のオブジェクトと状態を共有しない（共有すると返却値が QUEUED / DRY_RUN の
     * どちらになるか定まらず、一括督促が送信受付を失敗と数えた）。
     */
    @Test
    void sendの返却値は送信の完了後も受付結果QUEUEDのままで非同期threadには複製を渡す() {
        runner.run(context -> {
            MailService mailService = context.getBean(MailService.class);
            MailFixture fixture = context.getBean(MailFixture.class);
            AccountingTenantContextHolder.setTenantId("default");

            MailDispatchResult result = mailService.send(INVITEE, "督促", "本文", 77L);

            assertThat(fixture.updated.await(10, TimeUnit.SECONDS)).as("非同期送信が終わること").isTrue();
            assertThat(fixture.updatedStatuses).containsExactly("SENT");
            assertThat(result.getStatus()).as("送信の完了後も返却値は受付結果のまま").isEqualTo("QUEUED");
            assertThat(result.getDeliveryId()).isEqualTo(fixture.insertedDeliveries.get(0).getId());
            assertThat(fixture.updatedDeliveries.get(0))
                    .as("非同期threadは保存した配信履歴の複製を更新する（呼出元のオブジェクトを書き換えない）")
                    .isNotSameAs(fixture.insertedDeliveries.get(0));
            assertThat(fixture.updatedDeliveries.get(0).getId()).isEqualTo(result.getDeliveryId());
            assertThat(fixture.updatedDeliveries.get(0).getInvoiceId()).isEqualTo(77L);
            assertThat(fixture.insertedDeliveries.get(0).getStatus()).isEqualTo("QUEUED");
        });
    }

    @Test
    void 実装class型ではproxyへ解決できないため自己注入はinterface型で行う() {
        runner.run(context -> {
            String beanName = context.getBeanNamesForType(MailService.class)[0];
            MailService mailService = context.getBean(MailService.class);
            // 現行のAsyncConfigではMailServiceImplはJDK proxy（interfaceだけを実装する）になる。
            // proxy方式を変える場合も、自己呼出しはinterface型のproxyを通すこと（実装class型の注入に戻さない）。
            assertThat(AopUtils.isJdkDynamicProxy(mailService))
                    .as("@Asyncだけを持つMailServiceImplは@EnableAsync既定のJDK proxyになる").isTrue();
            assertThat(mailService).isNotInstanceOf(MailServiceImpl.class);
            assertThatThrownBy(() -> context.getBean(beanName, MailServiceImpl.class))
                    .as("旧実装の実装class型の自己注入が初回送信で失敗した理由")
                    .isInstanceOf(BeanNotOfRequiredTypeException.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({MailServiceImpl.class, PortalMailServiceImpl.class})
    static class MailFixture {

        final List<String> insertedStatuses = new CopyOnWriteArrayList<>();
        final List<String> updatedStatuses = new CopyOnWriteArrayList<>();
        final List<MailDelivery> insertedDeliveries = new CopyOnWriteArrayList<>();
        final List<MailDelivery> updatedDeliveries = new CopyOnWriteArrayList<>();
        final List<String> updateThreads = new CopyOnWriteArrayList<>();
        final List<SimpleMailMessage> sentMessages = new CopyOnWriteArrayList<>();
        final CountDownLatch updated = new CountDownLatch(1);
        private final AtomicLong ids = new AtomicLong(9000);

        @Bean
        EmailTemplateService emailTemplateService() {
            return mock(EmailTemplateService.class);
        }

        @Bean
        SystemConfigService systemConfigService() {
            SystemConfigService config = mock(SystemConfigService.class);
            when(config.getString(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
            return config;
        }

        @Bean
        MailDeliveryMapper mailDeliveryMapper() {
            MailDeliveryMapper mapper = mock(MailDeliveryMapper.class);
            when(mapper.insert(any(MailDelivery.class))).thenAnswer(invocation -> {
                MailDelivery delivery = invocation.getArgument(0);
                delivery.setId(ids.incrementAndGet());
                insertedStatuses.add(delivery.getStatus());
                insertedDeliveries.add(delivery);
                return 1;
            });
            when(mapper.updateById(any(MailDelivery.class))).thenAnswer(invocation -> {
                MailDelivery delivery = invocation.getArgument(0);
                updatedStatuses.add(delivery.getStatus());
                updatedDeliveries.add(delivery);
                updateThreads.add(Thread.currentThread().getName());
                updated.countDown();
                return 1;
            });
            return mapper;
        }

        @Bean
        JavaMailSender javaMailSender() {
            JavaMailSender sender = mock(JavaMailSender.class);
            doAnswer(invocation -> {
                sentMessages.add(invocation.getArgument(0));
                return null;
            }).when(sender).send(any(SimpleMailMessage.class));
            return sender;
        }
    }
}
