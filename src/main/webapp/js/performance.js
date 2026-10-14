(function () {
  const DEFAULT_PARAM_ID = "1";
  const STARTING_CASH = 10000;
  const POLL_MILLIS = 2000;

  const params = new URLSearchParams(window.location.search);
  const meta = document.getElementById("meta");
  const weekSelect = document.getElementById("week");
  const runForm = document.getElementById("run-form");
  const runWeekInput = document.getElementById("run-week");
  const recomputeCheckbox = document.getElementById("recompute");
  const runBtn = document.getElementById("run-btn");
  const backfillForm = document.getElementById("backfill-form");
  const backfillWeeksInput = document.getElementById("backfill-weeks");
  const backfillBtn = document.getElementById("backfill-btn");
  const jobStatus = document.getElementById("job-status");
  const errorEl = document.getElementById("error");
  const summarySection = document.getElementById("summary-section");
  const windowsLine = document.getElementById("windows-line");
  const summaryEl = document.getElementById("summary");
  const chartsSection = document.getElementById("charts");
  const beatCanvas = document.getElementById("beat-chart");
  const returnCanvas = document.getElementById("return-chart");
  const table = document.getElementById("perf-table");
  const tbody = document.getElementById("perf-body");
  const emptyEl = document.getElementById("empty");

  let weeks = [];
  let latestCompleteWeek = null;
  let beatChart = null;
  let returnChart = null;
  let pollTimer = null;

  function apiUrl(path, query) {
    const url = new URL(path, window.location.href);
    Object.entries(query || {}).forEach(([key, value]) => {
      if (value !== null && value !== undefined && value !== "") {
        url.searchParams.set(key, value);
      }
    });
    return url.toString();
  }

  async function fetchJson(url, options) {
    const response = await fetch(url, Object.assign({ headers: { Accept: "application/json" } }, options));
    const text = await response.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch (_) {
      /* non-JSON error body */
    }
    if (!response.ok && response.status !== 409) {
      throw new Error((data && data.error) || text || "HTTP " + response.status);
    }
    return data;
  }

  function postJson(path, body) {
    return fetchJson(apiUrl(path), {
      method: "POST",
      headers: { Accept: "application/json", "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });
  }

  function escapeHtml(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function formatMoney(value) {
    if (value === null || value === undefined || Number.isNaN(Number(value))) {
      return "—";
    }
    return Number(value).toLocaleString("en-US", { style: "currency", currency: "USD" });
  }

  function formatSignedMoney(value) {
    const n = Number(value);
    return (n >= 0 ? "+" : "−") + formatMoney(Math.abs(n));
  }

  function formatPct(value) {
    if (value === null || value === undefined || Number.isNaN(Number(value))) {
      return "—";
    }
    const n = Number(value);
    return (n >= 0 ? "+" : "") + n.toFixed(2) + "%";
  }

  function direction(value) {
    const n = Number(value);
    return n > 0 ? "up" : n < 0 ? "down" : "";
  }

  function showError(message) {
    errorEl.hidden = !message;
    errorEl.textContent = message || "";
  }

  function setBusy(busy) {
    runBtn.disabled = busy;
    backfillBtn.disabled = busy;
  }

  function strategyText(row) {
    return (
      "parts=" + row.parts +
      " · buy≥" + Number(row.minBuyConfidence).toFixed(2) +
      " · sell≥" + Number(row.minSellConfidence).toFixed(2) +
      " · BUY→" + row.onBuy +
      " · SELL→" + row.onSell +
      " · AVOID_HIGH→" + row.onAvoidHigh +
      " · AVOID_LOW→" + row.onAvoidLow
    );
  }

  function backtestLink(row, week) {
    const url = new URL("backtest.html", window.location.href);
    url.searchParams.set("ticker", row.ticker);
    url.searchParams.set("from", week.testFrom);
    url.searchParams.set("to", week.testTo);
    url.searchParams.set("cash", String(STARTING_CASH));
    url.searchParams.set("parts", String(row.parts));
    url.searchParams.set("buyConf", Number(row.minBuyConfidence).toFixed(2));
    url.searchParams.set("sellConf", Number(row.minSellConfidence).toFixed(2));
    url.searchParams.set("onBuy", row.onBuy);
    url.searchParams.set("onSell", row.onSell);
    url.searchParams.set("onAvoidHigh", row.onAvoidHigh);
    url.searchParams.set("onAvoidLow", row.onAvoidLow);
    return url.pathname + url.search;
  }

  function metric(label, value, note, cls) {
    return (
      '<div class="backtest-metric"><span>' + escapeHtml(label) + "</span>" +
      '<strong class="' + (cls || "") + '">' + escapeHtml(value) + "</strong>" +
      (note ? "<small>" + escapeHtml(note) + "</small>" : "") +
      "</div>"
    );
  }

  function renderSummary(week) {
    summarySection.hidden = false;
    const asOf = week.asOfDate !== week.weekEnding ? " (as of " + week.asOfDate + ")" : "";
    const computed = week.computedAt ? new Date(week.computedAt).toLocaleString() : "—";
    const seconds = week.durationMs != null ? ", " + (week.durationMs / 1000).toFixed(1) + " s" : "";
    windowsLine.textContent =
      "Week ending " + week.weekEnding + asOf +
      " · best strategy from " + week.trainFrom + " → " + week.trainTo +
      ", applied to " + week.testFrom + " → " + week.testTo +
      " · computed " + computed + " (" + String(week.trigger || "").toLowerCase() + seconds + ")";

    const beatPct = week.evaluated > 0 ? Math.round((week.beatCount / week.evaluated) * 100) + "% of evaluated" : "";
    const diff = week.strategyTotal - week.buyHoldTotal;
    summaryEl.innerHTML =
      metric("Beat Buy & Hold", String(week.beatCount), beatPct, week.beatCount > 0 ? "up" : "") +
      metric("Did not beat", String(week.notBeatCount), "", week.notBeatCount > 0 ? "down" : "") +
      metric("Skipped", String(week.skipped), "insufficient data") +
      metric("Invested", formatMoney(week.invested), week.evaluated + " × " + formatMoney(STARTING_CASH)) +
      metric("Strategy total", formatMoney(week.strategyTotal), formatPct(week.strategyReturnPct), direction(week.strategyReturnPct)) +
      metric("Buy & Hold total", formatMoney(week.buyHoldTotal), formatPct(week.buyHoldReturnPct), direction(week.buyHoldReturnPct)) +
      metric("Strategy − Buy & Hold", formatSignedMoney(diff), formatPct(week.strategyReturnPct - week.buyHoldReturnPct) + " pts", direction(diff));
  }

  function renderRows(rows, week) {
    if (!rows || rows.length === 0) {
      table.hidden = true;
      emptyEl.hidden = false;
      emptyEl.textContent = "No tickers in this snapshot.";
      return;
    }
    emptyEl.hidden = true;
    table.hidden = false;
    tbody.innerHTML = rows
      .map((row) => {
        if (row.status !== "OK") {
          return (
            "<tr>" +
            "<td>" + escapeHtml(row.ticker) + "</td>" +
            '<td class="skip-reason" colspan="10">' + escapeHtml(row.skipReason || "skipped") + "</td>" +
            "</tr>"
          );
        }
        const diff = row.strategyEquity - row.buyHoldEquity;
        return (
          '<tr class="main-row">' +
          "<td>" + escapeHtml(row.ticker) + "</td>" +
          '<td class="strategy-cell">' + escapeHtml(strategyText(row)) +
          (row.strategyCached ? ' <span class="perf-cached">(saved)</span>' : "") + "</td>" +
          '<td class="' + direction(row.trainReturnPct) + '">' + formatPct(row.trainReturnPct) + "</td>" +
          "<td>" + row.buyCount + " buy / " + row.sellCount + " sell</td>" +
          "<td>" + formatMoney(row.strategyEquity) + "</td>" +
          '<td class="' + direction(row.strategyReturnPct) + '">' + formatPct(row.strategyReturnPct) + "</td>" +
          "<td>" + formatMoney(row.buyHoldEquity) + "</td>" +
          '<td class="' + direction(row.buyHoldReturnPct) + '">' + formatPct(row.buyHoldReturnPct) + "</td>" +
          '<td class="' + direction(diff) + '">' + formatSignedMoney(diff) + "</td>" +
          '<td class="' + (row.beat ? "beat-yes" : "beat-no") + '">' + (row.beat ? "✓" : "✗") + "</td>" +
          '<td><a class="ticker-link" href="' + escapeHtml(backtestLink(row, week)) + '">Trades →</a></td>' +
          "</tr>"
        );
      })
      .join("");
  }

  function chartColors() {
    const styles = getComputedStyle(document.documentElement);
    return {
      muted: styles.getPropertyValue("--muted").trim() || "#8b98a5",
      line: styles.getPropertyValue("--line").trim() || "#2a3542",
      accent: styles.getPropertyValue("--accent").trim() || "#7dd3fc",
      hold: "#e0b44c",
      up: "#22c55e",
      down: "#ef4444"
    };
  }

  function renderCharts() {
    const points = weeks.filter((w) => w.evaluated > 0);
    if (points.length === 0 || typeof Chart === "undefined") {
      chartsSection.hidden = true;
      return;
    }
    chartsSection.hidden = false;
    const c = chartColors();
    const labels = points.map((w) => w.weekEnding);
    const axes = (yTicks, stacked) => ({
      x: { stacked: stacked, ticks: { color: c.muted, maxRotation: 0, maxTicksLimit: 10 }, grid: { display: false } },
      y: { stacked: stacked, ticks: Object.assign({ color: c.muted }, yTicks), grid: { color: c.line } }
    });
    const legend = { labels: { color: c.muted, boxWidth: 12 } };

    if (beatChart) {
      beatChart.destroy();
    }
    beatChart = new Chart(beatCanvas, {
      type: "bar",
      data: {
        labels: labels,
        datasets: [
          { label: "Beat Buy & Hold", data: points.map((w) => w.beatCount), backgroundColor: c.up, maxBarThickness: 48 },
          { label: "Did not beat", data: points.map((w) => w.notBeatCount), backgroundColor: c.down, maxBarThickness: 48 }
        ]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: "index", intersect: false },
        plugins: { legend: legend },
        scales: axes({ precision: 0 }, true)
      }
    });

    if (returnChart) {
      returnChart.destroy();
    }
    returnChart = new Chart(returnCanvas, {
      type: "line",
      data: {
        labels: labels,
        datasets: [
          {
            label: "Strategy",
            data: points.map((w) => w.strategyReturnPct),
            borderColor: c.accent,
            backgroundColor: c.accent,
            tension: 0.2,
            pointRadius: 3
          },
          {
            label: "Buy & Hold",
            data: points.map((w) => w.buyHoldReturnPct),
            borderColor: c.hold,
            backgroundColor: c.hold,
            tension: 0.2,
            pointRadius: 3
          }
        ]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: "index", intersect: false },
        plugins: {
          legend: legend,
          tooltip: {
            callbacks: {
              label: (ctx) => {
                const w = points[ctx.dataIndex];
                const total = ctx.datasetIndex === 0 ? w.strategyTotal : w.buyHoldTotal;
                return ctx.dataset.label + ": " + formatPct(ctx.parsed.y) + " (" + formatMoney(total) + ")";
              }
            }
          }
        },
        scales: axes({ callback: (value) => value + "%" }, false)
      }
    });
  }

  function populateWeekSelect(selected) {
    const ordered = weeks.slice().reverse();
    weekSelect.innerHTML = ordered
      .map((w) => {
        const label = w.weekEnding + (w.asOfDate !== w.weekEnding ? " (as of " + w.asOfDate + ")" : "");
        return '<option value="' + escapeHtml(w.weekEnding) + '">' + escapeHtml(label) + "</option>";
      })
      .join("");
    weekSelect.disabled = ordered.length === 0;
    if (selected && ordered.some((w) => w.weekEnding === selected)) {
      weekSelect.value = selected;
    }
  }

  async function loadWeeks(selected) {
    const data = await fetchJson(apiUrl("api/performance/weeks", { param: DEFAULT_PARAM_ID }));
    weeks = data.weeks || [];
    latestCompleteWeek = data.latestCompleteWeek;
    if (!runWeekInput.value && latestCompleteWeek) {
      runWeekInput.value = latestCompleteWeek;
    }
    populateWeekSelect(selected);
    renderCharts();
  }

  async function loadWeek(weekEnding) {
    if (weeks.length === 0) {
      summarySection.hidden = true;
      table.hidden = true;
      emptyEl.hidden = false;
      emptyEl.textContent = "No weekly snapshot yet.";
      meta.textContent =
        "No weekly snapshots yet. Click \"Run now\" to compute the week ending " + (latestCompleteWeek || "last Friday") + ".";
      return;
    }
    const data = await fetchJson(apiUrl("api/performance", { week: weekEnding, param: DEFAULT_PARAM_ID }));
    if (!data.found) {
      meta.textContent = "No snapshot for the week ending " + weekEnding + ".";
      summarySection.hidden = true;
      table.hidden = true;
      return;
    }
    const week = data.week;
    meta.textContent =
      "Week ending " + week.weekEnding + " · " + week.evaluated + " of " + week.tickersTotal +
      " watch-list stocks evaluated · " + weeks.length + " saved week(s).";
    renderSummary(week);
    renderRows(data.rows || [], week);
    const url = new URL(window.location.href);
    url.searchParams.set("week", week.weekEnding);
    window.history.replaceState(null, "", url.pathname + url.search);
  }

  async function refresh(selected) {
    try {
      showError("");
      await loadWeeks(selected || weekSelect.value || params.get("week"));
      await loadWeek(weekSelect.value || null);
    } catch (err) {
      showError("Failed to load weekly performance: " + err.message);
      meta.textContent = "Weekly performance";
    }
  }

  function describeStatus(s) {
    if (s.running) {
      let text = s.message || "Running…";
      if (s.tickerTotal > 0) {
        text += " · ticker " + s.tickerIndex + " of " + s.tickerTotal + (s.ticker ? " (" + s.ticker + ")" : "");
      }
      return text;
    }
    if (s.error) {
      return "Last run failed: " + s.error;
    }
    return s.message || "";
  }

  async function pollStatus(wasRunning, selectAfter) {
    clearTimeout(pollTimer);
    let s;
    try {
      s = await fetchJson(apiUrl("api/performance/status"));
    } catch (err) {
      jobStatus.hidden = false;
      jobStatus.textContent = "Could not read job status: " + err.message;
      setBusy(false);
      return;
    }
    if (s.running) {
      setBusy(true);
      jobStatus.hidden = false;
      jobStatus.classList.remove("error-text");
      jobStatus.textContent = describeStatus(s);
      pollTimer = setTimeout(() => pollStatus(true, selectAfter), POLL_MILLIS);
      return;
    }
    setBusy(false);
    if (wasRunning) {
      jobStatus.hidden = false;
      jobStatus.classList.toggle("error-text", Boolean(s.error));
      jobStatus.textContent = describeStatus(s);
      await refresh(selectAfter || s.weekEnding);
    }
  }

  async function startJob(path, body, selectAfter) {
    showError("");
    setBusy(true);
    try {
      const data = await postJson(path, body);
      if (!data.started) {
        jobStatus.hidden = false;
        jobStatus.classList.add("error-text");
        jobStatus.textContent = data.message;
        await pollStatus(Boolean(data.status && data.status.running), selectAfter);
        return;
      }
      recomputeCheckbox.checked = false;
      await pollStatus(true, selectAfter);
    } catch (err) {
      setBusy(false);
      showError(err.message);
    }
  }

  weekSelect.addEventListener("change", () => {
    loadWeek(weekSelect.value).catch((err) => showError("Failed to load week: " + err.message));
  });

  runForm.addEventListener("submit", (event) => {
    event.preventDefault();
    startJob(
      "api/performance/run",
      { week: runWeekInput.value || null, recompute: recomputeCheckbox.checked, param: DEFAULT_PARAM_ID },
      null
    );
  });

  backfillForm.addEventListener("submit", (event) => {
    event.preventDefault();
    const n = parseInt(backfillWeeksInput.value, 10);
    if (!(n >= 1 && n <= 104)) {
      showError("Weeks must be between 1 and 104.");
      return;
    }
    startJob(
      "api/performance/backfill",
      { weeks: n, recompute: recomputeCheckbox.checked, param: DEFAULT_PARAM_ID },
      weekSelect.value || null
    );
  });

  refresh(params.get("week")).then(() => pollStatus(false, null));
})();
