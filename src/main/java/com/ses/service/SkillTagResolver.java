package com.ses.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.SkillTag;
import com.ses.mapper.SkillTagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * スキル名文字列を m_skill_tag の skill_id に解決するユーティリティサービス。
 * 既存タグは正規化（trim・全半角・大文字小文字）で突合し、未存在なら '未分類' で新規作成する。
 * 外部データ（AIスキルシート解析・CSV取込等）からのスキル登録に使用する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillTagResolver {

    private final SkillTagMapper skillTagMapper;

    /**
     * スキル名を正規化して skill_id を返す。マスタに存在しない場合は新規作成する。
     *
     * @param skillName スキル名文字列（例: "react", "AWS"）
     * @return m_skill_tag の id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long resolveOrCreate(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("スキル名が空です");
        }
        String normalized = normalize(skillName);

        // 正規化名で全件照合（件数は多くないのでメモリ照合で十分）
        SkillTag found = skillTagMapper.selectList(new LambdaQueryWrapper<SkillTag>())
                .stream()
                .filter(t -> normalize(t.getSkillName()).equals(normalized))
                .findFirst()
                .orElse(null);

        if (found != null) {
            return found.getId();
        }

        // 未登録 → 新規作成
        SkillTag newTag = SkillTag.builder()
                .skillName(skillName.trim())
                .category("未分類")
                .build();
        skillTagMapper.insert(newTag);
        log.info("スキルタグを新規作成しました: name={}, id={}", newTag.getSkillName(), newTag.getId());
        return newTag.getId();
    }

    /**
     * スキル名を検索用に正規化する。
     * - 前後空白除去
     * - 全角英数字→半角
     * - 大文字→小文字
     */
    private String normalize(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        // 全角英数字・記号を半角に変換
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (char c : trimmed.toCharArray()) {
            if (c >= '！' && c <= '～') {
                sb.append((char) (c - '！' + '!'));
            } else if (c == '\u3000') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase();
    }
}
