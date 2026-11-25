package com.ses.featureenablement.service;

import com.ses.config.feature.Feature;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 閉鎖の確認の dialog に出す警告の件数（拡張点。feature-enablement-console design §5.5・§8.2、FE-RQ-011）。各機能が開放単位ごとに
 * 足す（例: NF01 二者確認は二者確認を含む有効な template の件数。FS 4.9.5 の (3)。FE-B01）。開放単位の詳細の API が返し、画面が
 * 閉鎖の dialog に出す。件数は固定の code と数だけで、値・氏名を含めない。読めなければ警告を出さない（閉鎖そのものは妨げない）。
 */
public interface FeatureEnablementCloseWarningSource {

    /** 受け持つ開放単位。 */
    Set<Feature> features();

    /** 閉鎖の警告（件数が0の警告は返さない）。 */
    List<CloseWarning> closeWarnings(Feature feature);

    /**
     * 閉鎖の警告の1件。
     *
     * @param code  文言の key の末尾（{@code featureEnablement.closeWarning.<code>}）
     * @param count 件数
     */
    record CloseWarning(String code, long count) {

        public CloseWarning {
            Objects.requireNonNull(code, "code");
        }
    }
}
