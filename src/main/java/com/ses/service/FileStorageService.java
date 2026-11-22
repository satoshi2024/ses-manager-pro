package com.ses.service;

import com.ses.common.enums.FileKind;
import com.ses.dto.file.StoredFile;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * ファイル保存サービス。
 */
public interface FileStorageService {

    /**
     * ファイルを検証して保存する。拡張子・サイズ・Content-Typeが不正な場合は BusinessException。
     */
    StoredFile store(MultipartFile file, FileKind kind);

    /**
     * バイト配列からファイルを検証して保存する。
     */
    StoredFile store(byte[] data, String originalName, FileKind kind);

    /**
     * 保存済みファイルを読み込む。パストラバーサルは拒否する。
     */
    Resource load(String storedName);

    /**
     * server が生成した種別（{@link FileKind#isServerGeneratedOnly()}。DSAR の開示 package 等）の file を、その種別の専用経路から
     * 読む。汎用の {@link #load} はこれらの種別を開かない。既定は {@link #load} と同じ（metadata を持たない実装向け）。
     */
    default Resource loadServerGenerated(String storedName, FileKind kind) {
        return load(storedName);
    }

    /**
     * 専用の経路だけが読む種別（{@link FileKind#isDedicatedPathOnly()}。機能の開放の署名の文書等）の file を、その種別の専用経路から
     * 読む（種別の一致を要する）。汎用の {@link #load} はこれらの種別を開かない。既定は {@link #load} と同じ（metadata を持たない実装向け）。
     */
    default Resource loadDedicated(String storedName, FileKind kind) {
        return load(storedName);
    }

    /**
     * 専用の経路だけの種別（{@link FileKind#isDedicatedPathOnly()}）で、持ち主の行と metadata が登録の transaction の rollback で
     * 消えた実体を消す（metadata が無いため {@link #delete} では消せない。review rb-fe-a1 P3-1）。呼ぶ側は、同じ要求で自分が保存し
     * rollback で持ち主を失った保存名だけを渡し、持ち主の行が無いことを確かめてから呼ぶ。実装は metadata の行が無いことを確かめ
     * （あれば 409）、backup の書込み静止の最中は拒否する（1a4fd48d 差分 P3-N1）。既定は何もしない（孤児の清掃が消す）。
     */
    default void discardUnregistered(String storedName, FileKind kind) {
    }

    /** 保持期限到達等の認可済み処理で、現在tenantの保存実体を消去する。 */
    void delete(String storedName);

    /** quarantine中のファイルを再scanし、CLEANなら公開領域へ移す。 */
    boolean rescan(String storedName);
}
