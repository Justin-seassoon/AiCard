package com.aicard.translation.qualify;

public record QualifyTermDto(Long id, String type, String term) {
    public static QualifyTermDto from(QualifyTerm t) {
        return new QualifyTermDto(t.getId(), t.getType(), t.getTerm());
    }
}
