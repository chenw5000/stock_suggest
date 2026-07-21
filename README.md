# StockSugg

StockSugg downloads daily OHLCV data from Yahoo Finance, computes technical indicators, stores everything in PostgreSQL, and asks Google Gemini for structured trade suggestions (action, confidence, entry/stop/profit levels, thesis, and risks). A small Tomcat web UI lets you browse suggestions by date, view per-ticker history, and manage admin settings (including the watch list and API key).

This is a research / educational tool, not investment advice.

---

## Prerequisites

| Component | Notes |
|-----------|--------|
| **Java** | JDK **21** or newer (`java -version`, `mvn -version`) |
| **Maven** | 3.9+ recommended |
| **PostgreSQL** | A running server with a database (default name: `stocksugg`) and a user that can create tables |
| **Apache Tomcat** | **10.1.x** (Jakarta Servlet 6). Deploy the built WAR into `webapps/` |
| **Gemini API key** | Required for suggestion generation; store it as admin property `GEMINI_API_KEY` (or set env `GEMINI_API_KEY` / `GOOGLE_API_KEY`) |

Optional for local CLI-only use: you can also run the batch job with Maven `exec:java` without Tomcat; the UI still expects Tomcat (or embedded Javalin via `--embedded` for dev).

---

## Database configuration

1. Create a Postgres database, for example:

```sql
CREATE DATABASE stocksugg;
```

2. Copy the example config and set your password:

```powershell
copy config\database.properties.example config\database.properties
```

Edit `config/database.properties`:

```properties
STOCKSUGG_JDBC_URL=jdbc:postgresql://localhost:5432/stocksugg?socketTimeout=60000&connectTimeout=15000
STOCKSUGG_DB_USER=admin
STOCKSUGG_DB_PASSWORD=your-password
```

`config/database.properties` is gitignored — do not commit secrets.

You can instead set environment variables or JVM `-D` flags: `STOCKSUGG_JDBC_URL`, `STOCKSUGG_DB_USER`, `STOCKSUGG_DB_PASSWORD`. For Tomcat, a convenient option is `~/.stocksugg/database.properties` (same keys), or point `STOCKSUGG_CONFIG` at a properties file Tomcat’s JVM can read.

On first connection the app creates the `stock` and `admin` tables if they do not exist.

---

## How to compile and install

### Build the WAR

From the project root:

```powershell
mvn -DskipTests package
```

Output: `target/stocksugg.war`

### Deploy to Tomcat

1. Ensure Tomcat 10.1 is installed and Postgres is reachable with the config above.
2. Copy the WAR into Tomcat’s `webapps` folder (name controls the context path):

```powershell
Copy-Item -Force target\stocksugg.war C:\path\to\apache-tomcat-10.1.x\webapps\stocksugg.war
```

3. Start Tomcat (or wait for auto-redeploy). Open:

- Home: `http://localhost:8080/stocksugg/` (port may differ; this project often uses **7070**)
- Suggestions: `…/suggest.html`
- History: `…/history.html?ticker=AAPL`
- Admin: `…/admin.html`
- Health: `…/health`

### Run the daily batch (Yahoo refresh + Gemini)

The batch reads the watch list from admin (`TICKERS`), downloads new Yahoo bars (only dates not already stored), then requests Gemini suggestions for the latest session:

```powershell
mvn compile exec:java "-Dexec.args=--batch"
```

From the admin UI you can also click **Run batch job** (starts the same work in the background on the Tomcat JVM).

Other CLI options:

```text
--backfill --ticker=AAPL --from=2026-07-01 --to=2026-07-14
--update-rsi   # recompute rsi14 for every watch-list ticker from stored closes
--embedded     # local Javalin on port 7070 (dev)
```

---

## Updating the stock watch list

The symbols Yahoo downloads and Gemini advises come from the **admin** table key `TICKERS`.

- **Format:** comma-separated tickers, e.g. `AAPL,TSLA,MSFT,NVDA`
- **Limit:** at most **20** symbols
- **Where to edit:** open `admin.html` → edit the **TICKERS** row’s value → **Update**

Example via API:

```powershell
curl.exe -X PUT -H "Content-Type: application/json" `
  -d "{\"key\":\"TICKERS\",\"value\":\"AAPL,MSFT,NVDA,GOOGL\"}" `
  http://localhost:7070/stocksugg/api/admin
```

