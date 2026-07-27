package com.ses.dto.organization;

/**
 * 所属登録の対象ユーザー候補。
 * パスワードハッシュ・失敗回数・ロック期限などを含む {@code SysUser} を返さないための公開項目allow-list。
 */
public record AssignableUserDto(Long id, String username, String realName, String role) {
}
