package com.stocksugg.stock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Versioned Gemini prompt texts, selected by {@code tuning_params.prompt_version}. A version's text
 * must never change once suggestions were generated with it; add a new version instead.
 */
public final class GeminiPrompts {

    public static final String DEFAULT_VERSION = "v1";

    /** System instruction plus the user-prompt text that precedes the package. */
    public record Prompt(
            String version,
            String label,
            String systemInstruction,
            String analysisInstructions,
            String actionDefinitions) {

        public String userPrompt(int packageCount, String asOfList, String packageJson) {
            return analysisInstructions + actionDefinitions
                    + RESPONSE_CONTRACT.formatted(packageCount, asOfList, packageJson);
        }
    }

    // ----- v1: original production prompt (param 1) -----

    /** Canonical meanings for the action field (long-position framing only). */
    private static final String V1_ACTION_DEFINITIONS = """
            Set action to exactly one of BUY, HOLD, SELL, AVOID, or NA (insufficient data only).
            Use long-position framing only — do not recommend opening a short.
            - BUY: Favorable setup to enter or add a long position. Bullish trend, momentum,
              volume, and/or candlestick structure with acceptable risk/reward over
              horizonTradingDays. entryPriceRange and profitTakingPriceRange should support
              a new or increased long.
            - HOLD: No new trade. Signals are mixed, range-bound, or inconclusive. If already
              long, no strong reason to add or exit; wait for confirmation. Do not use HOLD
              when a clear entry or exit case exists.
            - SELL: Favor exiting or reducing an existing long. Bearish trend, breakdown,
              weakening momentum/volume, or bearish patterns suggest downside risk over
              horizonTradingDays. cutlossPriceRange / profitTakingPriceRange should reflect
              exit or de-risking, not a new long entry.
            - AVOID: Do not initiate a new long. Setup is unclear, choppy, or poor
              risk/reward — worse than HOLD for someone considering entry. Use when
              staying flat (no new position) is the best call even if an existing holder
              might HOLD.
            - NA: Use only when supplied data is insufficient to analyze that package.
            After writing thesis and risks, pick the single best-fitting action label.
            """;

    // NOTE: Extreme condition: HOLD means TRAIL STOP / HOLD.
    // NOTE: Extreme condition: AVOID means TAKE PROFIT / AVOID.
    private static final String V1_SYSTEM_INSTRUCTION = """
            Use only provided OHLCV/indicators. No invented quotes. Return JSON schema.

            If data is insufficient for a package, action=NA and explain for that package.
            Price ranges must be grounded in the supplied prices/levels (entryPriceRange,
            cutlossPriceRange, profitTakingPriceRange). confidence is 0.0-1.0.

            When multiple stock packages are provided, return exactly one suggestion object
            per package in the suggestions array. Each package has ticker + asOf — copy that
            asOf into the suggestion, keep packages independent, and do not use later data.
            When the package includes indicatorWeights, honor them: 0 means ignore that
            indicator; higher weight means give it more influence on thesis and action.
            Weights are relative priorities, not numeric multipliers of prices.

            CRITICAL EXHAUSTION FILTER: If the final rows of the 20-day data payload exhibit 
            extreme terminal oversold parameters (specifically: CMO below -50, RSI below 20, 
            and CMF below -0.20), you must classify this as a "Capitulation Exhaustion Zone." 
            In this state, do not issue a Continuation Downward / Sell recommendation. 
            Instead, categorize the asset as "Awaiting Reversal Confirmation." 
            You are only permitted to issue a BUY suggestion if the final candlestick row 
            demonstrates a positive closing price return backed by above-average daily volume.

            CRITICAL PARABOLIC EXHAUSTION FILTER: You must evaluate if the stock has decoupled 
            from its moving average structure. If the final rows of the 20-day data payload 
            exhibit extreme upper boundary readings (specifically: CMO above +50, RSI above 80, 
            and the Close price is stretched more than 15% above the 20MA), 
            you must classify this as a "Parabolic Blow-off Top Danger Zone." 

            In this state, you are STRICTLY FORBIDDEN from issuing a "Buy" or "Strong Buy" 
            recommendation, regardless of how strong the upward trajectory looks. 
            Instead, your output must prioritize Capital Preservation:
            1. If the current Close price is still above the MA5, issue a "HOLD" 
               stance, advising the user to protect open profits.
            2. If the current Close price has crossed and closed below the MA5, or if a clear 
               Bearish Divergence is visible (Price making higher highs while RSI/CMO make 
               lower highs over the 20-day window), you must issue an "AVOID" 
               stance, signaling a definitive near-term trend breakdown.

            """ + V1_ACTION_DEFINITIONS;