Also set `GEMINI_API_KEY` in admin (same page) before running batch suggestions.

After changing `TICKERS`, run a batch so new symbols get historical Yahoo data (first time: up to ~2 years) and suggestions.

**Import safety:** refreshing never overwrites existing daily rows. New Yahoo data is inserted only for dates missing in the DB, so prior OHLCV and Gemini fields (thesis, risks, etc.) stay intact.

---

## Technical indicators

After each Yahoo download, StockSugg computes indicators in `TechnicalIndicators` and stores them on every daily row: SMAs, Wilder RSI(14), the Chande Momentum Oscillator, and Chaikin Money Flow. Gemini receives recent bars plus a latest summary (including MA stack and close vs MA50).

### Simple moving averages (SMA)

| Field | Period | Meaning |
|-------|--------|---------|
| `ma5` | 5 days | Very short-term average of close |
| `ma10` | 10 days | Short-term trend |
| `ma20` | 20 days | Near-term trend (~1 month of sessions) |
| `ma50` | 50 days | Intermediate trend |
| `ma200` | 200 days | Long-term trend / “bull vs bear” backdrop |

**How to read them**

- **Price vs MA:** close above a rising MA often supports an uptrend; below a falling MA often supports a downtrend.
- **MA stack:** e.g. `ma5 > ma20 > ma50 > ma200` is a bullish alignment; the reverse is bearish. Crosses (short MA crossing long MA) are classic trend-change signals.
- **Distance from MA50:** large % above/below can mean stretched momentum (continuation or mean-reversion risk, depending on context).

MAs lag price by design: they smooth noise but react slowly to sharp turns.

### Relative Strength Index (`rsi14`, Wilder, period 14)

Wilder's RSI measures the speed and size of recent gains versus losses over 14 sessions, using Wilder smoothing (an exponential-style running average of gains/losses):

\[
\mathrm{RSI} = 100 - \frac{100}{1 + \mathrm{RS}}, \qquad \mathrm{RS} = \frac{\text{avg gain}}{\text{avg loss}}
\]

Range is **0 to 100**. The first 14 rows of a series have no value (`null`) until enough price changes accumulate. Edge cases: a stretch with no losses yields 100, and perfectly flat prices yield 50.

**Inferences**

- **≥ 70:** overbought — strong upside momentum that may be stretched.
- **≤ 30:** oversold — strong downside momentum that may be stretched.
- **~50:** neutral momentum; no clear edge.
- **Divergence:** price making new highs while RSI fails to confirm can warn of fading momentum (and vice versa at lows).

RSI complements CMO (both are momentum oscillators) but uses Wilder smoothing rather than a flat window, so it reacts more smoothly to a single large move.

**Backfilling `rsi14` on existing rows:** RSI was added after the initial schema, and normal `--batch` imports only insert *new* dates, so previously stored rows keep `rsi14 = NULL`. To populate history in place (recompute from stored closes, update only the `rsi14` column, and leave OHLCV/suggestions untouched):

```powershell
mvn compile exec:java "-Dexec.args=--update-rsi"
```

This runs for every ticker in the admin `TICKERS` watch list. The first 14 rows per ticker stay `null` (warm-up).

### Chande Momentum Oscillator (`chandeMmt`, period 14)

Compares the sum of up-day closes to down-day closes over the last 14 sessions:

\[
\mathrm{CMO} = 100 \times \frac{\sum\mathrm{up} - \sum\mathrm{down}}{\sum\mathrm{up} + \sum\mathrm{down}}
\]

Range is roughly **-100 to +100**.

**Inferences**

- **High positive** (e.g. above ~+50): strong recent upside momentum; possible overbought / exhaustion if extreme.
- **High negative** (e.g. below ~−50): strong downside momentum; possible oversold if extreme.
- **Near zero:** balanced up/down movement — weaker directional push.
- Useful as a **momentum confirmation** alongside MA trend (e.g. price above MA50 with rising CMO).

### Chaikin Money Flow (`chaikinMF`, period 20)

Measures buying vs selling pressure using close location in the day’s range, weighted by volume, over 20 days. Values typically sit between about **-1 and +1**.

**Inferences**

- **Positive CMF:** closes tend to be in the upper part of the daily range on meaningful volume → accumulation / buying pressure.
- **Negative CMF:** closes toward the lows → distribution / selling pressure.
- **Divergence:** price making new highs while CMF weakens can warn that upside is not well supported by volume; the opposite for lows.

