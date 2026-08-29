(function () {
  const form = document.getElementById("backtest-form");
  const tickerSelect = document.getElementById("ticker");
  const cashInput = document.getElementById("cash");
  const fromInput = document.getElementById("from");
  const toInput = document.getElementById("to");
  const partsInput = document.getElementById("parts");
  const buyConfInput = document.getElementById("buy-conf");
  const sellConfInput = document.getElementById("sell-conf");
  const onBuySelect = document.getElementById("on-buy");
  const onSellSelect = document.getElementById("on-sell");
  const onAvoidSelect = document.getElementById("on-avoid");
  const runBtn = document.getElementById("run-btn");
  const errorEl = document.getElementById("error");
  const meta = document.getElementById("meta");
  const results = document.getElementById("results");
  const strategyLine = document.getElementById("strategy-line");
  const summary = document.getElementById("summary");
  const tradesEmpty = document.getElementById("trades-empty");
  const tradesTable = document.getElementById("trades-table");
  const tradesBody = document.getElementById("trades-body");
  const bestStrategyLine = document.getElementById("best-strategy-line");
  const findBestBtn = document.getElementById("find-best-btn");
  const applyBestBtn = document.getElementById("apply-best-btn");

  const params = new URLSearchParams(window.location.search);
  const DEFAULT_PARAM_ID = "1";
  let lastBestStrategy = null;

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
    const today = new Date();
    const fromDefault = new Date(today.getFullYear(), today.getMonth() - 6, today.getDate());
    function toIsoDate(d) {
      const y = d.getFullYear();
      const m = String(d.getMonth() + 1).padStart(2, "0");
      const day = String(d.getDate()).padStart(2, "0");
      return y + "-" + m + "-" + day;
    }
    fromInput.value = params.get("from") || toIsoDate(fromDefault);
    toInput.value = params.get("to") || toIsoDate(today);
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
    if (params.get("onBuy") && onBuySelect) {
      onBuySelect.value = params.get("onBuy").toUpperCase();
    }
    if (params.get("onSell") && onSellSelect) {
      const onSell = params.get("onSell").toUpperCase();
      onSellSelect.value = onSell === "NO_ACTION" ? "NONE" : onSell;
    }
    if (params.get("onAvoid") && onAvoidSelect) {
      const onAvoid = params.get("onAvoid").toUpperCase();
      onAvoidSelect.value = onAvoid === "NO_ACTION" ? "NONE" : onAvoid;
    }
  }

  function setBestStrategyButtons(mode) {
    // mode: "none" | "find" | "both" — Find Best Strategy stays visible so users can re-run.
    if (findBestBtn) {
      findBestBtn.hidden = false;
    }
    if (applyBestBtn) {
      applyBestBtn.hidden = mode !== "both";
    }
  }

  function setSelectValue(select, value) {
    if (!select || value == null || value === "") {
      return;
    }
    const normalized = String(value).toUpperCase();
    const mapped = normalized === "NO_ACTION" ? "NONE" : normalized;
    if ([...select.options].some((o) => o.value === mapped)) {
      select.value = mapped;
    }
  }

  function applyBestStrategyToForm(best) {
    if (!best) {
      showError("No best strategy to apply.");
      return;
    }
    clearError();

    if (best.ticker && tickerSelect) {
      const ticker = String(best.ticker).toUpperCase();
      if ([...tickerSelect.options].some((o) => o.value === ticker)) {
        tickerSelect.value = ticker;
      }
    }
    if (best.fromDate) {
      fromInput.value = best.fromDate;
    }
    if (best.toDate) {
      toInput.value = best.toDate;
    }
    if (best.startingCash != null && !Number.isNaN(Number(best.startingCash))) {
      cashInput.value = String(Math.round(Number(best.startingCash)));
    }
    if (best.parts != null) {
      partsInput.value = String(best.parts);
    }
    if (best.minBuyConfidence != null) {
      buyConfInput.value = Number(best.minBuyConfidence).toFixed(2);
    }
    if (best.minSellConfidence != null) {
      sellConfInput.value = Number(best.minSellConfidence).toFixed(2);
    }
    setSelectValue(onBuySelect, best.onBuy);
    setSelectValue(onSellSelect, best.onSell);
    setSelectValue(onAvoidSelect, best.onAvoid);

    meta.textContent =
      "Applied saved best strategy for " + (best.ticker || "") +
      " (" + (best.fromDate || "") + " → " + (best.toDate || "") + "). Click Run backtest to simulate.";
  }

  function renderBestStrategy(data) {
    if (!bestStrategyLine) {
      return;
    }
    const ticker = tickerSelect.value;
    const from = fromInput.value;
    const to = toInput.value;
    if (!data || !data.found || !data.best) {
      lastBestStrategy = null;
      bestStrategyLine.textContent =
        "No saved best strategy for " + ticker +
        " with from/to within ±7 days of " + from + " → " + to + ".";
      setBestStrategyButtons("find");
      return;
    }
    const b = data.best;
    lastBestStrategy = b;
    const bh = b.buyHoldReturnPct == null
      ? "—"
      : formatPct(b.buyHoldReturnPct);
    bestStrategyLine.textContent =
      b.ticker + " · saved " + b.fromDate + " → " + b.toDate +
      " · parts=" + b.parts +
      " buyConf≥" + formatNum(b.minBuyConfidence, 2) +
      " sellConf≥" + formatNum(b.minSellConfidence, 2) +
      " BUY→" + b.onBuy +
      " SELL→" + b.onSell +
      " AVOID→" + b.onAvoid +
      " · equity=" + formatMoney(b.endingEquity) +
      " (" + formatPct(b.returnPct) + ")" +
      " · buy&hold " + bh;
    setBestStrategyButtons("both");
  }

  function loadBestStrategy() {
    if (!bestStrategyLine) {
      return Promise.resolve();
    }
    const ticker = tickerSelect.value;
    const from = fromInput.value;
    const to = toInput.value;
    if (!ticker || !from || !to) {
      lastBestStrategy = null;
      bestStrategyLine.textContent =
        "Select a ticker and dates to look up a saved optimize result.";
      setBestStrategyButtons("none");
      return Promise.resolve();
    }

    bestStrategyLine.textContent = "Looking up saved best strategy…";
    setBestStrategyButtons("none");
    const url = new URL("api/backtest/best-strategy", window.location.href);
    url.searchParams.set("ticker", ticker);
    url.searchParams.set("from", from);
    url.searchParams.set("to", to);
    url.searchParams.set("param", DEFAULT_PARAM_ID);

    return fetch(url.toString(), { headers: { Accept: "application/json" } })
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
        renderBestStrategy(data);
      })
      .catch((err) => {
        lastBestStrategy = null;
        bestStrategyLine.textContent = "Failed to load best strategy: " + err.message;
        setBestStrategyButtons("find");
      });
  }

  function runFindBestStrategy() {
    const ticker = tickerSelect.value;
    const from = fromInput.value;
    const to = toInput.value;
    if (!ticker || !from || !to) {
      showError("Ticker, from, and to are required to find the best strategy.");
      return;
    }

    clearError();
    if (findBestBtn) {
      findBestBtn.disabled = true;
      findBestBtn.textContent = "Searching…";
    }
    lastBestStrategy = null;
    bestStrategyLine.textContent =
      "Running strategy search for " + ticker + " (" + from + " → " + to + ")… this can take a minute.";
    setBestStrategyButtons("find");

    fetch(apiUrl("api/backtest/optimize"), {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        ticker: ticker,
        from: from,
        to: to,
        cash: Number(cashInput.value) || 10000,
        top: 10,
        param: Number(DEFAULT_PARAM_ID)
      })
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
        renderBestStrategy(data);
      })
      .catch((err) => {
        lastBestStrategy = null;
        bestStrategyLine.textContent = "Find best strategy failed: " + err.message;
        setBestStrategyButtons("find");
        showError("Find best strategy failed: " + err.message);
      })
      .finally(() => {
        if (findBestBtn) {
          findBestBtn.disabled = false;
          findBestBtn.textContent = "Find Best Strategy";
        }
      });
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
        const tickers = [...(data.tickers || [])]
          .sort((a, b) => String(a).localeCompare(String(b), undefined, { sensitivity: "base" }));
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

    loadBestStrategy();

    const payload = {
      ticker: tickerSelect.value,
      cash: Number(cashInput.value),
      from: fromInput.value,
      to: toInput.value,
      parts: Number(partsInput.value),
      minBuyConfidence: Number(buyConfInput.value),
      minSellConfidence: Number(sellConfInput.value),
      onBuy: onBuySelect ? onBuySelect.value : "BUY_PART",
      onSell: onSellSelect ? onSellSelect.value : "SELL_PART",
      onAvoid: onAvoidSelect ? onAvoidSelect.value : "SELL_PART"
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

  if (findBestBtn) {
    findBestBtn.addEventListener("click", () => {
      runFindBestStrategy();
    });
  }
  if (applyBestBtn) {
    applyBestBtn.addEventListener("click", () => {
      applyBestStrategyToForm(lastBestStrategy);
    });
  }
})();
