package com.ses.service.impl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-PRERUN 追加 P3-2: {@code MailService.executeSend} は、{@code @EnableAsync} 既定のinterface proxyでも
 * {@code send()} から非同期に呼べるようinterfaceへ置いた。そのため {@code MailService} を持つbeanなら呼べてしまうが、
 * 直接呼ぶと {@code send()} の配信履歴（QUEUED）の保存とtenantの取得を通らず、任意の配信履歴（他の配信行のidを含む）と
 * tenantで送信・更新できる。main で呼ぶのは {@code MailServiceImpl}（{@code send()} の中）だけに限ることを、
 * sourceを読んで固定する（コメントは除く。メソッド参照 {@code ::executeSend} も呼出しとみなす）。
 */
class MailServiceExecuteSendCallSiteTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final String IMPL = "com/ses/service/impl/MailServiceImpl.java";
    private static final String INTERFACE = "com/ses/service/MailService.java";
    private static final Pattern CALL = Pattern.compile("::\\s*executeSend\\b|\\bexecuteSend\\s*\\(");

    @Test
    void executeSendを呼ぶmainのコードはMailServiceImplだけ() throws IOException {
        List<String> offenders = new ArrayList<>();
        int sources = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                sources++;
                String name = MAIN.relativize(file).toString().replace('\\', '/');
                if (name.equals(IMPL) || name.equals(INTERFACE)) {
                    continue;
                }
                if (CALL.matcher(code(file)).find()) {
                    offenders.add(name);
                }
            }
        }

        assertThat(sources).as("mainのsourceを読めていること").isGreaterThan(100);
        assertThat(offenders)
                .as("executeSend は send() を通して呼ぶ（直接呼ぶと配信履歴の保存とtenantの取得を飛ばす）")
                .isEmpty();
        // 検査が空振りしていないこと: MailServiceImpl の send() は proxy 経由と同期 fallback の2箇所で呼ぶ
        assertThat(CALL.matcher(code(MAIN.resolve(IMPL))).results().count())
                .as("MailServiceImpl の呼出し（send() の2箇所と executeSend(MailDelivery) の委譲）を数えられること")
                .isGreaterThanOrEqualTo(3);
    }

    /** コメントを除いたsource。 */
    private static String code(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "");
    }
}
