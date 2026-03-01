package com.codepilot.orchestrator.service;

import com.codepilot.orchestrator.executor.WorkspaceClient;
import com.codepilot.orchestrator.model.*;
import com.codepilot.orchestrator.repository.JobRepository;
import com.codepilot.orchestrator.repository.StepRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JobService.
 *
 * All I/O (DB + HTTP) is mocked with Mockito — no Spring context,
 * no database, no network. These tests run in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock JobRepository   jobRepo;
    @Mock StepRepository  stepRepo;
    @Mock WorkspaceClient workspaceClient;

    SimpleMeterRegistry meterRegistry;
    JobService          service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new JobService(jobRepo, stepRepo, workspaceClient, meterRegistry);
    }

    // ------------------------------------------------------------------
    // submit()
    // ------------------------------------------------------------------

    @Test
    void submit_happyPath_createsJobAndFirstStep() {
        Job savedJob = jobWithId();
        when(jobRepo.save(any())).thenReturn(savedJob);
        doNothing().when(workspaceClient).createWorkspace(any(), any(), any());

        Job result = service.submit("https://github.com/org/repo.git", "main", null, null);

        // Workspace must be created
        verify(workspaceClient).createWorkspace(any(), eq("https://github.com/org/repo.git"), eq("main"));
        // First step must be REPO_MAPPER
        ArgumentCaptor<Step> stepCaptor = ArgumentCaptor.forClass(Step.class);
        verify(stepRepo).save(stepCaptor.capture());
        assertThat(stepCaptor.getValue().getRole()).isEqualTo(AgentRole.REPO_MAPPER);
        // Job state must advance to MAP_REPO
        assertThat(result.getState()).isEqualTo(JobState.MAP_REPO);
    }

    @Test
    void submit_workspaceCreationFails_jobMarkedFailed() {
        Job savedJob = jobWithId();
        when(jobRepo.save(any())).thenReturn(savedJob);
        doThrow(new com.codepilot.orchestrator.executor.ExecutorException("clone failed", null))
                .when(workspaceClient).createWorkspace(any(), any(), any());

        Job result = service.submit("https://github.com/org/repo.git", "main", null, null);

        assertThat(result.getState()).isEqualTo(JobState.FAILED);
        verify(stepRepo, never()).save(any());   // no steps created on failure
    }

    @Test
    void submit_happyPath_incrementsSubmittedCounter() {
        Job savedJob = jobWithId();
        when(jobRepo.save(any())).thenReturn(savedJob);
        doNothing().when(workspaceClient).createWorkspace(any(), any(), any());

        service.submit("https://github.com/org/repo.git", "main", null, null);

        assertThat(meterRegistry.counter("codepilot.jobs.submitted").count()).isEqualTo(1.0);
    }

    @Test
    void submit_workspaceCreationFails_incrementsFailedCounter() {
        Job savedJob = jobWithId();
        when(jobRepo.save(any())).thenReturn(savedJob);
        doThrow(new com.codepilot.orchestrator.executor.ExecutorException("clone failed", null))
                .when(workspaceClient).createWorkspace(any(), any(), any());

        service.submit("https://github.com/org/repo.git", "main", null, null);

        assertThat(meterRegistry.counter("codepilot.jobs.failed", "reason", "workspace_error").count())
                .isEqualTo(1.0);
    }

    // ------------------------------------------------------------------
    // failStep() — retry logic
    // ------------------------------------------------------------------

    @Test
    void failStep_firstAttempt_resetsStepToPending() {
        Step step = stepWithAttempt(0);
        when(stepRepo.save(any())).thenReturn(step);

        service.failStep(step, "timeout");

        assertThat(step.getState()).isEqualTo(StepState.PENDING);  // will retry
        assertThat(step.getAttempt()).isEqualTo(1);
        // Job should NOT be marked FAILED yet
        verify(jobRepo, never()).save(argThat(j -> j.getState() == JobState.FAILED));
    }

    @Test
    void failStep_secondAttempt_stillRetries() {
        Step step = stepWithAttempt(1);
        when(stepRepo.save(any())).thenReturn(step);

        service.failStep(step, "timeout");

        assertThat(step.getState()).isEqualTo(StepState.PENDING);
        assertThat(step.getAttempt()).isEqualTo(2);
    }

    @Test
    void failStep_thirdAttempt_permanentlyFails() {
        Step step = stepWithAttempt(2);   // already attempted twice before
        Job job   = jobWithId();
        when(jobRepo.findById(any())).thenReturn(Optional.of(job));
        when(stepRepo.save(any())).thenReturn(step);

        service.failStep(step, "max retries");

        assertThat(step.getState()).isEqualTo(StepState.FAILED);
        // Job must be marked FAILED
        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepo).save(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getState()).isEqualTo(JobState.FAILED);
        // Workspace must be cleaned up
        verify(workspaceClient).deleteWorkspace(job.getWorkspaceRef());
    }

    @Test
    void failStep_workspaceDeleteFails_jobStillMarkedFailed() {
        // A broken executor must never roll back the DB state
        Step step = stepWithAttempt(2);
        Job job   = jobWithId();
        when(jobRepo.findById(any())).thenReturn(Optional.of(job));
        when(stepRepo.save(any())).thenReturn(step);
        doThrow(new com.codepilot.orchestrator.executor.ExecutorException("network error", null))
                .when(workspaceClient).deleteWorkspace(any());

        service.failStep(step, "max retries");

        // Job must still be FAILED despite the cleanup failure
        assertThat(job.getState()).isEqualTo(JobState.FAILED);
    }

    // ------------------------------------------------------------------
    // completeStep() — pipeline advancement
    // ------------------------------------------------------------------

    @Test
    void completeStep_repoMapper_advancesToPlanner() {
        Step step = stepForRole(AgentRole.REPO_MAPPER);
        Job job   = jobWithId();
        when(jobRepo.findById(any())).thenReturn(Optional.of(job));
        when(stepRepo.save(any())).thenReturn(step);

        service.completeStep(step, "{\"repo_map\": true}");

        // Next step must be PLANNER
        ArgumentCaptor<Step> stepCaptor = ArgumentCaptor.forClass(Step.class);
        verify(stepRepo, atLeastOnce()).save(stepCaptor.capture());
        List<Step> saved = stepCaptor.getAllValues();
        boolean plannerCreated = saved.stream()
                .anyMatch(s -> s.getRole() == AgentRole.PLANNER && s.getState() == StepState.PENDING);
        assertThat(plannerCreated).isTrue();

        // Job state must advance to PLAN
        assertThat(job.getState()).isEqualTo(JobState.PLAN);
    }

    @Test
    void completeStep_finalizer_jobMarkedDone() {
        // FINALIZER is the last pipeline step — completing it must mark the job DONE.
        Step step = stepForRole(AgentRole.FINALIZER);
        Job job   = jobWithId();
        when(jobRepo.findById(any())).thenReturn(Optional.of(job));
        when(stepRepo.save(any())).thenReturn(step);

        service.completeStep(step, "{\"summary\": \"done\"}");

        // No new step should be created after the final role
        verify(stepRepo, times(1)).save(any());  // only the FINALIZER step itself
        // Job must be DONE
        assertThat(job.getState()).isEqualTo(JobState.DONE);
        // Workspace must be cleaned up
        verify(workspaceClient).deleteWorkspace(job.getWorkspaceRef());
    }

    @Test
    void completeStep_intermediateRole_doesNotCleanupWorkspace() {
        // Cleanup should only happen at terminal state (DONE), not between steps
        Step step = stepForRole(AgentRole.PLANNER);
        Job job   = jobWithId();
        when(jobRepo.findById(any())).thenReturn(Optional.of(job));
        when(stepRepo.save(any())).thenReturn(step);

        service.completeStep(step, "{\"plan\": true}");

        verify(workspaceClient, never()).deleteWorkspace(any());
    }

    @Test
    void completeStep_workspaceDeleteFails_jobStillMarkedDone() {
        // A broken executor must never roll back the DB state
        Step step = stepForRole(AgentRole.FINALIZER);
        Job job   = jobWithId();
        when(jobRepo.findById(any())).thenReturn(Optional.of(job));
        when(stepRepo.save(any())).thenReturn(step);
        doThrow(new com.codepilot.orchestrator.executor.ExecutorException("network error", null))
                .when(workspaceClient).deleteWorkspace(any());

        service.completeStep(step, "{\"summary\": \"done\"}");

        // Job must still be DONE despite the cleanup failure
        assertThat(job.getState()).isEqualTo(JobState.DONE);
    }

    @Test
    void completeStep_finalizer_incrementsCompletedCounter() {
        Step step = stepForRole(AgentRole.FINALIZER);
        Job job   = jobWithId();
        when(jobRepo.findById(any())).thenReturn(Optional.of(job));
        when(stepRepo.save(any())).thenReturn(step);

        service.completeStep(step, "{\"summary\": \"done\"}");

        assertThat(meterRegistry.counter("codepilot.jobs.completed").count()).isEqualTo(1.0);
    }

    @Test
    void completeStep_testerFails_firstFailure_incrementsBacktrackedCounter() {
        Step step = stepForRole(AgentRole.TESTER);
        Job job   = jobWithId();
        when(jobRepo.findById(any())).thenReturn(Optional.of(job));
        when(stepRepo.save(any())).thenReturn(step);

        service.completeStep(step, "{\"tests_passed\":false}");

        assertThat(meterRegistry.counter("codepilot.jobs.backtracked").count()).isEqualTo(1.0);
    }

    @Test
    void failStep_permanent_incrementsStepFailedAndJobFailedCounters() {
        Step step = stepWithAttempt(2);
        step = stepForRoleWithAttempt(AgentRole.PLANNER, 2);
        Job job   = jobWithId();
        when(jobRepo.findById(any())).thenReturn(Optional.of(job));
        when(stepRepo.save(any())).thenReturn(step);

        service.failStep(step, "max retries");

        assertThat(meterRegistry.counter("codepilot.steps.failed", "role", "PLANNER").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("codepilot.jobs.failed", "reason", "max_retries").count())
                .isEqualTo(1.0);
    }

    // ------------------------------------------------------------------
    // failStep(permanent=true) — skip-retry path for permanent API errors
    // ------------------------------------------------------------------

    @Test
    void failStep_permanentFlag_onFirstAttempt_skipsRetryAndFailsJobImmediately() {
        // Even though attempt=0 (normally would retry), permanent=true must fail immediately.
        Step step = stepForRoleWithAttempt(AgentRole.PLANNER, 0);
        Job job   = jobWithId();
        when(jobRepo.findById(any())).thenReturn(Optional.of(job));
        when(stepRepo.save(any())).thenReturn(step);

        service.failStep(step, "Claude API error 401: Unauthorized", true);

        // Step must be FAILED, not PENDING
        assertThat(step.getState()).isEqualTo(StepState.FAILED);
        // Job must be FAILED immediately
        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepo).save(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getState()).isEqualTo(JobState.FAILED);
        // Workspace must be cleaned up
        verify(workspaceClient).deleteWorkspace(job.getWorkspaceRef());
    }

    @Test
    void failStep_permanentFlag_usesCorrectMetricTag() {
        Step step = stepForRoleWithAttempt(AgentRole.PLANNER, 0);
        Job job   = jobWithId();
        when(jobRepo.findById(any())).thenReturn(Optional.of(job));
        when(stepRepo.save(any())).thenReturn(step);

        service.failStep(step, "Claude API error 402: Payment Required", true);

        assertThat(meterRegistry.counter("codepilot.jobs.failed", "reason", "permanent_error").count())
                .isEqualTo(1.0);
        // Must NOT increment max_retries counter
        assertThat(meterRegistry.counter("codepilot.jobs.failed", "reason", "max_retries").count())
                .isEqualTo(0.0);
    }

    // ------------------------------------------------------------------
    // saveHistory() — conversation history persistence (§5.3)
    // ------------------------------------------------------------------

    @Test
    void saveHistory_persistsJsonStringToStep() {
        Step step = stepForRole(AgentRole.PLANNER);
        when(stepRepo.save(any())).thenReturn(step);

        service.saveHistory(step, "[{\"role\":\"user\",\"content\":\"hello\"}]");

        assertThat(step.getConversationHistory())
                .isEqualTo("[{\"role\":\"user\",\"content\":\"hello\"}]");
        verify(stepRepo).save(step);
    }

    // ------------------------------------------------------------------
    // completeStep() — backtracking (§4.2)
    // ------------------------------------------------------------------

    @Test
    void completeStep_testerReportsTestsFailed_firstFailure_backtracksToPlan() {
        Step step = stepForRole(AgentRole.TESTER);
        Job job   = jobWithId();
        // consecutiveTestFailures starts at 0 (first failure)
        when(jobRepo.findById(any())).thenReturn(Optional.of(job));
        when(stepRepo.save(any())).thenReturn(step);

        service.completeStep(step, "{\"tests_passed\":false,\"failures\":2}");

        // Job must backtrack to PLAN, not FAILED
        assertThat(job.getState()).isEqualTo(JobState.PLAN);
        assertThat(job.getConsecutiveTestFailures()).isEqualTo(1);
        assertThat(job.getIterationCount()).isEqualTo(1);
        // A new PLANNER step must be queued
        ArgumentCaptor<Step> captor = ArgumentCaptor.forClass(Step.class);
        verify(stepRepo, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(s -> s.getRole() == AgentRole.PLANNER && s.getState() == StepState.PENDING);
        // Workspace must NOT be cleaned up yet
        verify(workspaceClient, never()).deleteWorkspace(any());
    }

    @Test
    void completeStep_testerReportsTestsFailed_secondConsecutiveFailure_jobFailed() {
        Step step = stepForRole(AgentRole.TESTER);
        Job job   = jobWithId();
        job.incrementConsecutiveTestFailures();   // simulate first failure already recorded (= 1)
        when(jobRepo.findById(any())).thenReturn(Optional.of(job));
        when(stepRepo.save(any())).thenReturn(step);

        service.completeStep(step, "{\"tests_passed\":false,\"failures\":3}");

        // Second consecutive failure → backtrack budget exhausted → FAILED
        assertThat(job.getState()).isEqualTo(JobState.FAILED);
        assertThat(job.getConsecutiveTestFailures()).isEqualTo(2);
        // Workspace must be cleaned up
        verify(workspaceClient).deleteWorkspace(job.getWorkspaceRef());
    }

    @Test
    void completeStep_testerReportsTestsPassed_resetsCounterAndAdvancesToReviewer() {
        Step step = stepForRole(AgentRole.TESTER);
        Job job   = jobWithId();
        job.incrementConsecutiveTestFailures();   // prior failure on record (= 1)
        when(jobRepo.findById(any())).thenReturn(Optional.of(job));
        when(stepRepo.save(any())).thenReturn(step);

        service.completeStep(step, "{\"tests_passed\":true,\"failures\":0}");

        // Counter must be reset to 0
        assertThat(job.getConsecutiveTestFailures()).isEqualTo(0);
        // Pipeline advances to REVIEW
        assertThat(job.getState()).isEqualTo(JobState.REVIEW);
        ArgumentCaptor<Step> captor = ArgumentCaptor.forClass(Step.class);
        verify(stepRepo, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(s -> s.getRole() == AgentRole.REVIEWER && s.getState() == StepState.PENDING);
    }

    @Test
    void completeStep_testerReportsTestsFailed_spacedJson_alsoDetected() {
        // Both `"tests_passed":false` and `"tests_passed": false` (with space) must be caught
        Step step = stepForRole(AgentRole.TESTER);
        Job job   = jobWithId();
        when(jobRepo.findById(any())).thenReturn(Optional.of(job));
        when(stepRepo.save(any())).thenReturn(step);

        service.completeStep(step, "{\"tests_passed\": false, \"failures\": 1}");

        assertThat(job.getState()).isEqualTo(JobState.PLAN);
        assertThat(job.getConsecutiveTestFailures()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // recoverStalledSteps()
    // ------------------------------------------------------------------

    @Test
    void recoverStalledSteps_withStalledStep_resetsItToPending() {
        Step stalled = stepWithAttempt(0);
        stalled.setState(StepState.RUNNING);
        stalled.setHeartbeatAt(Instant.now().minusSeconds(600));  // 10 min ago

        when(stepRepo.findByStateAndHeartbeatAtBefore(eq(StepState.RUNNING), any()))
                .thenReturn(List.of(stalled));
        when(stepRepo.save(any())).thenReturn(stalled);

        service.recoverStalledSteps();

        // The stalled step should be retried (reset to PENDING)
        assertThat(stalled.getState()).isEqualTo(StepState.PENDING);
    }

    @Test
    void recoverStalledSteps_withNoStalledSteps_doesNothing() {
        when(stepRepo.findByStateAndHeartbeatAtBefore(any(), any()))
                .thenReturn(List.of());

        service.recoverStalledSteps();

        verify(stepRepo, never()).save(any());
        verify(jobRepo,  never()).save(any());
    }

    // ------------------------------------------------------------------
    // Test object factories
    // ------------------------------------------------------------------

    private Job jobWithId() {
        Job job = new Job("https://github.com/org/repo.git", "main");
        // Reflectively set the id since it's normally set by JPA on persist
        try {
            var f = job.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(job, UUID.randomUUID());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return job;
    }

    private Step stepWithAttempt(int attempt) {
        Step step = stepForRole(AgentRole.REPO_MAPPER);
        for (int i = 0; i < attempt; i++) step.incrementAttempt();
        return step;
    }

    private Step stepForRoleWithAttempt(AgentRole role, int attempt) {
        Step step = stepForRole(role);
        step.setStartedAt(Instant.now().minusSeconds(30));  // simulate 30 s elapsed
        for (int i = 0; i < attempt; i++) step.incrementAttempt();
        return step;
    }

    private Step stepForRole(AgentRole role) {
        Job job = jobWithId();
        job.setWorkspaceRef(job.getId().toString());
        Step step = new Step(job, role);
        try {
            var f = step.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(step, UUID.randomUUID());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return step;
    }
}
