package com.ses.service.compliance;

import com.ses.service.MenuCacheService;

/**
 * complianceメニュー権限の再チェック（design §5.3）。
 * 契約詳細等の他画面へcompliance情報（findings・帳票）を埋め込む場合、
 * 画面自身のmenu権限で足りるとみなさず、ここで再チェックする。
 *  - 管理者: 常に可
 *  - HR: design §5.3の「HR/法務=全件・全field」に従い常に可（V53のmenu seedは管理者/マネージャーのみのため、HRはmenu表に依存しない）
 *  - その他: compliance menuキーを保有する場合のみ
 * fail-closed（menuキャッシュ取得失敗は不可視）。
 */
public final class ComplianceAccessControl {

    private ComplianceAccessControl() {
    }

    public static boolean canViewCompliance(String role, MenuCacheService menuCacheService) {
        if ("管理者".equals(role) || "HR".equals(role)) {
            return true;
        }
        if (menuCacheService == null) {
            return false;
        }
        return menuCacheService.getMenuKeysByRole(role).contains("compliance");
    }
}
