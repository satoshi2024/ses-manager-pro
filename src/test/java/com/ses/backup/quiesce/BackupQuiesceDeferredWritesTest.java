package com.ses.backup.quiesce;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RB-QUIESCE BWQ-002: 解除後の書込みの一覧（{@link BackupQuiesceDeferredWrites}。design.md §6.13）の上限・捨てる時の WARN・
 * 投入の順・gate がまた閉じた時の戻し・3回の繰返しを確かめる。
 */
class BackupQuiesceDeferredWritesTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLog() {
        logger = (Logger) LoggerFactory.getLogger(BackupQuiesceDeferredWrites.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachLog() {
        logger.detachAppender(appender);
    }

    @Test
    void 入れる時にWARNを出し上限を超えたら最も古いものをWARNで捨てる() {
        BackupQuiesceDeferredWrites writes = new BackupQuiesceDeferredWrites(2);
        List<String> written = new ArrayList<>();
        writes.add(write("user=1 session=aaaaaaaa", written));
        writes.add(write("user=2 session=bbbbbbbb", written));
        writes.add(write("user=3 session=cccccccc", written));

        assertThat(writes.pending()).isEqualTo(2);
        assertThat(writes.droppedCount()).isEqualTo(1);
        assertThat(warnings()).filteredOn(message -> message.startsWith("errorCode=BACKUP_QUIESCE_DEFERRED_WRITE "))
                .hasSize(3);
        assertThat(warnings()).filteredOn(message -> message.startsWith("errorCode=BACKUP_QUIESCE_DEFERRED_WRITE_DROPPED"))
                .singleElement().asString().contains("user=1 session=aaaaaaaa").contains("2件");

        writes.flush(openGate());
        assertThat(written).as("入れた順に書き、捨てたものは書かない")
                .containsExactly("user=2 session=bbbbbbbb", "user=3 session=cccccccc");
        assertThat(writes.pending()).isZero();
    }

    @Test
    void 既定の上限は1万件で上限は1以上() {
        BackupWriteGate gate = new BackupWriteGate(BackupWriteGate.Mode.ARMED, true, Duration.ofSeconds(20),
                new BackupQuiesceProperties().getDeferredWritesMax());
        assertThat(gate.deferredWrites().capacity()).isEqualTo(10_000);
        assertThatThrownBy(() -> new BackupQuiesceDeferredWrites(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 書込みの間にgateがまた閉じたら捨てずに一覧の先頭へ戻し次の解除で書く() {
        BackupWriteGate gate = openGate();
        BackupQuiesceDeferredWrites writes = gate.deferredWrites();
        List<String> written = new ArrayList<>();
        writes.add(new BackupQuiesceDeferredWrites.DeferredWrite() {
            @Override
            public String summary() {
                return "first";
            }

            @Override
            public void write() {
                written.add("first");
                assertThat(gate.currentThreadHoldsPermit()).as("解除後の書込みの permit を持って書く").isTrue();
                gate.beginClosing(2);
            }
        });
        writes.add(write("second", written));
        writes.add(write("third", written));

        assertThat(writes.flush(gate)).isEqualTo(1);
        assertThat(written).containsExactly("first");
        assertThat(writes.pending()).as("残りは捨てない").isEqualTo(2);

        gate.reopen(2);
        assertThat(writes.flush(gate)).isEqualTo(2);
        assertThat(written).containsExactly("first", "second", "third");
        assertThat(gate.permitCount()).isZero();
    }

    @Test
    void DBの失敗は3回まで繰り返しそれでも失敗したら内容つきのWARNを残して捨てる() {
        BackupWriteGate gate = openGate();
        BackupQuiesceDeferredWrites writes = gate.deferredWrites();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger flaky = new AtomicInteger();
        List<String> written = new ArrayList<>();
        writes.add(new BackupQuiesceDeferredWrites.DeferredWrite() {
            @Override
            public String summary() {
                return "user=9 session=deadbeef";
            }

            @Override
            public void write() {
                attempts.incrementAndGet();
                throw new IllegalStateException("db down");
            }
        });
        writes.add(new BackupQuiesceDeferredWrites.DeferredWrite() {
            @Override
            public String summary() {
                return "flaky";
            }

            @Override
            public void write() {
                if (flaky.incrementAndGet() < 3) {
                    throw new IllegalStateException("transient");
                }
                written.add("flaky");
            }
        });

        assertThat(writes.flush(gate)).isEqualTo(1);
        assertThat(attempts.get()).isEqualTo(BackupQuiesceDeferredWrites.MAX_ATTEMPTS);
        assertThat(flaky.get()).as("3回目で成功").isEqualTo(3);
        assertThat(written).containsExactly("flaky");
        assertThat(writes.pending()).isZero();
        assertThat(warnings()).filteredOn(message -> message.startsWith("errorCode=BACKUP_QUIESCE_DEFERRED_WRITE_DROPPED"))
                .singleElement().asString().contains("user=9 session=deadbeef").contains("3回");
        assertThat(warnings()).as("失敗のたびに WARN").filteredOn(message -> message.startsWith("解除後の書込みに失敗しました"))
                .hasSize(5);
    }

    private List<String> warnings() {
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static BackupWriteGate openGate() {
        BackupWriteGate gate = new BackupWriteGate(BackupWriteGate.Mode.ARMED, true, Duration.ofDays(1), 100);
        gate.armOpen();
        return gate;
    }

    private static BackupQuiesceDeferredWrites.DeferredWrite write(String name, List<String> sink) {
        return new BackupQuiesceDeferredWrites.DeferredWrite() {
            @Override
            public String summary() {
                return name;
            }

            @Override
            public void write() {
                sink.add(name);
            }
        };
    }
}
