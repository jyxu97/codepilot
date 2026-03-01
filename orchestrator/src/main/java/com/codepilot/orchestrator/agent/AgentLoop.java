package com.codepilot.orchestrator.agent;

import com.codepilot.orchestrator.claude.ClaudeClient;
import com.codepilot.orchestrator.claude.ClaudeClient.Message;
import com.codepilot.orchestrator.executor.dto.ExecutionResult;
import com.codepilot.orchestrator.executor.WorkspaceClient;
import com.codepilot.orchestrator.model.AgentRole;
import com.codepilot.orchestrator.model.Step;
import com.codepilot.orchestrator.service.JobService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The CodeAct agent loop (§5.1 + §5.4).
 *
 * For a given Step, this class:
 *   1. Builds a system prompt based on the agent's role
 *   2. Feeds prior agents' results as context
 *   3. Runs a multi-turn conversation with Claude:
 *        → Claude emits a Python code block
 *        → We run it in the sandbox, return the observation
 *        → Claude reads the observation and decides what to do next
 *        → Repeat until Claude writes <result>...</result>
 *   4. Saves the result and advances the job to the next stage
 *
 * This loop is what makes CodePilot an "agent" rather than a
 * simple pipeline: Claude can self-correct based on real execution
 * output (compile errors, test failures, wrong file contents, etc.)
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    // Maximum turns per step to prevent infinite loops.
    // At ~$0.01/turn this caps per-step cost at ~$0.20.
    private static final int MAX_TURNS = 20;

    // How often to update the heartbeat (every N turns).
    private static final int HEARTBEAT_EVERY = 3;

    // Maximum characters returned per code-action observation.
    // Large file reads on big repos (e.g. Apache Commons) can push the
    // conversation history past Claude's 200k-token context limit.
    // At ~4 chars/token, 8 000 chars ≈ 2 000 tokens per observation;
    // over 20 turns that is at most ~40k tokens from observations alone.
    private static final int MAX_OBSERVATION_CHARS = 8_000;

    private final String model;

    private final ClaudeClient    claude;
    private final WorkspaceClient executor;
    private final JobService      jobService;
    private final ObjectMapper    objectMapper;
    private final SystemPrompts   systemPrompts;

    private static final TypeReference<List<Message>> MSG_LIST_TYPE = new TypeReference<>() {};

    public AgentLoop(ClaudeClient claude,
                     WorkspaceClient executor,
                     JobService jobService,
                     ObjectMapper objectMapper,
                     SystemPrompts systemPrompts,
                     @org.springframework.beans.factory.annotation.Value("${codepilot.claude.model}") String model) {
        this.claude        = claude;
        this.executor      = executor;
        this.jobService    = jobService;
        this.objectMapper  = objectMapper;
        this.systemPrompts = systemPrompts;
        this.model         = model;
        log.info("Using Claude model: {}", model);
    }

    // ------------------------------------------------------------------
    // Entry point — called by StepScheduler for each claimed step
    // ------------------------------------------------------------------

    /**
     * Run the full agent loop for one pipeline step.
     *
     * This method blocks until the agent finishes (result found),
     * gives up (max turns), or an error occurs. It is called from
     * a worker thread in the StepScheduler's thread pool.
     */
    public void run(Step step) {
        // Populate MDC so every log line in this worker thread automatically carries
        // these fields — both in plain-text format (dev) and JSON format (prod).
        MDC.put("jobId",   step.getJob().getId().toString());
        MDC.put("stepId",  step.getId().toString());
        MDC.put("role",    step.getRole().name());
        MDC.put("attempt", String.valueOf(step.getAttempt()));
        try {
        log.info("Starting agent loop: job={} role={} attempt={}",
                step.getJob().getId(), step.getRole(), step.getAttempt());

        String workspaceRef = step.getJob().getWorkspaceRef();

        // Collect outputs from all previously completed steps.
        // This gives each agent the full context of what came before it.
        Map<AgentRole, String> priorResults =
                jobService.completedResults(step.getJob().getId());

        // Snapshot / restore before IMPLEMENTER (§8.4).
        // We snapshot the workspace so we can roll back if tests fail later.
        // If this is a retry (attempt > 0) or a second iteration (snapshotKey
        // already set), restore to the clean pre-implementation state first.
        if (step.getRole() == AgentRole.IMPLEMENTER) {
            snapshotBeforeImplementer(step, workspaceRef);
        }

        // Build or restore conversation history (§5.3 — crash recovery).
        // If this step was previously interrupted, resume from the saved history
        // instead of restarting from scratch.
        List<Message> history = loadOrInitHistory(step, priorResults);

        // Multi-turn loop
        int consecutiveTimeouts = 0;
        for (int turn = 1; turn <= MAX_TURNS; turn++) {
            log.debug("Turn {}/{} for step {}", turn, MAX_TURNS, step.getId());

            // --- Call Claude ---
            String response;
            try {
                response = claude.complete(model, history, systemPrompts.get(step.getRole()));
                consecutiveTimeouts = 0;   // reset on any successful response
            } catch (ClaudeClient.ClaudeApiException e) {
                switch (e.errorKind()) {
                    case TRANSIENT_BACKOFF -> {
                        // 429 rate-limit or 529 overloaded — wait and retry the same turn.
                        log.warn("Claude API transient error (status={}, turn={}/{}), backing off 60 s…",
                                e.statusCode(), turn, MAX_TURNS);
                        // Update heartbeat BEFORE sleeping so the watchdog does not
                        // mistake a legitimate rate-limit backoff for a crashed worker.
                        jobService.heartbeat(step);
                        sleepSeconds(60);
                        turn--;
                        continue;
                    }
                    case TRANSIENT_TIMEOUT -> {
                        // Network timeout — allow one retry before giving up.
                        consecutiveTimeouts++;
                        log.warn("Claude API request timed out (consecutive={}, turn={}/{}). {}",
                                consecutiveTimeouts, turn, MAX_TURNS,
                                consecutiveTimeouts >= 2 ? "Failing step." : "Retrying once…");
                        if (consecutiveTimeouts >= 2) {
                            jobService.failStep(step, e.getMessage());
                            return;
                        }
                        jobService.heartbeat(step);  // same rationale as TRANSIENT_BACKOFF
                        turn--;   // don't consume a turn for a single timeout
                        continue;
                    }
                    case PERMANENT -> {
                        // 401/402/403/400 — retrying will never help; fail immediately.
                        log.error("Permanent Claude API error (status={}) — will not retry: {}",
                                e.statusCode(), e.getMessage());
                        jobService.failStep(step, e.getMessage(), true);
                        return;
                    }
                }
                return; // unreachable but keeps the compiler happy
            } catch (Exception e) {
                // Unexpected non-API exception (e.g. serialisation error).
                // Log with full cause chain to make future diagnosis easy.
                log.warn("Unexpected exception calling Claude (turn={}/{}): {} — cause: {}",
                        turn, MAX_TURNS, e.getMessage(),
                        e.getCause() != null ? e.getCause().toString() : "none");
                jobService.failStep(step, "Claude API error: " + e.getMessage()
                        + (e.getCause() != null ? " (caused by: " + e.getCause().getClass().getSimpleName() + ")" : ""));
                return;
            }
            history.add(new Message("assistant", response));

            // --- Check for terminal result ---
            Optional<String> result = ResponseParser.extractResult(response);
            if (result.isPresent()) {
                log.info("Step {} completed after {} turns", step.getId(), turn);
                jobService.completeStep(step, result.get());
                return;
            }

            // --- Check for code action ---
            Optional<String> code = ResponseParser.extractCodeBlock(response);
            String observation;
            if (code.isPresent()) {
                observation = executeCode(workspaceRef, code.get());
            } else {
                // Agent is reasoning without code — nudge it to continue.
                observation = "Continue. Use a code block to take an action, " +
                              "or write <result>...</result> when you are done.";
            }

            history.add(new Message("user", "Observation:\n" + observation));

            // --- Persist history for crash recovery (§5.3) ---
            persistHistory(step, history);

            // --- Heartbeat: tell DB this worker is still alive ---
            if (turn % HEARTBEAT_EVERY == 0) {
                jobService.heartbeat(step);
            }
        }

        // Max turns reached without a <result> tag.
        jobService.failStep(step,
                "Max turns (" + MAX_TURNS + ") reached without producing a <result> tag.");
        } finally {
            // Always clear MDC to prevent context leaking to the next task
            // executed by the same thread-pool worker thread.
            MDC.clear();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Load saved history from the step (crash recovery), or start a fresh history.
     *
     * If the step has a non-null conversationHistory, the worker was interrupted
     * mid-step and we resume from the last persisted turn. Otherwise we start a
     * new conversation with the role-specific initial prompt.
     */
    private List<Message> loadOrInitHistory(Step step, Map<AgentRole, String> priorResults) {
        String saved = step.getConversationHistory();
        if (saved != null && !saved.isBlank()) {
            try {
                List<Message> restored = objectMapper.readValue(saved, MSG_LIST_TYPE);
                // Rough token estimate: ~4 chars per token. If the saved history
                // is already near the 200k-token limit, resuming would immediately
                // fail again — restart from scratch instead.
                long estimatedTokens = saved.length() / 4;
                if (estimatedTokens > 150_000) {
                    log.warn("Step {} saved history is ~{} tokens (too large to resume safely), starting fresh",
                            step.getId(), estimatedTokens);
                } else {
                    log.info("Step {} resuming from saved history ({} messages, ~{} tokens)",
                            step.getId(), restored.size(), estimatedTokens);
                    return restored;
                }
            } catch (Exception e) {
                log.warn("Could not deserialise history for step {}, starting fresh: {}",
                        step.getId(), e.getMessage());
            }
        }
        List<Message> fresh = new ArrayList<>();
        fresh.add(new Message("user", buildInitialPrompt(step, priorResults)));
        return fresh;
    }

    /**
     * Serialise and save the current conversation history to the DB.
     * Failures are logged but never propagated — a missed save is non-fatal
     * (the worst case is that a retry has to redo one extra turn).
     */
    private void persistHistory(Step step, List<Message> history) {
        try {
            jobService.saveHistory(step, objectMapper.writeValueAsString(history));
        } catch (Exception e) {
            log.warn("Could not persist conversation history for step {}: {}",
                    step.getId(), e.getMessage());
        }
    }

    /** Sleep for {@code seconds} seconds, swallowing interrupts cleanly. */
    private static void sleepSeconds(int seconds) {
        try {
            Thread.sleep(seconds * 1_000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Execute a Python code block in the sandbox and return the observation string.
     * The observation is what Claude reads on its next turn.
     */
    private String executeCode(String workspaceRef, String code) {
        try {
            ExecutionResult result = executor.runCode(workspaceRef, code, 300);
            String observation = result.toObservation();
            if (observation.length() > MAX_OBSERVATION_CHARS) {
                observation = observation.substring(0, MAX_OBSERVATION_CHARS)
                        + "\n[... output truncated at " + MAX_OBSERVATION_CHARS
                        + " chars to stay within context limits ...]";
            }
            return observation;
        } catch (Exception e) {
            return "error_type: EXECUTOR_UNREACHABLE\nstderr: " + e.getMessage();
        }
    }

    /**
     * Snapshot the workspace before an IMPLEMENTER run (§8.4).
     *
     * If a snapshot key already exists on the job (retry or second iteration),
     * restore to that snapshot first so IMPLEMENTER always starts from a clean
     * pre-implementation state.  Then take a fresh snapshot and save its key.
     *
     * Failures here are logged but not fatal — the worst outcome is that a
     * retry starts from a partially-modified workspace rather than a clean one.
     */
    private void snapshotBeforeImplementer(Step step, String workspaceRef) {
        String existingKey = step.getJob().getSnapshotKey();
        if (existingKey != null) {
            try {
                executor.restoreWorkspace(workspaceRef, existingKey);
                log.info("Restored workspace to snapshot '{}' before IMPLEMENTER retry (step {}, attempt {})",
                        existingKey, step.getId(), step.getAttempt());
            } catch (Exception e) {
                log.warn("Could not restore snapshot '{}' before IMPLEMENTER — starting from current state: {}",
                        existingKey, e.getMessage());
            }
        }
        try {
            String newKey = executor.snapshotWorkspace(workspaceRef);
            jobService.saveSnapshotKey(step.getJob().getId(), newKey);
            log.info("Snapshot '{}' taken before IMPLEMENTER (step {}, attempt {})",
                    newKey, step.getId(), step.getAttempt());
        } catch (Exception e) {
            log.warn("Could not snapshot workspace before IMPLEMENTER — rollback unavailable: {}",
                    e.getMessage());
        }
    }

    /**
     * Build the first user message for a given pipeline step.
     *
     * This message contains:
     *  - Task context (description + failing test) if provided at submission time
     *  - The outputs of all previously completed agents (for context)
     *  - The role-specific instruction for what to do next
     */
    private String buildInitialPrompt(Step step, Map<AgentRole, String> priorResults) {
        AgentRole role = step.getRole();
        String taskDescription = step.getJob().getTaskDescription();
        String failingTest     = step.getJob().getFailingTest();

        StringBuilder sb = new StringBuilder();
        sb.append("You are starting your task as the ").append(role).append(" agent.\n\n");

        // Inject task context for agents that need it.
        if ((role == AgentRole.REPO_MAPPER || role == AgentRole.PLANNER
                || role == AgentRole.IMPLEMENTER || role == AgentRole.TESTER)
                && (taskDescription != null || failingTest != null)) {
            sb.append("=== TASK CONTEXT ===\n");
            if (taskDescription != null) {
                sb.append("Bug description : ").append(taskDescription).append("\n");
            }
            if (failingTest != null) {
                sb.append("Failing test    : ").append(failingTest).append("\n");
            }
            sb.append("=== END TASK CONTEXT ===\n\n");
        }

        if (!priorResults.isEmpty()) {
            sb.append("=== CONTEXT FROM PREVIOUS AGENTS ===\n");
            priorResults.forEach((r, json) ->
                    sb.append("[ ").append(r).append(" result ]\n").append(json).append("\n\n")
            );
            sb.append("=== END CONTEXT ===\n\n");
        }

        sb.append(switch (role) {
            case REPO_MAPPER ->
                "Explore the repository in the workspace and produce the required JSON summary. " +
                "Focus your analysis on the area described in the task context above.";
            case PLANNER -> {
                // Detect backtrack scenario: a prior TESTER step reported tests_passed=false.
                String testerResult = priorResults.get(AgentRole.TESTER);
                boolean isReplan = testerResult != null &&
                        (testerResult.contains("\"tests_passed\":false") ||
                         testerResult.contains("\"tests_passed\": false"));
                if (isReplan) {
                    yield "The previous implementation FAILED the tests (see TESTER result above). " +
                          "Study the failure details and produce a REVISED repair plan that correctly " +
                          "addresses the root cause.";
                }
                yield "Using the repository map and task context above, analyse the codebase " +
                      "and produce a repair plan targeting the described bug.";
            }
            case IMPLEMENTER -> {
                if (failingTest != null && !failingTest.isBlank()) {
                    yield "Follow the repair plan above. Apply the changes using apply_patch() and verify. " +
                          "IMPORTANT: After applying the fix, run the failing test from the task context " +
                          "to confirm your fix works. If it still fails, revise and retry.";
                }
                yield "Follow the repair plan above. Apply the changes using apply_patch() and verify.";
            }
            case TESTER -> {
                if (failingTest != null && !failingTest.isBlank()) {
                    yield "Run ONLY the specific failing test from the task context using " +
                          "`run_command([\"mvn\", \"-q\", \"test\", \"-Dtest=<test-name>\"])`. " +
                          "Do NOT run the full test suite. Report the result immediately.";
                }
                yield "Run the full test suite with run_command([\"mvn\", \"-q\", \"test\"]) and report results.";
            }
            case REVIEWER ->
                "Review the repair. Run git_diff(\"HEAD\") and assess the changes.";
            case FINALIZER ->
                "All pipeline stages are complete. Summarise the repair run using the prior agent " +
                "results above. Optionally run git_diff(\"HEAD\") to confirm the final patch.";
        });

        return sb.toString();
    }
}
