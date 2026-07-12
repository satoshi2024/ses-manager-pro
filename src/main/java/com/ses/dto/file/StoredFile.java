package com.ses.dto.file;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 保存済みファイル情報。
 * storedName（UUID+拡張子）で配信し、originalName は呼び出し側が別途保持する。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StoredFile {

    /** 保存ファイル名（UUID + 拡張子） */
    private String storedName;

    /** 元のファイル名 */
    private String originalName;

    /** ファイルサイズ（バイト） */
    private long size;
}
