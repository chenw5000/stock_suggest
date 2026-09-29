(function () {
  const CARDS_PER_ROW = 6;
  const TREND_DAYS = 30;
  const ACTION_SCORE = { BUY: 1, HOLD: 0, SELL: -1, AVOID_HIGH: 2, AVOID_LOW: -2, AVOID: -2 };
  const ACTION_LABEL = {
    AVOID_HIGH: "AVOID (overextended, take profit)",
    AVOID_LOW: "AVOID (weak)"
  };
  const CARD_ACTIONS = new Set(["BUY", "HOLD", "SELL", "AVOID", "AVOID_HIGH", "AVOID_LOW"]);
  const TREND_MAX = 2;
  const TREND_MIN = -2;
  const TREND_HEIGHT = 30;

  const params = new URLSearchParams(window.location.search);
  const dateInput = document.getElementById("date");
  const DEFAULT_PARAM_ID = "1";
  const meta = document.getElementById("meta");
  const daySummaryEl = document.getElementById("day-summary");
  const errorEl = document.getElementById("error");
  const emptyEl = document.getElementById("empty");
  const table = document.getElementById("summary-table");
  const tbody = document.getElementById("summary-body");
  const detailLink = document.getElementById("detail-link");

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
  document.title = "StockSugg — summary " + date;
  if (detailLink) {
    const url = new URL("suggest.html", window.location.href);
    url.searchParams.set("date", date);
    detailLink.href = url.pathname + url.search;
  }

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
    const url = new URL("suggest-summary.html", window.location.href);
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

  function formatChange(change, changePct) {
    if (change == null || Number.isNaN(change)) {
      return '<div class="summary-row summary-change flat">—</div>';
    }
    const direction = change > 0 ? "up" : change < 0 ? "down" : "flat";
    const sign = change > 0 ? "+" : "";
    const pct =
      changePct == null || Number.isNaN(changePct)
        ? ""
        : " (" + sign + formatNum(changePct) + "%)";
    return (
      '<div class="summary-row summary-change ' + direction + '">' +
      sign + formatNum(change) + pct +
      "</div>"
    );
  }

  function actionBadge(action, change = "") {
    if (!action) {
      return '<span class="summary-no-action">—</span>';
    }
    const key = String(action).toUpperCase();
    if (key === "AVOID_HIGH" || key === "AVOID_LOW") {
      return (
        '<span class="action ' + key + '" title="' + ACTION_LABEL[key] + '">' +
        "AVOID<small>" + key.slice("AVOID_".length) + "</small>" + change + "</span>"
      );
    }
    const safe = escapeHtml(key);
    return '<span class="action ' + safe + '">' + safe + change + "</span>";
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
    const prevKey = trendKey(entries[i - 1]);
    const today = ACTION_SCORE[trendKey(entries[i])];
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

  /** The row's action on isoDate, with AVOID split into AVOID_HIGH / AVOID_LOW when known. */
  function dayActionKey(row, isoDate) {
    const today = (row.recentActions || []).find((entry) => entry.date === isoDate);
    return trendKey(today || { suggestedAction: row.suggestedAction });
  }

  /** Card tinted with the same color as its action badge (unknown actions keep the panel color). */
  function cardClass(action) {
    const key = action ? String(action).toUpperCase() : "";
    return CARD_ACTIONS.has(key) ? "summary-card card-" + key : "summary-card";
  }

  /** AVOID split by the API's avoidSide into AVOID_HIGH / AVOID_LOW; other actions unchanged. */
  function trendKey(entry) {
    const action = entry.suggestedAction ? String(entry.suggestedAction).toUpperCase() : "";
    if (action === "AVOID" && (entry.avoidSide === "HIGH" || entry.avoidSide === "LOW")) {
      return "AVOID_" + entry.avoidSide;
    }
    return action;
  }

  /**
   * Tiny bar chart of the last TREND_DAYS suggestions (oldest left, newest right),
   * scored AVOID_HIGH=2, BUY=1, HOLD=0, SELL=-1, AVOID_LOW=-2 around a zero baseline.
   */
  function trendChart(recentActions) {
    const entries = (recentActions || []).slice(-TREND_DAYS);
    const offset = TREND_DAYS - entries.length;
    const unit = TREND_HEIGHT / (TREND_MAX - TREND_MIN);
    const baseline = TREND_MAX * unit;
    let bars = "";
    entries.forEach((entry, i) => {
      const key = trendKey(entry);
      const score = ACTION_SCORE[key];
      if (score === undefined) {
        return;
      }
      const height = score === 0 ? 1.5 : Math.abs(score) * unit;
      const y = score > 0 ? baseline - height : score === 0 ? baseline - height / 2 : baseline;
      bars +=
        '<rect class="trend-bar ' + key + '" x="' + (offset + i + 0.15) + '" y="' + y +
        '" width="0.7" height="' + height + '">' +
        "<title>" + escapeHtml(entry.date || "") + ": " + (ACTION_LABEL[key] || key) +
        "</title></rect>";
    });
    return (
      '<div class="summary-trend">' +
      '<svg viewBox="0 0 ' + TREND_DAYS + " " + TREND_HEIGHT + '" preserveAspectRatio="none" ' +
      'role="img" aria-label="Last ' + TREND_DAYS + ' suggestions">' +
      '<line class="trend-baseline" x1="0" y1="' + baseline + '" x2="' + TREND_DAYS +
      '" y2="' + baseline + '"></line>' +
      bars +
      "</svg></div>"
    );
  }

  function renderCard(row) {
    const ticker = row.ticker || "";
    return (
      '<td class="summary-cell">' +
      '<div class="' + cardClass(row.actionKey) + '">' +
      '<div class="summary-info">' +
      '<div class="summary-row summary-ticker">' +
      '<a class="ticker-link" href="history.html?ticker=' +
      encodeURIComponent(ticker) + '">' + escapeHtml(ticker) + "</a>" +
      "</div>" +
      '<div class="summary-row summary-close">' + formatNum(row.close) + "</div>" +
      formatChange(row.change, row.changePct) +
      "</div>" +
      '<div class="summary-suggest">' +
      actionBadge(row.actionKey, row.actionChange) +
      trendChart(row.recentActions) +
      "</div>" +
      "</div>" +
      "</td>"
    );
  }

  function renderRows(rows) {
    tbody.innerHTML = "";
    if (!rows || rows.length === 0) {
      table.hidden = true;
      emptyEl.hidden = false;
      return;
    }
    emptyEl.hidden = true;
    table.hidden = false;

    let html = "";
    for (let i = 0; i < rows.length; i += CARDS_PER_ROW) {
      const chunk = rows.slice(i, i + CARDS_PER_ROW);
      html += "<tr>" + chunk.map(renderCard).join("");
      for (let pad = chunk.length; pad < CARDS_PER_ROW; pad++) {
        html += '<td class="summary-cell"></td>';
      }
      html += "</tr>";
    }
    tbody.innerHTML = html;
  }

  /**
   * One-line count of the day's suggestions. AVOID is split by that day's avoidSide
   * (unclassified AVOID counts as AVOID_LOW, matching the trend chart).
   */
  function daySummary(rows) {
    const counts = { AVOID_HIGH: 0, BUY: 0, HOLD: 0, SELL: 0, AVOID_LOW: 0 };
    let other = 0;
    rows.forEach((row) => {
      let key = row.actionKey;
      if (key === "AVOID") {
        key = "AVOID_LOW";
      }
      if (key in counts) {
        counts[key]++;
      } else {
        other++;
      }
    });
    const parts = ["Total: " + rows.length + " stocks"];
    Object.keys(counts).forEach((key) => parts.push(key + ": " + counts[key]));
    if (other > 0) {
      parts.push("Other: " + other);
    }
    return parts.join(", ");
  }

  function showError(message) {
    errorEl.hidden = false;
    errorEl.textContent = message;
    table.hidden = true;
    emptyEl.hidden = true;
    daySummaryEl.hidden = true;
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
      const rows = data.rows || [];
      rows.forEach((row) => {
        row.actionKey = dayActionKey(row, data.date);
        row.actionChange = actionChange(row.recentActions, data.date);
      });
      daySummaryEl.textContent = daySummary(rows);
      daySummaryEl.hidden = rows.length === 0;
      renderRows(rows);
    })
    .catch((err) => {
      showError("Failed to load suggestions: " + err.message);
    });
})();
