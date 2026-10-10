package com.aicard.report.repository;

/** 单设备某周期聚合结果。 */
public record DeviceAggregate(int turnCount, int sessionCount, int qaHitCount, int activeDays, String metrics) {}
