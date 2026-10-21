package com.smartrag.model;

import java.time.LocalDate;

public record DailyReqCountStat(
        LocalDate recordDate,
        Long totalRequestCount
) {
}