    // The unindented continuation lines make every line keep its full indentation; kept as-is so
    // v1 stays byte-identical to the text param 1 was generated with.
    private static final String V1_ANALYSIS = """
                Analyze this multi-stock market package and return one suggestion per stock package.

                in the response 'thesis', include the following information:
                - INDICATOR ANALYSIS: base this on the passed-in moving averages and indicators,
                  emphasizing those with higher indicatorWeights (0 = ignore that series).
                - TREND CONTEXT: Identify the prevailing short-term and medium-term trend context leading into the final 5 candlesticks.
                - K-LINE RECOGNITION: Scan the most recent 1 to 5 candlesticks. Identify any specific price-action candlestick patterns (e.g., Doji, Hammer, Bullish/Bearish Engulfing, Marubozu, Harami, Piercing Line, or multi-bar reversal/continuation structures).
                - VOLUME VALIDATION: Analyze whether the volume on the pattern bars confirms the move (e.g., expanding volume on breakout/reversal bars, or drying volume on pullbacks).
                - TRADING SUGGESTIONS: Based strictly on the identified K-line setup and structural context, provide an actionable execution table with:
                   - Aggressive Entry Zone
                   - Conservative Entry Zone
                   - Stop Loss Zone
                   - Profit Taking Zone
                   - Time Frame
                   - Potential Opportunities
                   - Potential Risks
                   - Potential Rewards
                - Summarize and provide a final recommendation based on the analysis.
                - EXTREME CONDITION NOTE: If the final rows trigger either the Capitulation Exhaustion Zone \
(CMO < -50, RSI < 20, CMF < -0.20) or the Parabolic Blow-off Top Danger Zone \
(CMO > +50, RSI > 80, Close > 15% above 20MA), explicitly state which extreme condition was detected \
at the start of the thesis, including the actual indicator values that triggered it.

                Based on the thesis and risks, set action using these definitions:
                """;

    private static final String RESPONSE_CONTRACT = """

            You MUST return exactly %d suggestion(s), one for each asOf date: %s.
            Each package has its own ticker and asOf date — treat that asOf as the decision date
            (do not use later information), and return the same asOf in each suggestion.
            Do not omit any asOf date from the list.
            Package:
            %s
            """;

    // ----- shared building blocks for v2+ -----

    private static final String BASE_RULES = """
            Use only provided OHLCV/indicators. No invented quotes. Return JSON schema.

            If data is insufficient for a package, action=NA and explain for that package.
            Price ranges must be grounded in the supplied prices/levels (entryPriceRange,
            cutlossPriceRange, profitTakingPriceRange). confidence is 0.0-1.0.

            When multiple stock packages are provided, return exactly one suggestion object
            per package in the suggestions array. Each package has ticker + asOf — copy that
            asOf into the suggestion, keep packages independent, and do not use later data.
            When the package includes indicatorWeights, honor them: 0 means ignore that
            indicator; higher weight means give it more influence on thesis and action.
            Weights are relative priorities, not numeric multipliers of prices.

            LABEL VOCABULARY: action must be exactly one of BUY, HOLD, SELL, AVOID, NA. Never put
            any other wording (such as "Strong Buy", "Awaiting Reversal", "Trail Stop" or
            "Take Profit") in action; describe such nuances in thesis only.

            """;

    // ----- v2: v1 with the extreme-condition rules mapped strictly onto the five labels -----

    private static final String V2_SYSTEM_INSTRUCTION = BASE_RULES + """
            CAPITULATION FILTER: If the final rows show CMO below -50, RSI below 20 and CMF below
            -0.20 (capitulation exhaustion), never output SELL. Output BUY only if the final bar
            closed higher than the previous close on above-average volume; otherwise output HOLD
            (wait for reversal confirmation).

            PARABOLIC FILTER: If the final rows show CMO above +50, RSI above 80 and Close more
            than 15% above MA20 (parabolic blow-off), never output BUY. Output HOLD while Close
            is still above MA5. Output AVOID if Close closed below MA5, or if price made higher
            highs while RSI/CMO made lower highs over the last 20 bars (bearish divergence).

            """ + V1_ACTION_DEFINITIONS;

    // ----- v3: labels defined by the expected move over the horizon -----

    private static final String V3_ACTION_DEFINITIONS = """
            Set action to exactly one of BUY, HOLD, SELL, AVOID, or NA (insufficient data only).
            Long positions only. Decide from the close-to-close move you expect over the next
            horizonTradingDays trading days:
            - BUY: you expect a gain of at least +3%, and the distance up to profitTakingPriceRange
              is at least twice the distance down to cutlossPriceRange.
            - SELL: you expect a decline of 3% or more; a holder should exit.
            - AVOID: you expect between -3% and +3%, but downside risk clearly outweighs upside;
              someone flat should not enter (a holder may keep).
            - HOLD: you expect between -3% and +3% with balanced risk; no action.
            - NA: use only when supplied data is insufficient to analyze that package.
            confidence is your probability (0.0-1.0) that the move behind the chosen label happens
            within horizonTradingDays.
            """;

