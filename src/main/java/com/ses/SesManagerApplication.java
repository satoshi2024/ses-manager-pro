package com.ses;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SES Manager Pro アプリケーションエントリーポイント
 * SES（システムエンジニアリングサービス）会社管理システム
 */
@SpringBootApplication
@EnableScheduling
public class SesManagerApplication {
    public static void main(String[] args) {
        SpringApplication.run(SesManagerApplication.class, args);
    }
}