Together with MAs, RSI, and CMO: trend (MAs) + momentum (RSI + CMO) + volume-backed pressure (CMF) give Gemini a compact technical package for each suggestion horizon (default 10 trading days). Both the `latest` summary and every historical bar sent to Gemini include `rsi14`.

---

## Suggested action (`suggestedAction`)

Each row stores Gemini’s **`suggestedAction`** — a headline label for what to do with a **long** position over the suggestion horizon (~10 trading days). It is not a broker order; read **confidence**, **thesis**, **risks**, and the price columns for the full picture.

| Action | Meaning |
|--------|---------|
| **BUY** | Favorable setup to **enter or add** a long. Bullish trend, momentum, volume, and/or candle patterns with acceptable risk/reward. Entry and profit zones should support a new or larger long. |
| **HOLD** | **No new trade.** Signals are mixed or range-bound; wait for confirmation. If you already hold, no strong reason to add or exit. |
| **SELL** | **Exit or reduce** an existing long. Bearish breakdown, weak momentum, or patterns that favor downside over the horizon. Price zones reflect de-risking, not opening a new long. |
| **AVOID** | **Do not open a new long.** Setup is unclear, choppy, or poor risk/reward — stricter than HOLD for someone considering entry. |
| **NA** | Insufficient data to analyze that ticker/date (rare; only when the model cannot work with the supplied bars). |

**HOLD vs AVOID:** HOLD = “no clear move either way.” AVOID = “don’t start a position here.”

**Note:** The app does not recommend shorts; SELL means get out of (or trim) a long, not “go short.”

---


## Backtesting

StockSugg can simulate a long-only account using stored daily `close` prices plus Gemini `suggestedAction` / `confidence`. Two CLI modes:

| Mode | Flag | Purpose |
|------|------|---------|
| Fixed strategy | `--backtest` | Run one rule set (`all-in` or equal `parts`) |
| Strategy search | `--optimize` | Grid-search parts, confidence floors, and how BUY/SELL/HOLD/AVOID map to trade intents |

Fills are **whole shares at that day's close** (no commissions/slippage). Results are research-only and **in-sample** when you optimize on the same window you evaluate.

### How to prepare the data

1. **Yahoo OHLCV in range** for the ticker (close must exist every trading day you care about).
2. **Gemini suggestions filled** for those days (`suggestedAction`, preferably `confidence` too).

Typical prep:

```powershell
# 1) Ensure the ticker is on the watch list (admin TICKERS), then refresh prices
mvn compile exec:java "-Dexec.args=--batch"

# 2) Backfill Gemini suggestions for the backtest window
mvn compile exec:java "-Dexec.args=--backfill --ticker=QQQ --from=2026-01-01 --to=2026-07-17"
```

Notes:

- `--batch` only downloads **new** dates (does not wipe existing suggestion columns).
- `--backfill` **overwrites** suggestion fields for each day it successfully advises.
- Confirm coverage in the UI (`history.html?ticker=QQQ`) or by checking that optimize/backtest reports many days "with suggestedAction".

### How to run

**Unit tests** (strategy engine):

```powershell
mvn "-Dtest=SuggestionBacktesterTest" test
```

**Fixed backtest — all cash in / all out** (BUY buys full cash when flat; SELL/AVOID sell all; HOLD does nothing):

```powershell
mvn compile exec:java "-Dexec.args=--backtest --ticker=QQQ --from=2026-01-01 --to=2026-07-17 --cash=10000 --strategy=all-in"
```

**Fixed backtest — equal parts** (cash split into N budgets; BUY spends one part; SELL/AVOID sell one FIFO lot):

```powershell
mvn compile exec:java "-Dexec.args=--backtest --ticker=QQQ --from=2026-01-01 --to=2026-07-17 --cash=10000 --strategy=parts --parts=4"
```

**Strategy search** (grid over parts, confidence thresholds, and action-to-intent mappings):

```powershell
mvn compile exec:java "-Dexec.args=--optimize --ticker=TSLA --from=2026-01-01 --to=2026-07-17 --cash=10000 --top=15"
```

### Command arguments

