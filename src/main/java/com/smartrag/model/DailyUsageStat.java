package com.smartrag.model;

import java.time.LocalDate;

public record DailyUsageStat(
        LocalDate recordDate,
        Long totalAmount,
        Long totalRequestCount
) {
}