package com.aicard.translation.qualify;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QualifyTermServiceTest {

    private final QualifyTermRepository repository = mock(QualifyTermRepository.class);
    private final QualifyTermService service = new QualifyTermService(repository, "ok,yes", "iphone");

    private QualifyTerm term(Long id, String type, String term) {
        return QualifyTerm.builder().id(id).customerId(1L).storeId(2L).type(type).term(term).build();
    }

    @Test
    void mergesDefaultBaselineWithDbTerms() {
        when(repository.findByCustomerIdAndStoreIdAndType(1L, 2L, "whitelist"))
                .thenReturn(List.of(term(1L, "whitelist", "ありがとう")));

        Set<String> whitelist = service.getWhitelist(1L, 2L);

        assertThat(whitelist).contains("ok", "yes", "ありがとう"); // 默认基线 + 数据库
    }

    @Test
    void addClearsCacheSoNextGetReloads() {
        when(repository.findByCustomerIdAndStoreIdAndType(1L, 2L, "brand")).thenReturn(List.of());
        assertThat(service.getBrandTerms(1L, 2L)).contains("iphone"); // 首次加载（默认 + 空库）

        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.add(1L, 2L, "brand", "dyson"); // 添加品牌词，清缓存

        when(repository.findByCustomerIdAndStoreIdAndType(1L, 2L, "brand"))
                .thenReturn(List.of(term(2L, "brand", "dyson")));
        assertThat(service.getBrandTerms(1L, 2L)).contains("iphone", "dyson"); // 重载含新词
    }

    @Test
    void normalizesTermToLowercase() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        QualifyTerm saved = service.add(1L, 2L, "brand", "IPHONE");
        assertThat(saved.getTerm()).isEqualTo("iphone");
    }
}
