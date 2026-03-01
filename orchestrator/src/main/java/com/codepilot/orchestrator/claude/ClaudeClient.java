package com.codepilot.orchestrator.claude;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the Anthropic Messages API.
 *
 * This is the foundation of §5.4 (LLM Integration). Right now it handles a
 * single turn; in Phase 1 we'll extend it to manage multi-turn conversation
 * history for the full agent loop.
 *
 * Why raw HttpClient instead of an SDK?
 *   - No extra dependencies — the Claude API is a straightforward REST endpoint
 *   - You see exactly what's on the wire, which makes debugging the agent loop easier
 *   - We can add retry logic (429/503) exactly as the design doc specifies (§5.4)
 */
@Component
public class ClaudeClient {

    // -------------------------------------------------------------------------
    // Data records
    // -------------------------------------------------------------------------

    /**
     * A single message in a conversation.
     * role must be "user" or "assistant" — Claude's API alternates between them.
     */
    public record Message(String role, String content) {}

    /**
     * The subset of the API response we care about.
     * @JsonIgnoreProperties(ignoreUnknown = true) tells Jackson to silently skip
     * any fields we didn't declare — safe and practical for API responses.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessagesResponse(List<ContentBlock> content) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record ContentBlock(String type, String text) {}

        /** Extracts the text from the first content block (the one we always use). */
        public String firstText() {
            return content.stream()
                    .filter(b -> "text".equals(b.type()))
                    .map(ContentBlock::text)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No text block in response"));
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private static final String API_URL   = "https://api.anthropic.com/v1/messages";
    private static final String API_VER   = "2023-06-01";
    private static final int    MAX_TOKENS = 4096;

    private final HttpClient    http;
    private final ObjectMapper  json;
    private final String        apiKey;

    // -------------------------------------------------------------------------
    // Constructor — Spring injects the API key from the environment variable
    // -------------------------------------------------------------------------

    public ClaudeClient(@Value("${anthropic.api-key}") String apiKey,
                        ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.json   = objectMapper;
        this.http   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Send a conversation to Claude and return the assistant's text reply.
     *
     * This is one "turn" of the agent loop from §5.4:
     *
     *   Agent turn N:
     *     Input:  messages  (the full conversation so far)
     *     Output: the assistant's text (a Python code block, or a <result> tag)
     *
     * @param model    e.g. "claude-sonnet-4-6"
     * @param messages the full conversation history so far (user + assistant turns)
     * @return the assistant's text content
     */
    /** Convenience overload without a system prompt. */
    public String complete(String model, List<Message> messages) {
        return complete(model, messages, null);
    }

    /**
     * Send a conversation to Claude and return the assistant's text reply.
     *
     * @param model      e.g. "claude-sonnet-4-6"
     * @param messages   the full conversation history (alternating user/assistant)
     * @param systemPrompt optional system prompt; pass null to omit
     * @return the assistant's text content
     */
    public String complete(String model, List<Message> messages, String systemPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ClaudeApiException(0,
                "ANTHROPIC_API_KEY is not set. Export it before starting the orchestrator.");
        }
        try {
            // Build request body — include "system" only when provided.
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model",      model);
            body.put("max_tokens", MAX_TOKENS);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                body.put("system", systemPrompt);
            }
            body.put("messages", messages);
            String requestBody = json.writeValueAsString(body);

            // 2. Build the HTTP request with the required headers
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(300))
                    .header("content-type",      "application/json")
                    .header("x-api-key",          apiKey)
                    .header("anthropic-version",  API_VER)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            // 3. Send and get the raw response
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            // 4. Check for API-level errors
            if (response.statusCode() != 200) {
                throw new ClaudeApiException(response.statusCode(), response.body());
            }

            // 5. Parse the JSON and extract the text
            //    Full response shape: { id, type, role, content: [{type, text}], ... }
            MessagesResponse parsed = json.readValue(response.body(), MessagesResponse.class);
            return parsed.firstText();

        } catch (ClaudeApiException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            // Surface timeouts as a typed, classified exception so AgentLoop
            // can decide whether to retry rather than failing the step immediately.
            throw new ClaudeApiException("Request timed out after 300 s: " + e.getMessage(),
                    ClaudeApiException.ErrorKind.TRANSIENT_TIMEOUT);
        } catch (Exception e) {
            throw new RuntimeException("Claude API call failed: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Exception type
    // -------------------------------------------------------------------------

    public static class ClaudeApiException extends RuntimeException {

        /**
         * Classifies how AgentLoop should react to this exception.
         *
         *  PERMANENT        — retrying will never help (bad key, no credits, bad request).
         *                     Fail the step immediately and skip further retry attempts.
         *  TRANSIENT_BACKOFF — server-side overload or rate-limit; back off, then retry.
         *  TRANSIENT_TIMEOUT — HTTP request timed out; may retry once before giving up.
         */
        public enum ErrorKind { PERMANENT, TRANSIENT_BACKOFF, TRANSIENT_TIMEOUT }

        private final int       statusCode;
        private final ErrorKind kind;

        /** Used for real HTTP error responses (non-200). Kind is derived from status. */
        public ClaudeApiException(int statusCode, String body) {
            super("Claude API error %d: %s".formatted(statusCode, body));
            this.statusCode = statusCode;
            this.kind       = classify(statusCode);
        }

        /** Used internally for network-level errors (e.g. timeout) with no HTTP status. */
        ClaudeApiException(String message, ErrorKind kind) {
            super(message);
            this.statusCode = 0;
            this.kind       = kind;
        }

        public int       statusCode() { return statusCode; }
        public ErrorKind errorKind()  { return kind; }

        private static ErrorKind classify(int status) {
            return switch (status) {
                // These will never succeed on retry without human intervention.
                case 400, 401, 402, 403 -> ErrorKind.PERMANENT;
                // Server-side transient: rate-limited or overloaded — back off and retry.
                case 429, 500, 503, 529 -> ErrorKind.TRANSIENT_BACKOFF;
                // Unknown non-200: treat as permanent (safer than infinite retries).
                default                 -> ErrorKind.PERMANENT;
            };
        }
    }
}
