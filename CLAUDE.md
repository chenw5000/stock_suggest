# StockSugg — Claude Code project instructions

## Install and deploy

When the user asks to **install**, **set up**, **build**, or **deploy** StockSugg from scratch:

1. Read and follow **[docs/INSTALL.md](docs/INSTALL.md)** end to end.
2. Use deploy scripts when possible:
   - Windows: `.\scripts\deploy\deploy.ps1 -TomcatHome $env:CATALINA_HOME`
   - Unix: `./scripts/deploy/deploy.sh "$CATALINA_HOME"`
3. Verify `http://localhost:7070/stocksugg/health` (or port 8080 if configured in Tomcat).
4. Remind user to set admin `GEMINI_API_KEY` and `TICKERS`, then run first batch.

Troubleshooting: [.cursor/skills/stocksugg-install/reference.md](.cursor/skills/stocksugg-install/reference.md)

## Stack

- Java 21, Maven WAR, PostgreSQL, Apache Tomcat 10.1.x
- Context path: `/stocksugg`
- DB config: `config/database.properties` (CLI) and `~/.stocksugg/database.properties` (Tomcat)

## Slash command

Run `/install-stocksugg` for the same install workflow.

## Do not commit

- `config/database.properties` (secrets)
