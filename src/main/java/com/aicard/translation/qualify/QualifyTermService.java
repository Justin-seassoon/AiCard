package com.aicard.translation.qualify;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 质量门控词表服务：品牌词表 + 短词白名单，按租户隔离、后台可配。
 * 词表缓存在内存（懒加载），CRUD 时清缓存 → 下次查询重载，动态生效无需重启。
 * application.yml 的静态词表作为「默认基线」（所有租户共享），数据库词表在其上追加。
 */
@Service
public class QualifyTermService {

    private final QualifyTermRepository repository;
    private final Set<String> defaultWhitelist;
    private final Set<String> defaultBrandTerms;
    private final ConcurrentHashMap<String, Set<String>> cache = new ConcurrentHashMap<>();

    public QualifyTermService(QualifyTermRepository repository,
                              @Value("${translation.qualify.whitelist:}") String whitelistCsv,
                              @Value("${translation.qualify.brand-terms:}") String brandTermsCsv) {
        this.repository = repository;
        this.defaultWhitelist = splitLowerCsv(whitelistCsv);
        this.defaultBrandTerms = splitLowerCsv(brandTermsCsv);
    }

    public Set<String> getWhitelist(Long customerId, Long storeId) {
        return cache.computeIfAbsent(key(customerId, storeId, "whitelist"),
                k -> load(customerId, storeId, "whitelist", defaultWhitelist));
    }

    public Set<String> getBrandTerms(Long customerId, Long storeId) {
        return cache.computeIfAbsent(key(customerId, storeId, "brand"),
                k -> load(customerId, storeId, "brand", defaultBrandTerms));
    }

    public List<QualifyTerm> list(Long customerId, Long storeId, String type) {
        return repository.findByCustomerIdAndStoreIdAndType(customerId, storeId, type);
    }

    public QualifyTerm add(Long customerId, Long storeId, String type, String term) {
        String normalized = term.trim().toLowerCase();
        QualifyTerm saved = repository.save(QualifyTerm.builder()
                .customerId(customerId).storeId(storeId).type(type).term(normalized)
                .createdAt(Instant.now()).build());
        cache.remove(key(customerId, storeId, type));
        return saved;
    }

    public void remove(Long id) {
        repository.findById(id).ifPresent(t -> {
            repository.deleteById(id);
            cache.remove(key(t.getCustomerId(), t.getStoreId(), t.getType()));
        });
    }

    private Set<String> load(Long customerId, Long storeId, String type, Set<String> defaults) {
        Set<String> result = new HashSet<>(defaults);
        repository.findByCustomerIdAndStoreIdAndType(customerId, storeId, type)
                .forEach(t -> result.add(t.getTerm()));
        return result;
    }

    private static String key(Long customerId, Long storeId, String type) {
        return customerId + ":" + storeId + ":" + type;
    }

    private static Set<String> splitLowerCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }
}
