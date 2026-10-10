package com.aicard.report.job;

import com.aicard.report.service.ReportService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReportGenerationJobTest {

    @Test
    void weeklyGeneratesLastWeek() {
        ReportService reports = mock(ReportService.class);
        ReportGenerationJob job = new ReportGenerationJob(reports);

        job.weekly();

        verify(reports).generateDue(eq("weekly"), any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    void monthlyGeneratesLastMonth() {
        ReportService reports = mock(ReportService.class);
        ReportGenerationJob job = new ReportGenerationJob(reports);

        job.monthly();

        verify(reports).generateDue(eq("monthly"), any(LocalDate.class), any(LocalDate.class));
    }
}
