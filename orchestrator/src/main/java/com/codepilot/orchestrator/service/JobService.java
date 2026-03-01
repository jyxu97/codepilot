package com.codepilot.orchestrator.service;

import com.codepilot.orchestrator.executor.ExecutorException;
import com.codepilot.orchestrator.executor.WorkspaceClient;
import com.codepilot.orchestrator.model.*;
import com.codepilot.orchestrator.repository.JobRepository;
import com.codepilot.orchestrator.repository.StepRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Core business logic for job and step lifecycle.
 *
 * All public methods that touch the DB are @Transactional so that
 * SELECT FOR UPDATE SKIP LOCKED and the following UPDATE are atomic.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    // Ordered list of agent roles — defines the pipeline sequence.
    private static final List<AgentRole> PIPELINE = List.of(
            AgentRole.REPO_MAPPER,
            AgentRole.PLANNER,
            AgentRole.IMPLEMENTER,
            AgentRole.TESTER,
            AgentRole.REVIEWER,
            AgentRole.FINALIZER
    );

    private final JobRepository     jobRepo;
    private final StepRepository    stepRepo;
    private final WorkspaceClient   workspaceClient;
    private final MeterRegistry     meterRegistry;

    public JobService(JobRepository jobRepo,
                      StepRepository stepRepo,
                      WorkspaceClient workspaceClient,
                      MeterRegistry meterRegistry) {
        this.jobRepo         = jobRepo;
        this.stepRepo        = stepRepo;
        this.workspaceClient = workspaceClient;
        this.meterRegistry   = meterRegistry;
    }

    // ------------------------------------------------------------------
    // Job submission
    // ------------------------------------------------------------------

    /**
     * Create a new repair job and kick off the pipeline.
     *
     * Steps:
     *  1. Save a Job row (state = INIT)
     *  2. Use the job's UUID as the workspace_ref (keeps things simple)
     *  3. Create the workspace on the executor (git clone)
     *  4. Create the first Step: REPO_MAPPER (state = PENDING)
     *  5. Update job state to MAP_REPO — the scheduler will pick it up
     */
    @Transactional
    public Job submit(String repoUrl, String gitRef,
                      String taskDescription, String failingTest) {
        // Step 1-2: persist the job and set workspace_ref = job UUID
        Job job = jobRepo.save(new Job(repoUrl, gitRef));
        job.setWorkspaceRef(job.getId().toString());
        if (taskDescription != null && !taskDescription.isBlank()) {
            job.setTaskDescription(taskDescription);
        }
        if (failingTest != null && !failingTest.isBlank()) {
            job.setFailingTest(failingTest);
        }

        // Step 3: clone the repo into a workspace (may take 30-60 s for large repos)
        try {
            workspaceClient.createWorkspace(job.getWorkspaceRef(), repoUrl, gitRef);
        } catch (ExecutorException e) {
            log.error("Workspace creation failed for job {}", job.getId(), e);
            job.setState(JobState.FAILED);
            meterRegistry.counter("codepilot.jobs.failed", "reason", "workspace_error").increment();
            return jobRepo.save(job);
        }

        // Step 4-5: enqueue the first pipeline step and advance job state
        stepRepo.save(new Step(job, AgentRole.REPO_MAPPER));
        job.setState(JobState.MAP_REPO);
        meterRegistry.counter("codepilot.jobs.submitted").increment();
        return jobRepo.save(job);
    }

    public Optional<Job> findById(UUID id) {
        return jobRepo.findById(id);
    }

    // ------------------------------------------------------------------
    // Step claiming (called by the scheduler in a background thread)
    // ------------------------------------------------------------------

    /**
     * Claim the next PENDING step for execution.
     *
     * Uses SELECT FOR UPDATE SKIP LOCKED so multiple worker threads can
     * call this concurrently without claiming the same step twice.
     *
     * The whole method is @Transactional: the lock is held from the SELECT
     * until the UPDATE (state = RUNNING) commits — then released.
     */
    @Transactional
    public Optional<Step> claimNextStep(String workerId) {
        Optional<Step> opt = stepRepo.claimNextPendingStep();
        opt.ifPresent(step -> {
            step.setState(StepState.RUNNING);
            step.setWorkerId(workerId);
            step.setStartedAt(Instant.now());
            step.setHeartbeatAt(Instant.now());
            stepRepo.save(step);
            // Force the Job proxy to fully load while we still have a Hibernate session.
            // The native SQL query does not JOIN the jobs table, so job is a lazy proxy.
            // Accessing any non-id field here initialises it; without this the worker
            // thread (which has no session) throws LazyInitializationException.
            step.getJob().getWorkspaceRef();
            log.info("Worker '{}' claimed step {} (job={}, role={})",
                    workerId, step.getId(), step.getJob().getId(), step.getRole());
        });
        return opt;
    }

    // ------------------------------------------------------------------
    // Step completion (called by AgentLoop when agent finishes)
    // ------------------------------------------------------------------

    /**
     * Mark a step as DONE and advance the pipeline.
     *
     * For TESTER steps that report tests_passed=false, this method implements
     * the backtracking logic (§4.2):
     *   - First failure:  re-queue a new PLANNER step and return to PLAN state.
     *   - Second failure: backtrack budget exhausted, mark job FAILED.
     * The consecutive_test_failures counter resets whenever TESTER passes.
     *
     * For all other roles, the pipeline advances normally.
     */
    @Transactional
    public void completeStep(Step step, String resultJson) {
        // Guard against ghost threads: after the watchdog recovers a stalled step,
        // the original worker may still be alive and call completeStep after losing
        // ownership. Verify this worker still owns the step before acting.
        if (!ownsStep(step)) return;

        step.setState(StepState.DONE);
        step.setFinishedAt(Instant.now());
        step.setResultJson(resultJson);
        stepRepo.save(step);

        Job job = jobRepo.findById(step.getJob().getId()).orElseThrow();
        recordStepDuration(step, "done");

        // ── Backtracking (§4.2) ──────────────────────────────────────────────
        if (step.getRole() == AgentRole.TESTER && !parseTestsPassed(resultJson)) {
            job.incrementConsecutiveTestFailures();
            if (job.getConsecutiveTestFailures() >= 2) {
                // Two consecutive test failures → give up.
                job.setState(JobState.FAILED);
                jobRepo.save(job);
                log.error("Job {} FAILED: {} consecutive TESTER failures, backtrack budget exhausted.",
                        job.getId(), job.getConsecutiveTestFailures());
                meterRegistry.counter("codepilot.jobs.failed",
                        "reason", "test_budget_exhausted").increment();
                cleanupWorkspace(job);
            } else {
                // First failure → backtrack: restore workspace so PLANNER sees clean code,
                // then re-queue PLANNER with failure context in priorResults.
                restoreSnapshotIfPresent(job);
                job.setIterationCount(job.getIterationCount() + 1);
                job.setState(JobState.PLAN);
                jobRepo.save(job);
                stepRepo.save(new Step(job, AgentRole.PLANNER));
                log.warn("Job {} backtracking to PLAN (iteration={}, consecutiveTestFailures={})",
                        job.getId(), job.getIterationCount(), job.getConsecutiveTestFailures());
                meterRegistry.counter("codepilot.jobs.backtracked").increment();
            }
            return;
        }

        // TESTER passed — reset the failure streak.
        if (step.getRole() == AgentRole.TESTER) {
            job.setConsecutiveTestFailures(0);
        }

        // ── Normal pipeline advance ──────────────────────────────────────────
        AgentRole nextRole = nextRole(step.getRole());

        if (nextRole == null) {
            // All roles finished — job is complete.
            job.setState(JobState.DONE);
            jobRepo.save(job);
            log.info("Job {} DONE", job.getId());
            meterRegistry.counter("codepilot.jobs.completed").increment();
            cleanupWorkspace(job);
        } else {
            // Enqueue the next agent role.
            stepRepo.save(new Step(job, nextRole));
            job.setState(jobStateFor(nextRole));
            jobRepo.save(job);
            log.info("Job {} advancing to {}", job.getId(), job.getState());
        }
    }

    /**
     * Mark a step as FAILED.
     *
     * If the step has been attempted fewer than MAX_ATTEMPTS times,
     * reset it to PENDING for a retry. Otherwise mark the whole job FAILED.
     */
    @Transactional
    public void failStep(Step step, String reason) {
        failStep(step, reason, false);
    }

    /**
     * Mark a step as FAILED, with optional permanent flag.
     *
     * @param permanent if true, skip all remaining retry attempts and fail the
     *                  job immediately. Use this for errors that retrying cannot
     *                  fix (e.g. 401 Unauthorized, 402 Payment Required, 400 Bad Request).
     */
    @Transactional
    public void failStep(Step step, String reason, boolean permanent) {
        // Guard against ghost threads (same rationale as completeStep).
        if (!ownsStep(step)) return;

        final int MAX_ATTEMPTS = 3;
        step.incrementAttempt();
        step.setFinishedAt(Instant.now());
        step.setWorkerId(null);

        boolean shouldRetry = !permanent && step.getAttempt() < MAX_ATTEMPTS;

        if (shouldRetry) {
            step.setState(StepState.PENDING);   // will be claimed again by scheduler
            step.setStartedAt(null);
            step.setFinishedAt(null);
            log.warn("Step {} failed (attempt {}/{}), will retry. Reason: {}",
                    step.getId(), step.getAttempt(), MAX_ATTEMPTS, reason);
        } else {
            recordStepDuration(step, "failed");
            step.setState(StepState.FAILED);
            stepRepo.save(step);

            Job job = jobRepo.findById(step.getJob().getId()).orElseThrow();
            job.setState(JobState.FAILED);
            jobRepo.save(job);
            if (permanent) {
                log.error("Step {} failed with a permanent error — no retry. Job {} → FAILED. Reason: {}",
                        step.getId(), job.getId(), reason);
                meterRegistry.counter("codepilot.jobs.failed", "reason", "permanent_error").increment();
            } else {
                log.error("Step {} permanently failed after {} attempts. Job {} → FAILED.",
                        step.getId(), MAX_ATTEMPTS, job.getId());
                meterRegistry.counter("codepilot.jobs.failed", "reason", "max_retries").increment();
            }
            meterRegistry.counter("codepilot.steps.failed",
                    "role", step.getRole().name()).increment();
            cleanupWorkspace(job);
        }
        stepRepo.save(step);
    }

    /**
     * Persist the snapshot key for a job (§8.4).
     *
     * Called by AgentLoop just before the IMPLEMENTER loop starts.
     * Stored on the Job so that JobService can use it during backtracking.
     */
    @Transactional
    public void saveSnapshotKey(UUID jobId, String snapshotKey) {
        Job job = jobRepo.findById(jobId).orElseThrow();
        job.setSnapshotKey(snapshotKey);
        jobRepo.save(job);
    }

    /**
     * Persist the serialised conversation history for a running step (§5.3).
     *
     * Called by AgentLoop after every agent turn so that a crashed worker
     * can resume from the last saved point instead of restarting the full
     * conversation. The historyJson is a Jackson-serialised List<Message>.
     */
    @Transactional
    public void saveHistory(Step step, String historyJson) {
        step.setConversationHistory(historyJson);
        stepRepo.save(step);
    }

    /**
     * Update the heartbeat timestamp for a running step.
     * Called periodically by the worker thread to prove liveness (§8.2).
     */
    @Transactional
    public void heartbeat(Step step) {
        step.setHeartbeatAt(Instant.now());
        stepRepo.save(step);
    }

    /**
     * Detect and recover steps whose worker has silently crashed (§8.2).
     *
     * A step is considered stalled when it has been RUNNING for more than
     * STALL_TIMEOUT without a heartbeat update. The step is reset to PENDING
     * (or permanently FAILED if it has exhausted its retry attempts) so the
     * scheduler can pick it up again on the next tick.
     *
     * Called by StepScheduler every 60 seconds.
     */
    @Transactional
    public void recoverStalledSteps() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(5));
        List<Step> stalled = stepRepo.findByStateAndHeartbeatAtBefore(StepState.RUNNING, cutoff);
        for (Step step : stalled) {
            log.warn("Recovering stalled step {} (worker={}, last heartbeat={})",
                    step.getId(), step.getWorkerId(), step.getHeartbeatAt());
            failStep(step, "Worker heartbeat timed out after 5 minutes");
        }
    }

    /**
     * Return all steps for a job in creation order.
     * Used by GET /jobs/{id}/steps.
     */
    @Transactional(readOnly = true)
    public List<Step> getSteps(UUID jobId) {
        return stepRepo.findByJobIdOrderByCreatedAtAsc(jobId);
    }

    /**
     * Collect all DONE step results for a job, keyed by role.
     * Used by AgentLoop to build context for subsequent agents.
     *
     * After backtracking there can be multiple DONE steps with the same role
     * (e.g. two PLANNER steps). We keep the latest result per role so agents
     * always see the most recent prior work.
     */
    @Transactional(readOnly = true)
    public Map<AgentRole, String> completedResults(UUID jobId) {
        return stepRepo.findByJobIdOrderByCreatedAtAsc(jobId).stream()
                .filter(s -> s.getState() == StepState.DONE && s.getResultJson() != null)
                .collect(Collectors.toMap(
                        Step::getRole,
                        Step::getResultJson,
                        (existing, replacement) -> replacement   // keep latest per role
                ));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Delete the executor workspace for a job that has reached a terminal state.
     *
     * Called inside @Transactional methods, so we must swallow any executor
     * error here — a failed delete must never roll back the DB transaction.
     * The job state is already committed before this runs; a leftover workspace
     * only costs disk space and can be cleaned up manually if needed.
     */
    private void cleanupWorkspace(Job job) {
        try {
            workspaceClient.deleteWorkspace(job.getWorkspaceRef());
            log.info("Workspace {} deleted for job {}", job.getWorkspaceRef(), job.getId());
        } catch (Exception e) {
            log.warn("Could not delete workspace {} for job {} — manual cleanup may be needed: {}",
                    job.getWorkspaceRef(), job.getId(), e.getMessage());
        }
    }

    /**
     * Restore the workspace to the pre-IMPLEMENTER snapshot, if one exists.
     *
     * Called inside @Transactional methods — executor errors are swallowed
     * so a failed restore never rolls back the DB transaction.  A missed
     * restore means PLANNER sees a partially-modified workspace, which is
     * sub-optimal but recoverable (PLANNER can re-read files).
     */
    private void restoreSnapshotIfPresent(Job job) {
        String key = job.getSnapshotKey();
        if (key == null) return;
        try {
            workspaceClient.restoreWorkspace(job.getWorkspaceRef(), key);
            log.info("Workspace {} restored to snapshot '{}' for job {}",
                    job.getWorkspaceRef(), key, job.getId());
        } catch (Exception e) {
            log.warn("Could not restore snapshot '{}' for job {} — PLANNER will see modified files: {}",
                    key, job.getId(), e.getMessage());
        }
    }

    /**
     * Verify that the calling worker still owns this step in the DB.
     *
     * When the watchdog marks a stalled step for retry, the original worker thread
     * may still be alive. Once another worker claims the step (or it completes), the
     * original thread must not interfere. We detect this by comparing the in-memory
     * workerId (set when the step was originally claimed) against the current DB value.
     *
     * Returns false (and logs a warning) if ownership has been lost; the caller
     * should then silently return without modifying any state.
     */
    private boolean ownsStep(Step step) {
        // Steps created internally (e.g. by recoverStalledSteps) always have a workerId.
        // If workerId is null the step was never claimed — treat it as owned.
        if (step.getWorkerId() == null) return true;

        Step fresh = stepRepo.findById(step.getId()).orElse(null);
        if (fresh == null) {
            log.warn("Step {} no longer exists in DB — ignoring call from worker {}",
                    step.getId(), step.getWorkerId());
            return false;
        }
        if (fresh.getState() != StepState.RUNNING
                || !step.getWorkerId().equals(fresh.getWorkerId())) {
            log.warn("Step {} ownership lost — caller workerId={}, DB workerId={}, DB state={} — ignoring",
                    step.getId(), step.getWorkerId(), fresh.getWorkerId(), fresh.getState());
            return false;
        }
        return true;
    }

    private static AgentRole nextRole(AgentRole current) {
        int idx = PIPELINE.indexOf(current);
        return (idx >= 0 && idx < PIPELINE.size() - 1) ? PIPELINE.get(idx + 1) : null;
    }

    private static JobState jobStateFor(AgentRole role) {
        return switch (role) {
            case REPO_MAPPER  -> JobState.MAP_REPO;
            case PLANNER      -> JobState.PLAN;
            case IMPLEMENTER  -> JobState.IMPLEMENT;
            case TESTER       -> JobState.TEST;
            case REVIEWER     -> JobState.REVIEW;
            case FINALIZER    -> JobState.FINALIZE;
        };
    }

    /**
     * Record how long a step took from start to now, tagged by role and outcome.
     *
     * Metric name: codepilot.steps.duration
     * Tags:        role    = REPO_MAPPER | PLANNER | IMPLEMENTER | TESTER | REVIEWER
     *              outcome = done | failed
     *
     * startedAt is null if the step never actually ran (shouldn't happen in normal
     * flow, but we guard defensively to avoid a NullPointerException).
     */
    private void recordStepDuration(Step step, String outcome) {
        if (step.getStartedAt() == null) return;
        meterRegistry.timer("codepilot.steps.duration",
                        "role",    step.getRole().name(),
                        "outcome", outcome)
                .record(Duration.between(step.getStartedAt(), Instant.now()));
    }

    /**
     * Check whether the TESTER's result JSON reports tests_passed=true.
     *
     * We do a simple substring search rather than full JSON parsing to
     * avoid a Jackson dependency in the service layer. The TESTER prompt
     * defines the exact field name so the format is controlled.
     */
    private static boolean parseTestsPassed(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) return false;
        return resultJson.contains("\"tests_passed\":true")
            || resultJson.contains("\"tests_passed\": true");
    }
}
