(function () {
  const form = document.getElementById("backtest-form");
  const tickerSelect = document.getElementById("ticker");
  const cashInput = document.getElementById("cash");
  const fromInput = document.getElementById("from");
  const toInput = document.getElementById("to");
  const partsInput = document.getElementById("parts");
  const buyConfInput = document.getElementById("buy-conf");
  const sellConfInput = document.getElementById("sell-conf");
  const sellOnSellInput = document.getElementById("sell-on-sell");
  const sellOnAvoidInput = document.getElementById("sell-on-avoid");
  const sellAllOnAvoidInput = document.getElementById("sell-all-on-avoid");
  const runBtn = document.getElementById("run-btn");
  const errorEl = document.getElementById("error");
  const meta = document.getElementById("meta");
  const results = document.getElementById("results");
  const strategyLine = document.getElementById("strategy-line");
  const summary = document.getElementById("summary");
  const tradesEmpty = document.getElementById("trades-empty");
  const tradesTable = document.getElementById("trades-table");
  const tradesBody = document.getElementById("trades-body");

  const params = new URLSearchParams(window.location.search);

  function apiUrl(path) {
    return new URL(path, window.location.href).toString();
  }

  function escapeHtml(value) {
    return String(value)
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;");
  }

  function showError(message) {
    errorEl.hidden = false;
    errorEl.textContent = message;
  }

  function clearError() {
    errorEl.hidden = true;
    errorEl.textContent = "";
  }

  function formatMoney(value) {
    return Number(value).toLocaleString("en-US", {
      style: "currency",
      currency: "USD"
    });
  }

  function formatNum(value, digits) {
    if (value == null || Number.isNaN(value)) {
      return "—";
    }
    return Number(value).toLocaleString("en-US", {
      minimumFractionDigits: digits,
      maximumFractionDigits: digits
    });
  }

  function formatPct(value) {
    const sign = value > 0 ? "+" : "";
    return sign + formatNum(value, 2) + "%";
  }

  function actionBadge(action) {
    if (!action) {
      return "—";
    }
    const safe = escapeHtml(String(action).toUpperCase());
    return '<span class="action ' + safe + '">' + safe + "</span>";
  }

  function setDefaults() {
    fromInput.value = params.get("from") || "2026-01-01";
    toInput.value = params.get("to") || "2026-07-21";
    if (params.get("cash")) {
      cashInput.value = params.get("cash");
    }
    if (params.get("parts")) {
      partsInput.value = params.get("parts");
    }
    if (params.get("buyConf")) {
      buyConfInput.value = params.get("buyConf");
    }
    if (params.get("sellConf")) {
      sellConfInput.value = params.get("sellConf");
    }
  }

  function loadTickers() {
    return fetch(apiUrl("api/backtest"), { headers: { Accept: "application/json" } })
      .then((response) => response.text().then((text) => {
        let data = null;
        try {
          data = text ? JSON.parse(text) : null;
        } catch (_) {
          /* ignore */
        }
        if (!response.ok) {
          throw new Error((data && data.error) || text || ("HTTP " + response.status));
        }
        return data;
      }))
      .then((data) => {
        const tickers = data.tickers || [];
        tickerSelect.innerHTML = tickers.map((t) =>
          '<option value="' + escapeHtml(t) + '">' + escapeHtml(t) + "</option>"
        ).join("");
        const preferred = (params.get("ticker") || "QQQ").toUpperCase();
        if (tickers.includes(preferred)) {
          tickerSelect.value = preferred;
        } else if (tickers.length) {
          tickerSelect.value = tickers[0];
        }
        meta.textContent = tickers.length
          ? "Watch list: " + tickers.length + " ticker(s). Run one customized strategy."
          : "No tickers in admin TICKERS.";
      });
  }

  function renderSummary(data) {
    const r = data.result || {};
    const bh = data.buyAndHold || {};
    strategyLine.textContent =
      data.ticker + " · " + data.from + " → " + data.to +
      " · " + data.tradingDays + " day(s) (" + data.daysWithAction + " with action) · " +
      data.strategy;

    const equityClass = Number(r.endingEquity) < Number(bh.endingEquity) ? "down" : "up";
    summary.innerHTML =
      '<div class="backtest-metric">' +
      '<span>Ending equity</span><strong class="' + equityClass + '">' +
      escapeHtml(formatMoney(r.endingEquity)) + "</strong></div>" +
      '<div class="backtest-metric">' +
      "<span>Return</span><strong>" + escapeHtml(formatPct(r.returnPct)) + "</strong></div>" +
      '<div class="backtest-metric">' +
      "<span>Buys / sells / skipped</span><strong>" +
      escapeHtml(r.buyCount + " / " + r.sellCount + " / " + r.skippedBuys) +
      "</strong></div>" +
      '<div class="backtest-metric">' +
      "<span>Ending cash / shares</span><strong>" +
      escapeHtml(formatMoney(r.endingCash) + " / " + r.endingShares) +
      "</strong></div>" +
      '<div class="backtest-metric">' +
      "<span>Buy &amp; hold</span><strong>" +
      escapeHtml(formatMoney(bh.endingEquity) + " (" + formatPct(bh.returnPct) + ")") +
      "</strong></div>";
  }

  function renderTrades(trades) {
    const rows = (trades || []).filter((t) => !String(t.event || "").startsWith("SKIP_"));
    if (rows.length === 0) {
      tradesTable.hidden = true;
      tradesEmpty.hidden = false;
      tradesBody.innerHTML = "";
      return;
    }
    tradesEmpty.hidden = true;
    tradesTable.hidden = false;
    tradesBody.innerHTML = rows.map((t) => {
      const delta = Number(t.sharesDelta);
      const sign = delta > 0 ? "+" : "";
      return (
        "<tr>" +
        "<td>" + escapeHtml(t.date || "") + "</td>" +
        "<td>" + escapeHtml(t.event || "") + "</td>" +
        "<td>" + escapeHtml(sign + delta) + "</td>" +
        "<td>" + escapeHtml(formatNum(t.price, 2)) + "</td>" +
        "<td>" + escapeHtml(formatMoney(t.cashAfter)) + "</td>" +
        "<td>" + escapeHtml(String(t.sharesAfter)) + "</td>" +
        "<td>" + escapeHtml(formatMoney(t.equityAfter)) + "</td>" +
        "<td>" + actionBadge(t.suggestedAction) + "</td>" +
        "<td>" + escapeHtml(formatNum(t.confidence, 2)) + "</td>" +
        "</tr>"
      );
    }).join("");
  }

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    clearError();
    results.hidden = true;
    runBtn.disabled = true;
    runBtn.textContent = "Running…";

    const payload = {
      ticker: tickerSelect.value,
      cash: Number(cashInput.value),
      from: fromInput.value,
      to: toInput.value,
      parts: Number(partsInput.value),
      minBuyConfidence: Number(buyConfInput.value),
      minSellConfidence: Number(sellConfInput.value),
      sellOnSell: !!sellOnSellInput.checked,
      sellOnAvoid: !!sellOnAvoidInput.checked,
      sellAllOnAvoid: !!sellAllOnAvoidInput.checked
    };

    fetch(apiUrl("api/backtest"), {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json"
      },
      body: JSON.stringify(payload)
    })
      .then((response) => response.text().then((text) => {
        let data = null;
        try {
          data = text ? JSON.parse(text) : null;
        } catch (_) {
          /* ignore */
        }
        if (!response.ok) {
          throw new Error((data && data.error) || text || ("HTTP " + response.status));
        }
        return data;
      }))
      .then((data) => {
        renderSummary(data);
        renderTrades(data.trades || []);
        results.hidden = false;
        results.scrollIntoView({ behavior: "smooth", block: "start" });
      })
      .catch((err) => {
        showError("Backtest failed: " + err.message);
      })
      .finally(() => {
        runBtn.disabled = false;
        runBtn.textContent = "Run backtest";
      });
  });

  setDefaults();
  loadTickers().catch((err) => {
    showError("Failed to load tickers: " + err.message);
  });
})();
