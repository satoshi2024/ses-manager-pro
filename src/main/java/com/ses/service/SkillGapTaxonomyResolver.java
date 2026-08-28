package com.ses.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.SkillTag;
import com.ses.entity.SkillTagAlias;
import com.ses.mapper.SkillTagAliasMapper;
import com.ses.mapper.SkillTagMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * skill-gap専用のtaxonomy lookup。
 * 既存の外部取込向け{@link SkillTagResolver#resolveOrCreate(String)}とは異なり、
 * 未知skillをmasterへ書き込まず、unknownとして返す。
 */
@Service
@RequiredArgsConstructor
public class SkillGapTaxonomyResolver {

    private static final String DEFAULT_TENANT = "default";

    private final SkillTagMapper skillTagMapper;
    private final SkillTagAliasMapper skillTagAliasMapper;

    public Resolution resolveName(String input, LocalDate asOf) {
        String normalized = normalize(input);
        if (normalized.isBlank()) {
            return Resolution.unknown(input, normalized);
        }

        SkillTag canonical = skillTagMapper.selectList(new LambdaQueryWrapper<SkillTag>()).stream()
                .filter(tag -> normalized.equals(normalize(tag.getSkillName())))
                .findFirst()
                .orElse(null);
        if (canonical != null) {
            return Resolution.canonical(input, normalized, canonical);
        }

        SkillTagAlias alias = activeAliases(asOf).stream()
                .filter(candidate -> normalized.equals(normalize(candidate.getNormalizedAlias())))
                .sorted(Comparator.comparing(SkillTagAlias::getId,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst()
                .orElse(null);
        if (alias == null || alias.getCanonicalSkillId() == null || alias.getApprovedBy() == null) {
            return Resolution.unknown(input, normalized);
        }
        SkillTag aliased = skillTagMapper.selectById(alias.getCanonicalSkillId());
        return aliased == null
                ? Resolution.unknown(input, normalized)
                : Resolution.alias(input, normalized, aliased, alias);
    }

    public Resolution resolveCanonicalId(Long skillId) {
        if (skillId == null) {
            return Resolution.unknown(null, "");
        }
        SkillTag canonical = skillTagMapper.selectById(skillId);
        return canonical == null
                ? Resolution.unknown("skill#" + skillId, "skill#" + skillId)
                : Resolution.canonical("skill#" + skillId, "skill#" + skillId, canonical);
    }

    public String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .toUpperCase(Locale.ROOT);
    }

    public String fingerprint(LocalDate asOf) {
        String aliases = activeAliases(asOf).stream()
                .sorted(Comparator.comparing(SkillTagAlias::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .filter(alias -> alias.getApprovedBy() != null)
                .map(alias -> String.valueOf(alias.getId()) + ":" + alias.getCanonicalSkillId()
                        + ":" + alias.getNormalizedAlias() + ":" + alias.getValidFrom()
                        + ":" + alias.getValidTo() + ":" + alias.getVersion())
                .reduce("", (left, right) -> left + "|" + right);
        String skills = skillTagMapper.selectList(new LambdaQueryWrapper<SkillTag>()).stream()
                .sorted(Comparator.comparing(SkillTag::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(tag -> String.valueOf(tag.getId()) + ":" + normalize(tag.getSkillName()))
                .reduce("", (left, right) -> left + "|" + right);
        return sha256(skills + "#" + aliases + "#" + asOf);
    }

    private List<SkillTagAlias> activeAliases(LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        return skillTagAliasMapper.selectList(new LambdaQueryWrapper<SkillTagAlias>()
                .eq(SkillTagAlias::getTenantId, DEFAULT_TENANT)
                .eq(SkillTagAlias::getDeletedFlag, 0)
                .and(wrapper -> wrapper.isNull(SkillTagAlias::getValidFrom)
                        .or().le(SkillTagAlias::getValidFrom, date))
                .and(wrapper -> wrapper.isNull(SkillTagAlias::getValidTo)
                        .or().ge(SkillTagAlias::getValidTo, date)));
    }

    private String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }

    public record Resolution(String inputName, String normalizedInput, Long canonicalSkillId,
                             String canonicalSkillName, Long aliasId, String resolution) {
        static Resolution canonical(String input, String normalized, SkillTag tag) {
            return new Resolution(input, normalized, tag.getId(), tag.getSkillName(), null, "CANONICAL");
        }

        static Resolution alias(String input, String normalized, SkillTag tag, SkillTagAlias alias) {
            return new Resolution(input, normalized, tag.getId(), tag.getSkillName(), alias.getId(), "ALIAS");
        }

        static Resolution unknown(String input, String normalized) {
            return new Resolution(input, normalized, null, null, null, "UNKNOWN");
        }

        public boolean unknown() {
            return canonicalSkillId == null;
        }
    }
}
