package com.ses.dto.user;

import com.ses.entity.SysUser;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * ユーザー一覧行。要員↔アカウント紐付けの有無を付与する。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SysUserListDto extends SysUser {

    /**
     * 要員マスタと紐付いているか。
     *
     * <p>ロールが {@code 要員} の行だけ設定し、それ以外は null にする。要員以外のロールに
     * 紐付けは不要であり、null と false を区別しないと全ユーザーへ不要な警告が出るため。
     *
     * <p>role=要員 かつ false は明確な不備である。紐付けが無い要員アカウントは
     * ログインしても `/my/timesheet` が常に403（error.my.notLinked）になり、何も操作できない。
     */
    private Boolean engineerLinked;
}
