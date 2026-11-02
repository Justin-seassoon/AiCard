package com.aicard.ota.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionComparatorTest {

    @Test
    void comparesNumericSegments() {
        assertThat(VersionComparator.compare("3.4", "3.3")).isPositive();
        assertThat(VersionComparator.compare("3.10", "3.9")).isPositive();
        assertThat(VersionComparator.compare("3.3", "3.3")).isZero();
        assertThat(VersionComparator.compare("3.3", "3.3.0")).isZero();
        assertThat(VersionComparator.compare("v3.4", "3.3")).isPositive();
        assertThat(VersionComparator.compare("3.3-beta", "3.3")).isNegative();
    }

    @Test
    void blankIsLowest() {
        assertThat(VersionComparator.compare(null, "1.0")).isNegative();
        assertThat(VersionComparator.compare("1.0", "")).isPositive();
        assertThat(VersionComparator.compare(null, null)).isZero();
    }
}
