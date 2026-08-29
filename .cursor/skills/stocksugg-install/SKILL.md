---
name: stocksugg-install
description: >-
  End-to-end StockSugg setup: PostgreSQL, database.properties, Apache Tomcat 10.1,
  Maven build, WAR deploy, health check, and first-run admin/batch config.
  Use when installing StockSugg, onboarding a developer, setting up Tomcat or Postgres,
  or when the user asks to build and deploy from scratch.
---

# StockSugg install

Follow this workflow to install or redeploy StockSugg. Full details: [docs/INSTALL.md](../../../docs/INSTALL.md).

## Agent instructions

1. Read [docs/INSTALL.md](../../../docs/INSTALL.md) if any step is unclear.
2. Run commands from the **repository root**.
3. Execute steps in order; verify each step before continuing.
4. Use deploy scripts when available (prefer over ad-hoc copy).
5. After deploy, **always** hit `/stocksugg/health` and report the result.
6. Do not commit `config/database.properties` or secrets.

## Quick checklist

```
- [ ] Prerequisites: JDK 21+, Maven, Postgres, Tomcat 10.1.x
- [ ] CREATE DATABASE stocksugg
- [ ] config/database.properties (+ ~/.stocksugg/database.properties for Tomcat)
- [ ] mvn -DskipTests package
- [ ] Deploy target/stocksugg.war (scripts/deploy/)
- [ ] Health check OK
- [ ] Admin: GEMINI_API_KEY, TICKERS
- [ ] First batch: mvn compile exec:java "-Dexec.args=--batch"
```

## Database

```powershell
# Windows — from repo root
copy config\database.properties.example config\database.properties
```

```bash
# macOS / Linux
cp config/database.properties.example config/database.properties
```

Edit credentials, then **also** copy to Tomcat-readable path:

- Windows: `%USERPROFILE%\.stocksugg\database.properties`
- Unix: `~/.stocksugg/database.properties`

## Build

```bash
mvn -DskipTests package
```

## Deploy

Set `CATALINA_HOME` / `CATALINA_BASE` to Tomcat 10.1 install path.

**Windows:**
```powershell
$env:CATALINA_HOME = "C:\apache-tomcat-10.1.57"
$env:CATALINA_BASE = $env:CATALINA_HOME
.\scripts\deploy\deploy.ps1 -TomcatHome $env:CATALINA_HOME
```

**Unix:**
```bash
export CATALINA_HOME=/path/to/apache-tomcat-10.1.57
export CATALINA_BASE=$CATALINA_HOME
./scripts/deploy/deploy.sh "$CATALINA_HOME"
```

## Verify

```bash
curl -s http://localhost:7070/stocksugg/health
```

Try port **8080** if **7070** fails (check Tomcat `conf/server.xml`).

## Post-install

1. Admin UI → `GEMINI_API_KEY`, `TICKERS`
2. `mvn compile exec:java "-Dexec.args=--batch"`

## Redeploy only

```bash
mvn -DskipTests package
# then run deploy script again
```

## Troubleshooting

See [reference.md](reference.md).