| Argument | Used by | Default | Meaning |
|----------|---------|---------|---------|
| `--backtest` | backtest | — | Run a single strategy |
| `--optimize` | optimize | — | Grid-search strategies |
| `--ticker=SYM` | both | `QQQ` | Symbol (must exist in DB) |
| `--from=yyyy-MM-dd` | both | `2026-01-01` | Inclusive start date |
| `--to=yyyy-MM-dd` | both | `2026-07-17` | Inclusive end date |
| `--cash=10000` | both | `10000` | Starting cash |
| `--strategy=all-in\|parts` | backtest | `all-in` | Position sizing mode |
| `--parts=4` | backtest | `4` | Number of equal cash parts when `strategy=parts` |
| `--top=15` | optimize | `15` | How many best strategies to print |

**`--strategy=all-in` rules**

- **BUY** — if flat, buy as many whole shares as cash allows; if already long, skip
- **SELL** — sell all shares
- **HOLD** — do nothing
- **AVOID** — if long, sell all

**`--strategy=parts` rules**

- Starting cash is split into `--parts` equal budgets (e.g. $10,000 / 4 = $2,500)
- **BUY** — if cash remains, spend one part (one lot)
- **SELL** or **AVOID** — sell the oldest lot (one part)
- **HOLD** — do nothing

**`--optimize` search space** (approximate)

- `parts`: 1, 2, 3, 4, 5, 6, 8, 10
- min buy / sell confidence: 0.00 … 0.80
- BUY → `BUY_PART` or `BUY_ALL`
- SELL / AVOID → `NONE`, `SELL_PART`, or `SELL_ALL`
- HOLD → always `NONE`

### What the results look like

**`--backtest`** prints each fill, then a summary:

```text
Backtest QQQ from 2026-01-01 to 2026-07-17 starting cash $10,000.00 strategy=parts parts=4
Trading days: 135 (with suggestedAction: 135)
Part size: $2,500.00 (4 equal parts)
--- Trades ---
2026-01-06  BUY_PART                    +4 @ 623.42  cash=$7,506.32  shares=4  equity=$10,000.00
2026-01-16  SELL_PART                   -4 @ 621.26  cash=$3,733.82  shares=10 equity=$9,946.42
...
--- Summary ---
Starting cash: $10,000.00
Ending cash:   $10,653.34
Ending shares: 0 @ last close 695.33
Ending equity: $10,653.34
Return:        +6.53%
Buys / sells / skipped buys: 26 / 26 / 23
```

**`--optimize`** prints baselines, the top-N parameter sets, then the best strategy's real (non-skip) trades:

```text
Strategy search TSLA from 2026-01-01 to 2026-07-17 cash $10,000.00 top=15
Trading days: 135
--- Baselines ---
Buy & hold                                equity=$8,740.94  return=-12.59%
All-in (BUY all / SELL·AVOID all)         equity=$8,903.22  return=-10.97%
4 equal parts                             equity=$8,950.96  return=-10.49%
--- Top 15 strategies by ending equity ---
#1  equity=$10,277.96  return=+2.78%  buys=7 sells=4 skippedBuys=1
    parts=4 buyConf>=0.80 sellConf>=0.70 BUY->BUY_PART SELL->SELL_ALL HOLD->NONE AVOID->NONE
...
--- Best strategy trades ---
2026-04-17  BUY_PART               +6 @ 400.62  cash=$7,596.28  shares=6  equity=$10,000.00  conf=0.80
2026-05-12  SELL_ALL              -24 @ 433.45  cash=$10,828.36 shares=0  equity=$10,828.36 conf=0.75
...
```

Treat optimized winners as **hypotheses** to retest on other tickers/dates; they are fitted to the window you searched.

---
## Project layout (quick map)

| Area | Role |
|------|------|
| `App` | CLI: `--batch`, `--backfill`, `--update-rsi`, `--backtest`, `--optimize`, `--embedded` |
| `StockDataImporter` / Yahoo client | Download + indicator enrichment |
| `StockRepository` | Postgres persistence (insert missing dates only) |
| `GeminiStockAdvisor` | Prompt Gemini and write suggestion columns |
| `SuggestionBacktester` / `SuggestionStrategyOptimizer` | Suggestion-driven backtest and parameter search |
| `webapp/` + `StockSuggServlet` | Tomcat UI and JSON APIs |
| `admin` table | `TICKERS`, `GEMINI_API_KEY`, other config |

---

## Disclaimer

Market data and model output can be wrong, delayed, or incomplete. Do your own research before making any trading decision.