    private static final String V3_ANALYSIS = """
            Analyze this multi-stock market package and return one suggestion per stock package.

            In the response 'thesis', include:
            - TREND CONTEXT: short-term and medium-term trend from the moving averages.
            - INDICATORS: the readings that matter most, emphasizing higher indicatorWeights
              (0 = ignore that series).
            - EXPECTED MOVE: your expected percentage move over horizonTradingDays and why.
            - LEVELS: the upside target and downside risk levels, and their reward/risk ratio.

            Then set action using these definitions:
            """;

    // ----- v4: trend-following (stay invested while the trend is up) -----

    private static final String V4_ACTION_DEFINITIONS = """
            Set action to exactly one of BUY, HOLD, SELL, AVOID, or NA (insufficient data only).
            Long positions only, trend-following. The default is to stay invested while the
            trend is up.
            - BUY: Close is above MA50 and MA20 is rising, or price just reclaimed MA20 on
              above-average volume. Enter or add a long.
            - HOLD: The uptrend is intact (Close above MA50) but price is extended or
              consolidating; keep the long, do not add. Also use HOLD when signals are mixed.
            - SELL: The trend broke: Close closed below MA20 on above-average volume, or closed
              below MA50. Exit the long.
            - AVOID: Downtrend (Close below MA50 and MA20 falling); do not enter.
            - NA: use only when supplied data is insufficient to analyze that package.
            Pullbacks that hold above MA50 are not SELL signals. Overbought RSI or CMO alone is
            not a reason for SELL or AVOID.
            """;

    private static final String V4_ANALYSIS = """
            Analyze this multi-stock market package and return one suggestion per stock package.

            In the response 'thesis', include:
            - TREND STRUCTURE: where Close sits relative to MA20, MA50 and MA200, and whether
              MA20 and MA50 are rising or falling.
            - MOMENTUM AND VOLUME: whether momentum indicators and volume confirm the trend,
              emphasizing higher indicatorWeights (0 = ignore that series).
            - TREND BREAK CHECK: whether the latest bars broke MA20 or MA50 on above-average volume.

            Then set action using these definitions:
            """;

    // ----- v5: v1 rules with a short thesis (no candlestick or execution-table narrative) -----

    private static final String V5_ANALYSIS = """
            Analyze this multi-stock market package and return one suggestion per stock package.

            Keep 'thesis' to 3-5 short bullet points: the trend, the indicator readings that
            matter most (emphasizing higher indicatorWeights; 0 = ignore that series), and the
            reason for the action. Do not write candlestick-pattern commentary or an execution
            table.

            Based on the thesis and risks, set action using these definitions:
            """;

    private static final Map<String, Prompt> PROMPTS = new LinkedHashMap<>();

    static {
        register(new Prompt("v1", "original production prompt",
                V1_SYSTEM_INSTRUCTION, V1_ANALYSIS, V1_ACTION_DEFINITIONS));
        register(new Prompt("v2", "v1 with extreme-condition rules mapped to the five labels",
                V2_SYSTEM_INSTRUCTION, V1_ANALYSIS, V1_ACTION_DEFINITIONS));
        register(new Prompt("v3", "labels defined by the expected move over the horizon",
                BASE_RULES + V3_ACTION_DEFINITIONS, V3_ANALYSIS, V3_ACTION_DEFINITIONS));
        register(new Prompt("v4", "trend-following: stay invested while the trend is up",
                BASE_RULES + V4_ACTION_DEFINITIONS, V4_ANALYSIS, V4_ACTION_DEFINITIONS));
        register(new Prompt("v5", "v1 rules with a short thesis",
                V1_SYSTEM_INSTRUCTION, V5_ANALYSIS, V1_ACTION_DEFINITIONS));
    }

    private GeminiPrompts() {}

    private static void register(Prompt prompt) {
        PROMPTS.put(prompt.version(), prompt);
    }

    /** Prompt for {@code version} (case-insensitive); {@code v1} when blank. */
    public static Prompt forVersion(String version) {
        String key = version == null || version.isBlank()
                ? DEFAULT_VERSION
                : version.trim().toLowerCase(Locale.ROOT);
        Prompt prompt = PROMPTS.get(key);
        if (prompt == null) {
            throw new IllegalArgumentException(
                    "Unknown prompt_version '" + version + "'; known: " + PROMPTS.keySet());
        }
        return prompt;
    }

    public static List<Prompt> all() {
        return List.copyOf(PROMPTS.values());
    }
}
