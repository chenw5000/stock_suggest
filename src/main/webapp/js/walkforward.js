(function () {
  const DEFAULT_PARAM_ID = "1";
  const POLL_MILLIS = 2000;

  const params = new URLSearchParams(window.location.search);
  const meta = document.getElementById("meta");
  const runSelect = document.getElementById("run-select");
  const runForm = document.getElementById("run-form");
  const cadenceSelect = document.getElementById("cadence");
  const fromInput = document.getElementById("from");
  const toInput = document.getElementById("to");
  const recomputeCheckbox = document.getElementById("recompute");
  const runBtn = document.getElementById("run-btn");
  const jobStatus = document.getElementById("job-status");
  const errorEl = document.getElementById("error");
  const summarySection = document.getElementById("summary-section");
  const spanLine = document.getElementById("span-line");
  const summaryEl = document.getElementById("summary");
  const compareBody = document.getElementById("compare-body");
  const portfolioPanel = document.getElementById("portfolio-panel");
  const portfolioCanvas = document.getElementById("portfolio-chart");
  const detail = document.getElementById("detail");
  const detailTitle = document.getElementById("detail-title");
  const tickerCanvas = document.getElementById("ticker-chart");
  const periodBody = document.getElementById("period-body");
  const table = document.getElementById("ticker-table");
  const tbody = document.getElementById("ticker-body");
  const emptyEl = document.getElementById("empty");

  let runs = [];
  let report = null;
  let selectedTicker = null;
  let portfolioChart = null;
  let tickerChart = null;
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

  function plainPct(value) {
    return value === null || value === undefined ? "—" : Number(value).toFixed(1) + "%";
  }

  function direction(value) {
    const n = Number(value);
    return n > 0 ? "up" : n < 0 ? "down" : "";
  }

  function cadenceText(cadence) {
    return cadence === "WEEKLY" ? "weekly" : "quarterly";
  }

  function showError(message) {
    errorEl.hidden = !message;
    errorEl.textContent = message || "";
  }

  function metric(label, value, note, cls) {
    return (
      '<div class="backtest-metric"><span>' + escapeHtml(label) + "</span>" +
      '<strong class="' + (cls || "") + '">' + escapeHtml(value) + "</strong>" +
      (note ? "<small>" + escapeHtml(note) + "</small>" : "") +
      "</div>"
    );
  }

  function chartColors() {
    const styles = getComputedStyle(document.documentElement);
    return {
      muted: styles.getPropertyValue("--muted").trim() || "#8b98a5",
      line: styles.getPropertyValue("--line").trim() || "#2a3542",
      accent: styles.getPropertyValue("--accent").trim() || "#7dd3fc",
      hold: "#e0b44c",
      fixed: "#a78bfa"
    };
  }

  function lineChart(canvas, existing, labels, series, markers) {
    if (existing) {
      existing.destroy();
    }
    const c = chartColors();
    const colors = { "Buy & Hold": c.hold, "Walk-forward": c.accent, Fixed: c.fixed };
    return new Chart(canvas, {
      type: "line",
      data: {
        labels: labels,
        datasets: series.map((s) => ({
          label: s.label,
          data: s.data,
          borderColor: colors[s.label],
          backgroundColor: colors[s.label],
          borderWidth: s.label === "Walk-forward" ? 2.5 : 1.5,
          tension: 0.1,
          pointRadius: (ctx) => (s.label === "Walk-forward" && markers && markers.has(ctx.dataIndex) ? 3 : 0),
          pointHoverRadius: 3
        }))
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: "index", intersect: false },
        plugins: {
          legend: { labels: { color: c.muted, boxWidth: 12 } },
          tooltip: {
            callbacks: { label: (ctx) => ctx.dataset.label + ": " + formatMoney(ctx.parsed.y) }
          }
        },
        scales: {
          x: { ticks: { color: c.muted, maxRotation: 0, maxTicksLimit: 10 }, grid: { display: false } },
          y: {
            ticks: { color: c.muted, callback: (value) => "$" + Number(value).toLocaleString("en-US") },
            grid: { color: c.line }
          }
        }
      }
    });
  }

  /** Indices of the first trading day of each test period (re-optimization points). */
  function periodStartIndices(dates, periods) {
    const starts = new Set();
    periods.forEach((p) => {
      const index = dates.findIndex((d) => d >= p.testFrom);
      if (index > 0) {
        starts.add(index);
      }
    });
    return starts;
  }

  function renderSummary(summary, r) {
    summarySection.hidden = false;
    const p = r.portfolio;
    const computed = summary.computedAt ? new Date(summary.computedAt).toLocaleString() : "—";
    const seconds = summary.durationMs != null ? ", " + (summary.durationMs / 1000).toFixed(1) + " s" : "";
    spanLine.textContent =
      "Simulated " + r.testFrom + " → " + r.testTo + " · re-optimized " + cadenceText(r.cadence) +
      " (" + r.periods.length + " periods) on the previous " + r.trainMonths + " months" +
      " · first training window " + r.periods[0].trainFrom + " → " + r.periods[0].trainTo +
      " · computed " + computed + seconds;

    if (r.included === 0) {
      summaryEl.innerHTML = metric("Included stocks", "0", "no stock had enough data");
      compareBody.innerHTML = "";
      return;
    }
    const wf = p.walkForwardMetrics;
    const bh = p.buyHoldMetrics;
    const fx = p.fixedMetrics;
    const diff = wf.endingEquity - bh.endingEquity;
    summaryEl.innerHTML =
      metric("Included stocks", r.included + " of " + r.tickersTotal,
        r.tickersTotal - r.included > 0 ? (r.tickersTotal - r.included) + " skipped (insufficient data)" : "") +
      metric("Invested", formatMoney(p.invested), r.included + " × " + formatMoney(r.cashPerTicker)) +
      metric("Walk-forward", formatMoney(wf.endingEquity), formatPct(wf.returnPct), direction(wf.returnPct)) +
      metric("Buy & Hold", formatMoney(bh.endingEquity), formatPct(bh.returnPct), direction(bh.returnPct)) +
      metric("Fixed (untuned)", formatMoney(fx.endingEquity), formatPct(fx.returnPct), direction(fx.returnPct)) +
      metric("Walk-forward − Buy & Hold", formatSignedMoney(diff),
        formatPct(wf.returnPct - bh.returnPct) + " pts", direction(diff)) +
      metric("Walk-forward beats Fixed", p.walkForwardBeatsFixedCount + " of " + r.included, "stocks");

    const rows = [
      ["Buy & Hold", bh, "—"],
      ["Walk-forward (re-optimized)", wf, p.walkForwardBeatCount + " of " + r.included],
      ["Fixed (untuned all-in)", fx, p.fixedBeatCount + " of " + r.included]
    ];
    compareBody.innerHTML = rows
      .map(([label, m, beat]) =>
        "<tr>" +
        '<td class="left">' + escapeHtml(label) + "</td>" +
        "<td>" + formatMoney(m.endingEquity) + "</td>" +
        '<td class="' + direction(m.returnPct) + '">' + formatPct(m.returnPct) + "</td>" +
        '<td class="down">' + (m.maxDrawdownPct > 0 ? "−" : "") + plainPct(m.maxDrawdownPct) + "</td>" +
        "<td>" + plainPct(m.timeInMarketPct) + "</td>" +
        "<td>" + m.trades + "</td>" +
        "<td>" + escapeHtml(beat) + "</td>" +
        "</tr>")
      .join("");
  }

  function renderPortfolioChart(r) {
    const p = r.portfolio;
    if (r.included === 0 || typeof Chart === "undefined") {
      portfolioPanel.hidden = true;
      return;
    }
    portfolioPanel.hidden = false;
    portfolioChart = lineChart(portfolioCanvas, portfolioChart, p.dates, [
      { label: "Walk-forward", data: p.walkForward },
      { label: "Buy & Hold", data: p.buyHold },
      { label: "Fixed", data: p.fixed }
    ], periodStartIndices(p.dates, r.periods));
  }

  function renderTickers(r) {
    const tickers = r.tickers || [];
    if (tickers.length === 0) {
      table.hidden = true;
      emptyEl.hidden = false;
      emptyEl.textContent = "No tickers in this run.";
      return;
    }
    emptyEl.hidden = true;
    table.hidden = false;
    const ordered = tickers.filter((t) => t.status === "OK").concat(tickers.filter((t) => t.status !== "OK"));
    tbody.innerHTML = ordered
      .map((t) => {
        if (t.status !== "OK") {
          return (
            "<tr>" +
            '<td class="left">' + escapeHtml(t.ticker) + "</td>" +
            '<td class="skip-reason" colspan="8">' + escapeHtml(t.skipReason || "skipped") + "</td>" +
            "</tr>"
          );
        }
        const diff = t.walkForward.endingEquity - t.buyHold.endingEquity;
        return (
          '<tr class="wf-row' + (t.ticker === selectedTicker ? " selected" : "") + '" data-ticker="' +
          escapeHtml(t.ticker) + '" title="Show this stock\'s chart and per-period strategies">' +
          '<td class="left">' + escapeHtml(t.ticker) + "</td>" +
          '<td class="' + direction(t.buyHold.returnPct) + '">' + formatPct(t.buyHold.returnPct) + "</td>" +
          '<td class="' + direction(t.walkForward.returnPct) + '">' + formatPct(t.walkForward.returnPct) + "</td>" +
          '<td class="' + direction(t.fixed.returnPct) + '">' + formatPct(t.fixed.returnPct) + "</td>" +
          '<td class="' + direction(diff) + '">' + formatSignedMoney(diff) + "</td>" +
          "<td>" + plainPct(t.walkForward.maxDrawdownPct) + " / " + plainPct(t.buyHold.maxDrawdownPct) + "</td>" +
          "<td>" + plainPct(t.walkForward.timeInMarketPct) + "</td>" +
          "<td>" + t.walkForward.trades + "</td>" +
          '<td class="' + (t.beat ? "beat-yes" : "beat-no") + '">' + (t.beat ? "✓" : "✗") + "</td>" +
          "</tr>"
        );
      })
      .join("");
  }

  function strategyText(p) {
    if (p.parts == null) {
      return "none yet (holding cash)";
    }
    return (
      "parts=" + p.parts +
      " · buy≥" + Number(p.minBuyConfidence).toFixed(2) +
      " · sell≥" + Number(p.minSellConfidence).toFixed(2) +
      " · BUY→" + p.onBuy +
      " · SELL→" + p.onSell +
      " · AVOID_HIGH→" + p.onAvoidHigh +
      " · AVOID_LOW→" + p.onAvoidLow
    );
  }

  function periodStatusNote(p) {
    if (p.status === "KEPT_PREVIOUS") {
      return ' <span class="perf-cached">(kept previous: ' + escapeHtml(p.note || "training data missing") + ")</span>";
    }
    if (p.status === "IDLE") {
      return ' <span class="perf-cached">(' + escapeHtml(p.note || "no strategy") + ")</span>";
    }
    if (p.note) {
      return ' <span class="perf-cached">(' + escapeHtml(p.note) + ")</span>";
    }
    return p.cached ? ' <span class="perf-cached">(saved)</span>' : "";
  }

  function renderDetail(ticker) {
    const t = report && (report.tickers || []).find((x) => x.ticker === ticker && x.status === "OK");
    if (!t) {
      detail.hidden = true;
      selectedTicker = null;
      return;
    }
    selectedTicker = ticker;
    detail.hidden = false;
    detailTitle.textContent =
      t.ticker + " — walk-forward " + formatPct(t.walkForward.returnPct) +
      " vs Buy & Hold " + formatPct(t.buyHold.returnPct) +
      " vs Fixed " + formatPct(t.fixed.returnPct);
    if (typeof Chart !== "undefined") {
      tickerChart = lineChart(tickerCanvas, tickerChart, t.dates, [
        { label: "Walk-forward", data: t.walkForwardEquity },
        { label: "Buy & Hold", data: t.buyHoldEquity },
        { label: "Fixed", data: t.fixedEquity }
      ], periodStartIndices(t.dates, t.periods));
    }
    periodBody.innerHTML = t.periods
      .map((p) => {
        const periodReturn = p.startEquity > 0 ? (p.endEquity / p.startEquity - 1) * 100 : null;
        return (
          "<tr>" +
          '<td class="left">' + escapeHtml(p.testFrom + " → " + p.testTo) + "</td>" +
          '<td class="left">' + escapeHtml(p.trainFrom + " → " + p.trainTo) + "</td>" +
          '<td class="left strategy-cell">' + escapeHtml(strategyText(p)) + periodStatusNote(p) + "</td>" +
          '<td class="' + direction(p.trainReturnPct) + '">' + formatPct(p.trainReturnPct) + "</td>" +
          "<td>" + formatMoney(p.startEquity) + "</td>" +
          "<td>" + formatMoney(p.endEquity) + "</td>" +
          '<td class="' + direction(periodReturn) + '">' + formatPct(periodReturn) + "</td>" +
          "<td>" + p.buys + " buy / " + p.sells + " sell</td>" +
          "</tr>"
        );
      })
      .join("");
    Array.from(tbody.querySelectorAll("tr.wf-row")).forEach((row) => {
      row.classList.toggle("selected", row.dataset.ticker === ticker);
    });
  }

  function populateRunSelect(selectedId) {
    runSelect.innerHTML = runs
      .map((r) => {
        const label =
          cadenceText(r.cadence) + " · " + r.testFrom + " → " + r.testTo +
          " · " + r.included + " stocks · WF " + formatPct(r.walkForwardReturnPct) +
          " / B&H " + formatPct(r.buyHoldReturnPct);
        return '<option value="' + r.id + '">' + escapeHtml(label) + "</option>";
      })
      .join("");
    runSelect.disabled = runs.length === 0;
    if (selectedId && runs.some((r) => String(r.id) === String(selectedId))) {
      runSelect.value = String(selectedId);
    }
  }

  async function loadRuns(selectedId) {
    const data = await fetchJson(apiUrl("api/walkforward/runs", { param: DEFAULT_PARAM_ID }));
    runs = data.runs || [];
    if (!fromInput.value && data.defaultFrom) {
      fromInput.value = data.defaultFrom;
    }
    if (!toInput.value && data.defaultTo) {
      toInput.value = data.defaultTo;
    }
    populateRunSelect(selectedId);
  }

  async function loadRun(id) {
    if (runs.length === 0) {
      summarySection.hidden = true;
      portfolioPanel.hidden = true;
      detail.hidden = true;
      table.hidden = true;
      emptyEl.hidden = false;
      emptyEl.textContent = "No walk-forward run yet.";
      meta.textContent = "No walk-forward runs yet. Pick a span and click \"Run\".";
      return;
    }
    const data = await fetchJson(apiUrl("api/walkforward", { id: id, param: DEFAULT_PARAM_ID }));
    if (!data.found) {
      meta.textContent = "Walk-forward run not found.";
      return;
    }
    report = data.report;
    meta.textContent =
      cadenceText(report.cadence) + " walk-forward · " + report.testFrom + " → " + report.testTo +
      " · " + report.included + " of " + report.tickersTotal + " watch-list stocks · " +
      runs.length + " saved run(s).";
    renderSummary(data.summary, report);
    renderPortfolioChart(report);
    renderTickers(report);
    const keep = (report.tickers || []).some((t) => t.ticker === selectedTicker && t.status === "OK");
    renderDetail(keep ? selectedTicker : null);
    const url = new URL(window.location.href);
    url.searchParams.set("id", data.summary.id);
    window.history.replaceState(null, "", url.pathname + url.search);
  }

  async function refresh(selectedId) {
    try {
      showError("");
      await loadRuns(selectedId || runSelect.value || params.get("id"));
      await loadRun(runSelect.value || null);
    } catch (err) {
      showError("Failed to load walk-forward results: " + err.message);
      meta.textContent = "Walk-forward test";
    }
  }

  function describeStatus(s) {
    if (s.running) {
      let text = s.message || "Running…";
      if (s.tickerTotal > 0 && !(s.message || "").includes(" of ")) {
        text += " · ticker " + s.tickerIndex + " of " + s.tickerTotal;
      }
      return text;
    }
    if (s.error) {
      return "Last run failed: " + s.error;
    }
    if (s.finishedAt && s.startedAt) {
      const seconds = (new Date(s.finishedAt) - new Date(s.startedAt)) / 1000;
      return (s.message || "Done.") + " (" + seconds.toFixed(0) + " s)";
    }
    return s.message || "";
  }

  async function pollStatus(wasRunning) {
    clearTimeout(pollTimer);
    let s;
    try {
      s = await fetchJson(apiUrl("api/walkforward/status"));
    } catch (err) {
      jobStatus.hidden = false;
      jobStatus.textContent = "Could not read job status: " + err.message;
      runBtn.disabled = false;
      return;
    }
    if (s.running) {
      runBtn.disabled = true;
      jobStatus.hidden = false;
      jobStatus.classList.remove("error-text");
      jobStatus.textContent = describeStatus(s);
      pollTimer = setTimeout(() => pollStatus(true), POLL_MILLIS);
      return;
    }
    runBtn.disabled = false;
    if (wasRunning) {
      jobStatus.hidden = false;
      jobStatus.classList.toggle("error-text", Boolean(s.error));
      jobStatus.textContent = describeStatus(s);
      await refresh(s.runId);
    }
  }

  runSelect.addEventListener("change", () => {
    loadRun(runSelect.value).catch((err) => showError("Failed to load run: " + err.message));
  });

  tbody.addEventListener("click", (event) => {
    const row = event.target.closest("tr.wf-row");
    if (!row) {
      return;
    }
    renderDetail(row.dataset.ticker);
    detail.scrollIntoView({ behavior: "smooth", block: "start" });
  });

  runForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    showError("");
    if (fromInput.value && toInput.value && fromInput.value > toInput.value) {
      showError("\"from\" must be on or before \"to\".");
      return;
    }
    runBtn.disabled = true;
    try {
      const data = await fetchJson(apiUrl("api/walkforward/run"), {
        method: "POST",
        headers: { Accept: "application/json", "Content-Type": "application/json" },
        body: JSON.stringify({
          cadence: cadenceSelect.value,
          from: fromInput.value || null,
          to: toInput.value || null,
          recompute: recomputeCheckbox.checked,
          param: DEFAULT_PARAM_ID
        })
      });
      if (!data.started) {
        jobStatus.hidden = false;
        jobStatus.classList.add("error-text");
        jobStatus.textContent = data.message;
        await pollStatus(Boolean(data.status && data.status.running));
        return;
      }
      recomputeCheckbox.checked = false;
      await pollStatus(true);
    } catch (err) {
      runBtn.disabled = false;
      showError(err.message);
    }
  });

  refresh(params.get("id")).then(() => pollStatus(false));
})();
