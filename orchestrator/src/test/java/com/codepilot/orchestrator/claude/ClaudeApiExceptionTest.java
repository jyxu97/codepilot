package com.codepilot.orchestrator.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ClaudeApiException error classification.
 *
 * These are pure-logic tests — no Spring context, no network.
 * They guard against misclassification that would cause AgentLoop
 * to retry a permanent error (wasting API credits) or to give up
 * on a transient one (incorrectly failing a job).
 */
class ClaudeApiExceptionTest {

    // ------------------------------------------------------------------
    // PERMANENT errors — must never retry
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 402, 403})
    void permanentStatuses_classifyAsPermanent(int status) {
        var ex = new ClaudeClient.ClaudeApiException(status, "error body");
        assertThat(ex.errorKind())
                .as("HTTP %d should be PERMANENT", status)
                .isEqualTo(ClaudeClient.ClaudeApiException.ErrorKind.PERMANENT);
    }

    @Test
    void unknownStatus_classifiesAsPermanent() {
        // Unknown 4xx/5xx: fail fast rather than retry blindly.
        var ex = new ClaudeClient.ClaudeApiException(418, "I'm a teapot");
        assertThat(ex.errorKind()).isEqualTo(ClaudeClient.ClaudeApiException.ErrorKind.PERMANENT);
    }

    // ------------------------------------------------------------------
    // TRANSIENT_BACKOFF errors — must back off and retry
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503, 529})
    void transientBackoffStatuses_classifyAsTransientBackoff(int status) {
        var ex = new ClaudeClient.ClaudeApiException(status, "retry later");
        assertThat(ex.errorKind())
                .as("HTTP %d should be TRANSIENT_BACKOFF", status)
                .isEqualTo(ClaudeClient.ClaudeApiException.ErrorKind.TRANSIENT_BACKOFF);
    }

    // ------------------------------------------------------------------
    // TRANSIENT_TIMEOUT — must allow one retry before failing
    // ------------------------------------------------------------------

    @Test
    void timeoutConstructor_classifiesAsTransientTimeout() {
        var ex = new ClaudeClient.ClaudeApiException(
                "Request timed out after 300 s",
                ClaudeClient.ClaudeApiException.ErrorKind.TRANSIENT_TIMEOUT);
        assertThat(ex.errorKind()).isEqualTo(ClaudeClient.ClaudeApiException.ErrorKind.TRANSIENT_TIMEOUT);
        assertThat(ex.statusCode()).isEqualTo(0);   // no HTTP status for network-level errors
    }

    // ------------------------------------------------------------------
    // Message format
    // ------------------------------------------------------------------

    @Test
    void httpErrorMessage_includesStatusAndBody() {
        var ex = new ClaudeClient.ClaudeApiException(402, "credit balance too low");
        assertThat(ex.getMessage()).contains("402").contains("credit balance too low");
    }
}
