(function () {
  const MAIN_COL_COUNT = 11;
  const ACTION_SCORE = { AVOID_HIGH: 2, BUY: 1, HOLD: 0, SELL: -1, AVOID_LOW: -2, AVOID: -2 };

  const params = new URLSearchParams(window.location.search);
  const dateInput = document.getElementById("date");
  const DEFAULT_PARAM_ID = "1";
  const meta = document.getElementById("meta");
  const errorEl = document.getElementById("error");
  const emptyEl = document.getElementById("empty");
  const table = document.getElementById("suggest-table");
  const tbody = document.getElementById("suggest-body");

  /** Local yyyy-MM-dd; before 14:00 use yesterday (suggestions usually not ready yet). */
  function defaultSuggestDate(now = new Date()) {
    const d = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    if (now.getHours() < 14) {
      d.setDate(d.getDate() - 1);
    }
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, "0");
    const day = String(d.getDate()).padStart(2, "0");
    return y + "-" + m + "-" + day;
  }

  const date = params.get("date") || defaultSuggestDate();
  if (dateInput) {
    dateInput.value = date;
  }
  document.title = "StockSugg — " + date;

  /** Resolve prev/next trading day from DB (skips weekends/holidays with no stock rows). */
  function shiftDate(isoDate, deltaDays) {
    const url = new URL("api/suggest/adjacent", window.location.href);
    url.searchParams.set("date", isoDate);
    url.searchParams.set("dir", deltaDays > 0 ? "1" : "-1");
    return fetch(url.toString(), { headers: { Accept: "application/json" } }).then(
      async (response) => {
        const text = await response.text();
        let data = null;
        try {
          data = text ? JSON.parse(text) : null;
        } catch (_) {
          /* non-JSON error body */
        }
        if (!response.ok) {
          const msg = (data && data.error) || text || ("HTTP " + response.status);
          throw new Error(msg);
        }
        if (!data || !data.date) {
          throw new Error(
            deltaDays > 0
              ? "No later trading day found in the database."
              : "No earlier trading day found in the database."
          );
        }
        return data.date;
      }
    );
  }

  function goToDate(isoDate) {
    const url = new URL("suggest.html", window.location.href);
    url.searchParams.set("date", isoDate);
    window.location.href = url.pathname + url.search;
  }

  function navigateAdjacent(deltaDays, button) {
    const from = dateInput.value || date;
    button.disabled = true;
    shiftDate(from, deltaDays)
      .then(goToDate)
      .catch((err) => {
        button.disabled = false;
        errorEl.hidden = false;
        errorEl.textContent = err.message;
      });
  }

  document.getElementById("prev-day").addEventListener("click", (event) => {
    navigateAdjacent(-1, event.currentTarget);
  });
  document.getElementById("next-day").addEventListener("click", (event) => {
    navigateAdjacent(1, event.currentTarget);
  });

  function apiUrl(d) {
    // Resolve against the app context (works under /stocksugg/ on Tomcat).
    const url = new URL("api/suggestSummary/" + encodeURIComponent(d), window.location.href);
    url.searchParams.set("param", DEFAULT_PARAM_ID);
    return url.toString();
  }

  function escapeHtml(value) {
    return String(value)
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;");
  }

  function formatNum(value) {
    if (value == null || Number.isNaN(value)) {
      return "—";
    }
    return Number(value).toLocaleString("en-US", {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    });
  }

  function formatChangeCell(change, changePct) {
    if (change == null || Number.isNaN(change)) {
      return "<td>—</td>";
    }
    const direction = change > 0 ? "up" : change < 0 ? "down" : "flat";
    const sign = change > 0 ? "+" : "";
    const pct =
      changePct == null || Number.isNaN(changePct)
        ? ""
        : " (" + sign + formatNum(changePct) + "%)";
    return (
      '<td class="change ' + direction + '">' +
      '<span class="change-abs">' + sign + formatNum(change) + "</span>" +
      '<span class="change-pct">' + pct + "</span>" +
      "</td>"
    );
  }

  function actionBadge(action) {
    if (!action) {
      return "—";
    }
    const safe = escapeHtml(String(action).toUpperCase());
    return '<span class="action ' + safe + '">' + safe + "</span>";
  }

  /** AVOID split by the API's avoidSide into AVOID_HIGH / AVOID_LOW; other actions unchanged. */
  function actionKey(entry) {
    const action = entry && entry.suggestedAction ? String(entry.suggestedAction).toUpperCase() : "";
    if (action === "AVOID" && (entry.avoidSide === "HIGH" || entry.avoidSide === "LOW")) {
      return "AVOID_" + entry.avoidSide;
    }
    return action;
  }

  /**
   * ▲ / ▼ / – comparing the action on isoDate with the previous trading day, ranked
   * AVOID_HIGH > BUY > HOLD > SELL > AVOID_LOW. Empty when either day is missing or unranked.
   */
  function actionChange(recentActions, isoDate) {
    const entries = recentActions || [];
    const i = entries.findIndex((entry) => entry.date === isoDate);
    if (i <= 0) {
      return "";
    }
    const prevKey = actionKey(entries[i - 1]);
    const today = ACTION_SCORE[actionKey(entries[i])];
    const prev = ACTION_SCORE[prevKey];
    if (today === undefined || prev === undefined) {
      return "";
    }
    const direction = today > prev ? "up" : today < prev ? "down" : "same";
    const sign = direction === "up" ? "▲" : direction === "down" ? "▼" : "–";
    const title = "Previous (" + entries[i - 1].date + "): " + prevKey;
    return (
      '<span class="action-change ' + direction + '" title="' + escapeHtml(title) + '">' +
      sign + "</span>"
    );
  }

  function detailRow(label, value, cssClass) {
    const items = normalizeList(value);
    const body = items.length
      ? '<ul class="detail-list">' +
        items.map((item) => "<li>" + escapeHtml(item) + "</li>").join("") +
        "</ul>"
      : "—";
    return (
      '<tr class="detail-row ' + cssClass + '" hidden>' +
      '<td colspan="' + MAIN_COL_COUNT + '">' +
      '<span class="detail-label">' + escapeHtml(label) + "</span>" +
      '<span class="detail-body">' + body + "</span>" +
      "</td></tr>"
    );
  }

  function normalizeList(value) {
    if (value == null) {
      return [];
    }
    if (Array.isArray(value)) {
      return value.map((item) => String(item).trim()).filter(Boolean);
    }
    const text = String(value).trim();
    if (!text) {
      return [];
    }
    if (text.startsWith("[")) {
      try {
        const parsed = JSON.parse(text);
        if (Array.isArray(parsed)) {
          return parsed.map((item) => String(item).trim()).filter(Boolean);
        }
      } catch (_) {
        /* fall through */
      }
    }
    return text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  }

  function renderRows(rows, isoDate) {
    tbody.innerHTML = "";
    if (!rows || rows.length === 0) {
      table.hidden = true;
      emptyEl.hidden = false;
      return;
    }
    emptyEl.hidden = true;
    table.hidden = false;

    const html = rows.map((row) => {
      const today = (row.recentActions || []).find((entry) => entry.date === isoDate);
      const action = actionKey(today || row);
      return (
        '<tr class="main-row">' +
        '<td class="date-cell">' +
        '<button type="button" class="detail-toggle" aria-expanded="false" ' +
        'aria-label="Show details" title="Show details">' +
        '<span class="detail-toggle-icon" aria-hidden="true">▶</span>' +
        "</button>" +
        "<a class=\"ticker-link\" href=\"history.html?ticker=" +
        encodeURIComponent(row.ticker || "") +
        "\">" +
        escapeHtml(row.ticker || "") +
        "</a></td>" +
        "<td>" + formatNum(row.close) + "</td>" +
        formatChangeCell(row.change, row.changePct) +
        "<td>" + formatNum(row.ma50) + "</td>" +
        "<td>" + formatNum(row.chandeMmt) + "</td>" +
        "<td>" + formatNum(row.chalkinMF) + "</td>" +
        "<td>" + actionBadge(action) + actionChange(row.recentActions, isoDate) + "</td>" +
        "<td>" + formatNum(row.confidence) + "</td>" +
        "<td>" + formatNum(row.suggestedStopPrice) + "</td>" +
        "<td>" + formatNum(row.suggestedEntryPrice) + "</td>" +
        "<td>" + formatNum(row.suggestedProfitPrice) + "</td>" +
        "</tr>" +
        detailRow("thesis", row.thesis, "thesis") +
        detailRow("risks", row.risks, "risks")
      );
    }).join("");
    tbody.innerHTML = html;
    bindDetailToggles();
  }

  function bindDetailToggles() {
    tbody.querySelectorAll(".detail-toggle").forEach((btn) => {
      btn.addEventListener("click", () => {
        const mainRow = btn.closest("tr.main-row");
        if (!mainRow) {
          return;
        }
        const expand = btn.getAttribute("aria-expanded") !== "true";
        btn.setAttribute("aria-expanded", expand ? "true" : "false");
        btn.setAttribute("aria-label", expand ? "Hide details" : "Show details");
        btn.setAttribute("title", expand ? "Hide details" : "Show details");
        const icon = btn.querySelector(".detail-toggle-icon");
        if (icon) {
          icon.textContent = expand ? "▼" : "▶";
        }

        let next = mainRow.nextElementSibling;
        while (next && next.classList.contains("detail-row")) {
          next.hidden = !expand;
          next = next.nextElementSibling;
        }
      });
    });
  }

  function showError(message) {
    errorEl.hidden = false;
    errorEl.textContent = message;
    table.hidden = true;
    emptyEl.hidden = true;
    meta.textContent = "Date: " + date;
  }

  meta.textContent = "Date: " + date + " · loading…";

  fetch(apiUrl(date), { headers: { Accept: "application/json" } })
    .then(async (response) => {
      const text = await response.text();
      let data = null;
      try {
        data = text ? JSON.parse(text) : null;
      } catch (_) {
        /* non-JSON error body */
      }
      if (!response.ok) {
        const msg = (data && data.error) || text || ("HTTP " + response.status);
        throw new Error(msg);
      }
      return data;
    })
    .then((data) => {
      errorEl.hidden = true;
      meta.textContent = "Date: " + data.date + " · " + data.count + " ticker(s)";
      renderRows(data.rows || [], data.date);
    })
    .catch((err) => {
      showError("Failed to load suggestions: " + err.message);
    });
})();
