---
description: Install StockSugg end to end (Postgres, Tomcat, build, deploy, verify)
---

Follow the complete StockSugg installation workflow:

1. Read **docs/INSTALL.md** in the repository root.
2. Execute every checklist step in order from the repository root.
3. Use **scripts/deploy/deploy.ps1** (Windows) or **scripts/deploy/deploy.sh** (Unix) for build + Tomcat redeploy when Tomcat path is known.
4. Verify health: `GET /stocksugg/health` on port 7070 (or 8080 per Tomcat server.xml).
5. Guide the user through admin setup: `GEMINI_API_KEY`, `TICKERS`, then first `--batch` run.

If anything fails, read **.cursor/skills/stocksugg-install/reference.md** for troubleshooting.

Do not commit secrets or `config/database.properties`.
