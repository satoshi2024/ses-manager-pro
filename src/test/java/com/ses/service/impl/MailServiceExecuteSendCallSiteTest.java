package com.ses.service.impl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-PRERUN 追加 P3-2: {@code MailService.executeSend} は、{@code @EnableAsync} 既定のinterface proxyでも
 * {@code send()} から非同期に呼べるようinterfaceへ置いた。そのため {@code MailService} を持つbeanなら呼べてしまうが、
 * 直接呼ぶと {@code send()} の配信履歴（QUEUED）の保存とtenantの取得を通らず、任意の配信履歴（他の配信行のidを含む）と
 * tenantで送信・更新できる。main で呼ぶのは {@code MailServiceImpl}（{@code send()} の中）だけに限ることを、
 * sourceを読んで固定する（コメントは除く。メソッド参照 {@code ::executeSend} も呼出しとみなす）。
 *
 * <p>RB-POSTLAUNCH P3-B: 以前は空振り防止の「3件以上」にメソッドの宣言（{@code void executeSend(}）も数えていた。
 * 宣言は呼出しではないため数えず、{@code MailServiceImpl} の呼出しを正確に3件（{@code send()} の proxy 経由・同期 fallback と
 * 旧呼出し口の委譲）、宣言を2件と固定する。他のclassでも宣言（別の型が同名メソッドを実装する等）は呼出しとして扱わない。</p>
 */
class MailServiceExecuteSendCallSiteTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final String IMPL = "com/ses/service/impl/MailServiceImpl.java";
    private static final String INTERFACE = "com/ses/service/MailService.java";
    private static final Pattern CALL = Pattern.compile("::\\s*executeSend\\b|\\bexecuteSend\\s*\\(");
    /** 直後に式（呼出し）が来る語。直前の語がこれらなら宣言ではない。 */
    private static final Set<String> STATEMENT_WORDS = Set.of("return", "else", "throw", "new", "yield", "case", "do");

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
                List<String> calls = calls(code(file));
                if (!calls.isEmpty()) {
                    offenders.add(name + " " + calls);
                }
            }
        }

        assertThat(sources).as("mainのsourceを読めていること").isGreaterThan(100);
        assertThat(offenders)
                .as("executeSend は send() を通して呼ぶ（直接呼ぶと配信履歴の保存とtenantの取得を飛ばす）")
                .isEmpty();
        // 検査が空振りしていないこと: 宣言を除いた呼出しだけを数える
        String impl = code(MAIN.resolve(IMPL));
        assertThat(calls(impl))
                .as("MailServiceImpl の呼出し（send() の proxy 経由・同期 fallback と executeSend(MailDelivery) の委譲）")
                .containsExactly("self.executeSend(", "executeSend(", "executeSend(");
        assertThat(CALL.matcher(impl).results().count() - calls(impl).size())
                .as("MailServiceImpl の宣言（executeSend(MailDelivery) と executeSend(MailDelivery, String)）")
                .isEqualTo(2);
        assertThat(impl).contains("self.executeSend(handoff, tenantId);", "executeSend(handoff, tenantId);",
                "executeSend(delivery, AccountingTenantContextHolder.requireTenantContext());");
    }

    @Test
    void 宣言と呼出しを見分ける() {
        assertThat(calls("public void executeSend(MailDelivery d) { other.executeSend(d, t); }"))
                .containsExactly("other.executeSend(");
        assertThat(calls("@Async\n    public void executeSend(MailDelivery d, String t) {}")).isEmpty();
        assertThat(calls("List<String> executeSend(X x);")).isEmpty();
        assertThat(calls("if (a) { executeSend(x); } else executeSend(y); return executeSend(z);"))
                .containsExactly("executeSend(", "executeSend(", "executeSend(");
        assertThat(calls("list.forEach(service::executeSend);")).containsExactly("::executeSend");
        assertThat(calls("Runnable r = () -> executeSend(x);")).containsExactly("executeSend(");
    }

    /** 宣言を除いた呼出し（受け手の式があれば {@code receiver.} を含めて返す）。 */
    private static List<String> calls(String code) {
        List<String> calls = new ArrayList<>();
        Matcher matcher = CALL.matcher(code);
        while (matcher.find()) {
            if (isDeclaration(code, matcher.start())) {
                continue;
            }
            int start = matcher.start();
            while (!code.startsWith("::", matcher.start()) && start > 0
                    && (Character.isJavaIdentifierPart(code.charAt(start - 1)) || code.charAt(start - 1) == '.')) {
                start--;
            }
            calls.add(code.substring(start, matcher.end()).replaceAll("\\s+", ""));
        }
        return calls;
    }

    /**
     * メソッドの宣言か（直前の語が戻り値の型）。呼出しは {@code .}・{@code (}・{@code ,}・{@code =}・{@code ;}・
     * {@code {}・{@code }}・{@code ->} や return・else 等の後に来るため、直前が型名（識別子・{@code >}・{@code ]}）で、
     * その語が文の語でなければ宣言とみなす。
     */
    private static boolean isDeclaration(String code, int start) {
        if (code.startsWith("::", start)) {
            return false;
        }
        int index = start - 1;
        while (index >= 0 && Character.isWhitespace(code.charAt(index))) {
            index--;
        }
        if (index < 0) {
            return false;
        }
        char previous = code.charAt(index);
        if (previous == '>' || previous == ']') {
            return index == 0 || code.charAt(index - 1) != '-';
        }
        if (!Character.isJavaIdentifierPart(previous)) {
            return false;
        }
        int end = index + 1;
        while (index >= 0 && Character.isJavaIdentifierPart(code.charAt(index))) {
            index--;
        }
        return !STATEMENT_WORDS.contains(code.substring(index + 1, end));
    }

    /** コメントを除いたsource。 */
    private static String code(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "");
    }
}
