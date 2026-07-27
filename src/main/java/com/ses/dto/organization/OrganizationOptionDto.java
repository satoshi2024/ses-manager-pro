package com.ses.dto.organization;

/**
 * 組織・原価部門の選択肢。画面のプルダウン用に公開項目をid/コード/名称/親IDへ限定する。
 * エンティティをそのまま返すと有効期間・版番号・統合先などの内部列まで漏れるため専用DTOにする。
 */
public record OrganizationOptionDto(Long id, String code, String name, Long parentId) {
}
