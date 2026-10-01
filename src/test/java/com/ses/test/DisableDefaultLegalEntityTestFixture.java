package com.ses.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 組織fixtureをテスト自身で構築するため、既定法人fixtureの投入だけを無効化する。 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface DisableDefaultLegalEntityTestFixture {
}
