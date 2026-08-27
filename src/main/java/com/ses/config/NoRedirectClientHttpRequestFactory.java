package com.ses.config;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.io.IOException;
import java.net.HttpURLConnection;

/**
 * リダイレクトを一切追跡しない {@link SimpleClientHttpRequestFactory}。
 * <p>
 * SSRF対策として、検証済みの宛先が3xxで別ホストへ誘導されても追従しないようにする。
 * リダイレクト先を辿ると、検証を通過した安全なホストから内部アドレスへ
 * 再誘導される余地が生まれるため、追跡自体を無効化する。
 */
public class NoRedirectClientHttpRequestFactory extends SimpleClientHttpRequestFactory {

    @Override
    protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
        super.prepareConnection(connection, httpMethod);
        connection.setInstanceFollowRedirects(false);
    }
}
