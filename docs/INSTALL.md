# StockSugg — end-to-end install

Canonical install guide for developers. Used by Cursor (`.cursor/skills/stocksugg-install/`), Claude Code (`CLAUDE.md`), and humans.

## Prerequisites

| Component | Requirement |
|-----------|-------------|
| Java | JDK **21+** (`java -version`) |
| Maven | **3.9+** (`mvn -version`) |
| PostgreSQL | Running server; database user can create tables |
| Tomcat | **Apache Tomcat 10.1.x** (Jakarta Servlet 6) |
| Gemini API key | For suggestions (admin `GEMINI_API_KEY` or env) |

## Install checklist

```
- [ ] 1. Verify prerequisites
- [ ] 2. Create PostgreSQL database
- [ ] 3. Configure database.properties (CLI + Tomcat)
- [ ] 4. Build WAR (mvn -DskipTests package)
- [ ] 5. Deploy WAR to Tomcat
- [ ] 6. Verify /stocksugg/health
- [ ] 7. Set admin GEMINI_API_KEY and TICKERS
- [ ] 8. Run first batch job
```

---

## Step 1: PostgreSQL

Create the database (adjust user/host as needed):

```sql
CREATE DATABASE stocksugg;
```

---

## Step 2: Database configuration

From the **repository root**:

**Windows:**
```powershell
copy config\database.properties.example config\database.properties
```

**macOS / Linux:**
```bash
cp config/database.properties.example config/database.properties
```

Edit `config/database.properties`:

```properties
STOCKSUGG_JDBC_URL=jdbc:postgresql://localhost:5432/stocksugg?socketTimeout=60000&connectTimeout=15000
STOCKSUGG_DB_USER=your-user
STOCKSUGG_DB_PASSWORD=your-password
```

This file is **gitignored** — never commit real passwords.

### Tomcat must see the same config

Tomcat’s working directory is usually **not** the repo root. The app searches (in order):

1. `STOCKSUGG_CONFIG` env var → path to a properties file
2. `config/database.properties` (relative to JVM cwd)
3. `database.properties` (relative to JVM cwd)
4. `~/.stocksugg/database.properties`

**Recommended for Tomcat:** copy the same file to the user home location:

**Windows:** `%USERPROFILE%\.stocksugg\database.properties`  
**Unix:** `~/.stocksugg/database.properties`

Or set environment variables before starting Tomcat:

- `STOCKSUGG_JDBC_URL`
- `STOCKSUGG_DB_USER`
- `STOCKSUGG_DB_PASSWORD`

Tables (`stock`, `admin`, `strategy_optimize`, etc.) are created automatically on first connection.

---

## Step 3: Build

From repository root:

```bash
mvn -DskipTests package
```

Output: `target/stocksugg.war`

---

## Step 4: Deploy to Tomcat

Set Tomcat home (adjust path per machine):

**Windows (PowerShell):**
```powershell
$env:CATALINA_HOME = "C:\apache-tomcat-10.1.57"
$env:CATALINA_BASE = $env:CATALINA_HOME
```

**Unix:**
```bash
export CATALINA_HOME=/opt/apache-tomcat-10.1.57
export CATALINA_BASE=$CATALINA_HOME
```

### Automated deploy (recommended)

**Windows:**
```powershell
.\scripts\deploy\deploy.ps1 -TomcatHome $env:CATALINA_HOME
```

**Unix:**
```bash
./scripts/deploy/deploy.sh "$CATALINA_HOME"
```

### Manual deploy

1. Stop Tomcat (`bin/shutdown.bat` or `bin/shutdown.sh`)
2. Remove old app: `webapps/stocksugg.war`, `webapps/stocksugg/`, `webapps/stocksugg.war.failed`
3. Copy `target/stocksugg.war` → `webapps/stocksugg.war`
4. Start Tomcat (`bin/startup.bat` or `bin/startup.sh`)

If shutdown prints **CATALINA_HOME is not defined**, set `CATALINA_HOME` and `CATALINA_BASE` first.

### HTTP port

Default Tomcat is **8080**. This project often uses **7070** — check `conf/server.xml` for the active Connector port.

Context path: **`/stocksugg`** (WAR name controls this).

---

## Step 5: Verify

```bash
curl -s http://localhost:7070/stocksugg/health
```

Expected JSON includes `"status":"ok"`.

Open in browser:

| Page | URL |
|------|-----|
| Home | http://localhost:7070/stocksugg/ |
| Admin | http://localhost:7070/stocksugg/admin.html |
| Suggestions | http://localhost:7070/stocksugg/suggest.html |
| History | http://localhost:7070/stocksugg/history.html?ticker=AAPL |
| Backtest | http://localhost:7070/stocksugg/backtest.html |

Use port **8080** instead of **7070** if that is what `server.xml` defines.

---

## Step 6: First-run admin setup

1. Open **Admin** (`admin.html`)
2. Set **`GEMINI_API_KEY`** (or use env `GEMINI_API_KEY` / `GOOGLE_API_KEY`)
3. Set **`TICKERS`** — comma-separated symbols, max **100** (e.g. `AAPL,MSFT,NVDA,GOOGL`)

Example API (adjust port):

```bash
curl -X PUT -H "Content-Type: application/json" \
  -d '{"key":"TICKERS","value":"AAPL,MSFT,NVDA"}' \
  http://localhost:7070/stocksugg/api/admin
```

---

## Step 7: Initial data load

From repo root (uses `config/database.properties`):

```bash
mvn compile exec:java "-Dexec.args=--batch"
```

Or click **Run batch job** in the Admin UI (runs on the Tomcat JVM).

This downloads Yahoo OHLCV for watch-list tickers (~2 years for new symbols) and requests Gemini suggestions for the latest session.

---

## Redeploy after code changes

```bash
mvn -DskipTests package
```

Then run the deploy script again (Step 4). Always remove the old exploded `webapps/stocksugg/` directory if the UI looks stale.

---

## Optional: add one ticker without disturbing others

1. Append symbol to admin **`TICKERS`**
2. Run **`--batch`** (incremental Yahoo + latest-day Gemini for watch list)
3. For historical Gemini on that ticker only:

```bash
mvn compile exec:java "-Dexec.args=--backfill --ticker=SYMBOL --from=2026-01-01 --to=2026-08-28 --param-id=1"
```

---

## Troubleshooting

See [reference.md](../.cursor/skills/stocksugg-install/reference.md) for common errors (DB driver, port conflicts, WAR deploy failures).
