package com.bp.declinemapper.budget;

/**
 * Run-level ceiling on LLM calls and tokens. Counts real LLM round-trips only —
 * cache hits don't tick the call counter (that's the {@link com.bp.declinemapper.stage.LlmMapper}'s
 * contract).
 *
 * <p>On exhaustion the caller should:
 * <ol>
 *     <li>Stop issuing new LLM calls.</li>
 *     <li>Mark any unfinished codes {@code unmapped} with {@code review_reason="budget_exhausted"}.</li>
 *     <li>Still write {@code result.json}.</li>
 *     <li>Exit with code 2.</li>
 * </ol>
 */
public final class BudgetGuard {

    public static final class BudgetExhaustedException extends RuntimeException {
        public BudgetExhaustedException(String message) {
            super(message);
        }
    }

    private final int maxCalls;
    private final int maxTokens;
    private int calls;
    private int tokens;

    public BudgetGuard(int maxCalls, int maxTokens) {
        if (maxCalls < 0) {
            throw new IllegalArgumentException("maxCalls must be >= 0, got " + maxCalls);
        }
        if (maxTokens < 0) {
            throw new IllegalArgumentException("maxTokens must be >= 0, got " + maxTokens);
        }
        this.maxCalls = maxCalls;
        this.maxTokens = maxTokens;
    }

    /** True iff a new LLM call would exceed either ceiling. */
    public boolean isExhausted() {
        return calls >= maxCalls || tokens >= maxTokens;
    }

    /** Record one LLM round-trip. Cache hits MUST NOT call this. */
    public void recordCall(int tokensIn, int tokensOut) {
        calls++;
        tokens += Math.max(0, tokensIn) + Math.max(0, tokensOut);
    }

    public int callCount() {
        return calls;
    }

    public int tokensUsed() {
        return tokens;
    }

    public int maxCalls() {
        return maxCalls;
    }

    public int maxTokens() {
        return maxTokens;
    }
}
