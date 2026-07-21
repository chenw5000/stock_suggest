(function () {
  const PAGE_SIZE = 10;
  const MAIN_COL_COUNT = 11;
  const CHART_MONTHS = 6;

  const params = new URLSearchParams(window.location.search);
  const tickerInput = document.getElementById("ticker");
  const meta = document.getElementById("meta");
  const errorEl = document.getElementById("error");
  const emptyEl = document.getElementById("empty");
  const table = document.getElementById("history-table");
  const tbody = document.getElementById("history-body");
  const pagination = document.getElementById("pagination");
  const suggestLink = document.getElementById("suggest-link");
  const chartWrap = document.getElementById("history-chart-wrap");
  const chartTitle = document.getElementById("history-chart-title");
  const chartCanvas = document.getElementById("history-chart");
  let priceChart = null;

  const ticker = (params.get("ticker") || "").trim().toUpperCase();
  let page = Math.max(1, parseInt(params.get("page") || "1", 10) || 1);

  tickerInput.value = ticker;
  if (ticker) {
    document.title = "StockSugg — " + ticker + " history";
  }

  function apiUrl(symbol, pageNum) {
    const url = new URL(
      "api/history/" + encodeURIComponent(symbol),
      window.location.href
    );
    url.searchParams.set("page", String(pageNum));
    url.searchParams.set("pageSize", String(PAGE_SIZE));
    return url.toString();
  }

  function pageUrl(symbol, pageNum) {
    const url = new URL("history.html", window.location.href);
    url.searchParams.set("ticker", symbol);
    url.searchParams.set("page", String(pageNum));
    return url.pathname + url.search;
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

  function renderRows(rows) {
    tbody.innerHTML = "";
    if (!rows || rows.length === 0) {
      table.hidden = true;
      emptyEl.hidden = false;
      return;
    }
    emptyEl.hidden = true;
    table.hidden = false;

    tbody.innerHTML = rows.map((row) => {
      const action = row.suggestedAction ? String(row.suggestedAction).toUpperCase() : "";
      return (
        '<tr class="main-row">' +
        '<td class="date-cell">' +
        '<button type="button" class="detail-toggle" aria-expanded="false" ' +
        'aria-label="Show details" title="Show details">' +
        '<span class="detail-toggle-icon" aria-hidden="true">▶</span>' +
        "</button>" +
        '<span class="date-text">' + escapeHtml(row.date || "") + "</span>" +
        "</td>" +
        "<td>" + formatNum(row.close) + "</td>" +
        formatChangeCell(row.change, row.changePct) +
        "<td>" + formatNum(row.ma50) + "</td>" +
        "<td>" + formatNum(row.chandeMmt) + "</td>" +
        "<td>" + formatNum(row.chalkinMF) + "</td>" +
        "<td>" + actionBadge(action) + "</td>" +
        "<td>" + formatNum(row.confidence) + "</td>" +
        "<td>" + formatNum(row.suggestedStopPrice) + "</td>" +
        "<td>" + formatNum(row.suggestedEntryPrice) + "</td>" +
        "<td>" + formatNum(row.suggestedProfitPrice) + "</td>" +
        "</tr>" +
        detailRow("thesis", row.thesis, "thesis") +
        detailRow("risks", row.risks, "risks")
      );
    }).join("");

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

  function chartApiUrl(symbol) {
    const url = new URL(
      "api/chart/" + encodeURIComponent(symbol),
      window.location.href
    );
    url.searchParams.set("months", String(CHART_MONTHS));
    return url.toString();
  }

  function renderChart(data) {
    const points = (data && data.points) || [];
    if (points.length === 0 || typeof Chart === "undefined") {
      chartWrap.hidden = true;
      return;
    }
    chartWrap.hidden = false;
    chartTitle.textContent =
      data.ticker + " HLOC — last " + CHART_MONTHS + " months";

    const styles = getComputedStyle(document.documentElement);
    const muted = styles.getPropertyValue("--muted").trim() || "#8b98a5";
    const line = styles.getPropertyValue("--line").trim() || "#2a3542";
    const upColor = "#2eb872";
    const downColor = "#e05252";

    const candleColors = points.map((p) =>
      p.close >= p.open ? upColor : downColor
    );

    const highest = Math.max(...points.map((p) => p.high));
    const lowest = Math.min(...points.map((p) => p.low));

    if (priceChart) {
      priceChart.destroy();
    }
    // Candlesticks from two overlapping floating-bar datasets:
    // a thin high-low wick behind a thick open-close body.
    priceChart = new Chart(chartCanvas, {
      type: "bar",
      data: {
        labels: points.map((p) => p.date),
        datasets: [
          {
            label: "wick",
            data: points.map((p) => [p.low, p.high]),
            backgroundColor: candleColors,
            grouped: false,
            barPercentage: 0.16,
            categoryPercentage: 1.0,
            order: 2
          },
          {
            label: "body",
            data: points.map((p) => [p.open, p.close]),
            backgroundColor: candleColors,
            grouped: false,
            barPercentage: 0.72,
            categoryPercentage: 1.0,
            // Doji days (open == close) would otherwise render as invisible bars.
            minBarLength: 2,
            order: 1
          }
        ]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: "index", intersect: false },
        plugins: {
          legend: { display: false },
          tooltip: {
            filter: (item) => item.datasetIndex === 1,
            callbacks: {
              label: (ctx) => {
                const p = points[ctx.dataIndex];
                return [
                  "Open:  " + formatNum(p.open),
                  "High:  " + formatNum(p.high),
                  "Low:   " + formatNum(p.low),
                  "Close: " + formatNum(p.close)
                ];
              }
            }
          }
        },
        scales: {
          x: {
            ticks: {
              color: muted,
              maxTicksLimit: 8,
              maxRotation: 0
            },
            grid: { display: false }
          },
          y: {
            min: lowest * 0.9,
            max: highest * 1.1,
            ticks: {
              color: muted,
              callback: (value) => formatNum(value)
            },
            grid: { color: line }
          }
        }
      }
    });
  }

  function loadChart(symbol) {
    fetch(chartApiUrl(symbol), { headers: { Accept: "application/json" } })
      .then((response) => (response.ok ? response.json() : null))
      .then((data) => renderChart(data))
      .catch(() => {
        chartWrap.hidden = true;
      });
  }

  function renderPagination(data) {
    const totalPages = data.totalPages || 0;
    const current = data.page || 1;
    if (totalPages <= 1) {
      pagination.hidden = true;
      pagination.innerHTML = "";
      return;
    }
    pagination.hidden = false;

    const prevDisabled = current <= 1 ? " disabled" : "";
    const nextDisabled = current >= totalPages ? " disabled" : "";
    const prevHref = current <= 1 ? "#" : pageUrl(data.ticker, current - 1);
    const nextHref = current >= totalPages ? "#" : pageUrl(data.ticker, current + 1);

    let pagesHtml = "";
    const windowSize = 5;
    let start = Math.max(1, current - Math.floor(windowSize / 2));
    let end = Math.min(totalPages, start + windowSize - 1);
    start = Math.max(1, end - windowSize + 1);

    for (let p = start; p <= end; p++) {
      if (p === current) {
        pagesHtml += '<span class="page-current" aria-current="page">' + p + "</span>";
      } else {
        pagesHtml +=
          '<a class="page-link" href="' + escapeHtml(pageUrl(data.ticker, p)) + '">' + p + "</a>";
      }
    }

    pagination.innerHTML =
      '<a class="page-link"' + prevDisabled + ' href="' + escapeHtml(prevHref) + '">Prev</a>' +
      '<span class="page-status">Page ' + current + " of " + totalPages + "</span>" +
      pagesHtml +
      '<a class="page-link"' + nextDisabled + ' href="' + escapeHtml(nextHref) + '">Next</a>';
  }

  function showError(message) {
    errorEl.hidden = false;
    errorEl.textContent = message;
    table.hidden = true;
    emptyEl.hidden = true;
    pagination.hidden = true;
  }

  if (!ticker) {
    meta.textContent = "Enter a ticker to view recent history.";
    emptyEl.hidden = false;
    emptyEl.textContent = "Choose a ticker above to load history.";
    return;
  }

  meta.textContent = ticker + " · loading…";

  loadChart(ticker);

  fetch(apiUrl(ticker, page), { headers: { Accept: "application/json" } })
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
      const from = data.totalCount === 0 ? 0 : (data.page - 1) * data.pageSize + 1;
      const to = Math.min(data.page * data.pageSize, data.totalCount);
      meta.textContent =
        data.ticker +
        " · " +
        data.totalCount +
        " day(s)" +
        (data.totalCount === 0 ? "" : " · showing " + from + "–" + to);
      suggestLink.href = "suggest.html";
      renderRows(data.rows || []);
      renderPagination(data);
    })
    .catch((err) => {
      showError("Failed to load history: " + err.message);
      meta.textContent = ticker;
    });
})();
