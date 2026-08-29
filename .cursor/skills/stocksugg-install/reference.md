# StockSugg install — troubleshooting reference

## Database connection fails in Tomcat

**Symptom:** WAR deploys but `/health` or pages error; catalina log shows JDBC / password errors.

**Fix:**
- Ensure `~/.stocksugg/database.properties` exists with correct keys, **or**
- Set `STOCKSUGG_JDBC_URL`, `STOCKSUGG_DB_USER`, `STOCKSUGG_DB_PASSWORD` in Tomcat’s environment, **or**
- Set `STOCKSUGG_CONFIG` to an absolute path of a properties file.

CLI (`mvn exec:java`) reads `config/database.properties` from repo root; Tomcat often does not.

## CATALINA_HOME not defined

**Symptom:** `shutdown.bat` / `startup.bat` exits immediately.

**Fix:** Set both before any Tomcat script:

```powershell
$env:CATALINA_HOME = "C:\path\to\apache-tomcat-10.1.57"
$env:CATALINA_BASE = $env:CATALINA_HOME
```

## Port already in use (7070 or 8080)

**Windows:**
```powershell
netstat -ano | findstr :7070
taskkill /PID <pid> /F
```

**Unix:**
```bash
lsof -i :7070
kill <pid>
```

Then restart Tomcat.

## Health 404 or stale UI after deploy

**Fix:**
1. Stop Tomcat
2. Delete `webapps/stocksugg/`, `webapps/stocksugg.war`, `webapps/stocksugg.war.failed`
3. Copy fresh WAR
4. Start Tomcat; wait for explosion (~10–30 s)

## WAR deploy failed (Jetty / Javalin conflict)

StockSugg WAR excludes Javalin/Jetty JARs — Tomcat provides the servlet container. If deploy fails with websocket/Jetty errors, rebuild with current `pom.xml` (`mvn -DskipTests package`).

## Empty suggestions / batch errors

- Set `GEMINI_API_KEY` in admin (or env)
- Set non-empty `TICKERS` in admin
- Run `mvn compile exec:java "-Dexec.args=--batch"`
- New ticker with no rows: batch loads ~2 years Yahoo data on first run

## H2 → PostgreSQL migration (legacy)

Only if migrating old H2 data:

```bash
mvn -q compile exec:java -Dexec.mainClass=com.stocksugg.db.H2ToPostgresMigrator
```

Fresh installs do not need this.

## Config search order (DatabaseConfig)

1. `STOCKSUGG_CONFIG` (env or `-D`)
2. `config/database.properties`
3. `database.properties`
4. `~/.stocksugg/database.properties`

Env overrides for URL/user/password: `STOCKSUGG_JDBC_URL`, `STOCKSUGG_DB_USER`, `STOCKSUGG_DB_PASSWORD`.

## Useful URLs (default port 7070)

| Resource | Path |
|----------|------|
| Health | `/stocksugg/health` |
| Admin API | `/stocksugg/api/admin` |
| Batch status | `/stocksugg/api/batch` |
