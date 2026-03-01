package com.codepilot.orchestrator.repository;

import com.codepilot.orchestrator.model.Step;
import com.codepilot.orchestrator.model.StepState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StepRepository extends JpaRepository<Step, UUID> {

    /**
     * Claim the oldest PENDING step whose job is still active (not DONE or FAILED).
     *
     * We use a native query (not JPQL) because JPQL cannot express
     * FOR UPDATE SKIP LOCKED — that is PostgreSQL-specific syntax.
     *
     * FOR UPDATE OF s  : lock the step row so other transactions see it as taken
     * SKIP LOCKED      : if already locked by another worker, skip it immediately
     *                    (no blocking, no deadlocks — essential for multi-worker)
     *
     * The JOIN on jobs and the state filter ensure we never pick up steps that
     * belong to a terminal job. This prevents zombie steps (left over from a
     * FAILED job after an orchestrator restart) from consuming worker threads
     * and API credits.
     *
     * Must be called inside a @Transactional method so the lock is held
     * until the caller updates state = RUNNING and commits.
     */
    @Query(value = """
            SELECT s.* FROM steps s
            JOIN jobs j ON j.id = s.job_id
            WHERE s.state = 'PENDING'
              AND j.state NOT IN ('DONE', 'FAILED')
            ORDER BY s.created_at ASC
            LIMIT 1
            FOR UPDATE OF s SKIP LOCKED
            """, nativeQuery = true)
    Optional<Step> claimNextPendingStep();

    /** All steps for a given job, in creation order. */
    List<Step> findByJobIdOrderByCreatedAtAsc(UUID jobId);

    /**
     * Find RUNNING steps whose heartbeat is too old.
     * Used by the scheduler to detect and reset crashed workers (§8.2).
     */
    List<Step> findByStateAndHeartbeatAtBefore(StepState state, Instant cutoff);
}
