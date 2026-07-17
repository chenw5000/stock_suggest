(function () {
  const meta = document.getElementById("meta");
  const errorEl = document.getElementById("error");
  const statusEl = document.getElementById("status");
  const batchStatusEl = document.getElementById("batch-status");
  const emptyEl = document.getElementById("empty");
  const table = document.getElementById("admin-table");
  const tbody = document.getElementById("admin-body");
  const form = document.getElementById("admin-form");
  const keyInput = document.getElementById("key");
  const valueInput = document.getElementById("value");
  const runBatchBtn = document.getElementById("run-batch");

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

  function showStatus(message, isError) {
    statusEl.hidden = false;
    statusEl.textContent = message;
    statusEl.classList.toggle("error-text", !!isError);
  }

  function showBatchStatus(message, isError) {
    batchStatusEl.hidden = false;
    batchStatusEl.textContent = message;
    batchStatusEl.classList.toggle("error-text", !!isError);
  }

  function parseJsonResponse(response) {
    return response.text().then((text) => {
      let data = null;
      try {
        data = text ? JSON.parse(text) : null;
      } catch (_) {
        /* ignore */
      }
      if (!response.ok) {
        throw new Error((data && data.error) || (data && data.message) || text || ("HTTP " + response.status));
      }
      return data;
    });
  }

  function renderRows(properties) {
    tbody.innerHTML = "";
    if (!properties || properties.length === 0) {
      table.hidden = true;
      emptyEl.hidden = false;
      return;
    }
    emptyEl.hidden = true;
    table.hidden = false;
    tbody.innerHTML = properties.map((row) => {
      const key = row.key == null ? "" : String(row.key);
      const value = row.value == null ? "" : String(row.value);
      return (
        '<tr class="main-row" data-key="' + encodeURIComponent(key) + '">' +
        '<td class="admin-key">' + escapeHtml(key) + "</td>" +
        '<td class="admin-value-cell">' +
        '<input type="text" class="admin-value-input" maxlength="155" value="' +
        escapeHtml(value) +
        '">' +
        "</td>" +
        '<td class="admin-actions">' +
        '<button type="button" class="update-btn">Update</button>' +
        '<button type="button" class="delete-btn">Delete</button>' +
        "</td>" +
        "</tr>"
      );
    }).join("");

    tbody.querySelectorAll("tr.main-row").forEach((tr) => {
      const key = decodeURIComponent(tr.getAttribute("data-key") || "");
      const valueField = tr.querySelector(".admin-value-input");
      const updateBtn = tr.querySelector(".update-btn");
      const deleteBtn = tr.querySelector(".delete-btn");

      updateBtn.addEventListener("click", () => {
        clearError();
        fetch(apiUrl("api/admin"), {
          method: "PUT",
          headers: {
            Accept: "application/json",
            "Content-Type": "application/json"
          },
          body: JSON.stringify({ key: key, value: valueField.value })
        })
          .then(parseJsonResponse)
          .then((data) => {
            const saved = data.property || {};
            showStatus("Updated \"" + (saved.key || key) + "\".", false);
            return loadProperties();
          })
          .catch((err) => {
            showStatus("Update failed: " + err.message, true);
          });
      });

      deleteBtn.addEventListener("click", () => {
        if (!window.confirm("Delete property \"" + key + "\"?")) {
          return;
        }
        clearError();
        fetch(apiUrl("api/admin/" + encodeURIComponent(key)), {
          method: "DELETE",
          headers: { Accept: "application/json" }
        })
          .then(parseJsonResponse)
          .then(() => {
            showStatus("Deleted \"" + key + "\".", false);
            return loadProperties();
          })
          .catch((err) => {
            showStatus("Delete failed: " + err.message, true);
          });
      });
    });
  }

  function loadProperties() {
    meta.textContent = "Loading…";
    clearError();
    return fetch(apiUrl("api/admin"), { headers: { Accept: "application/json" } })
      .then(parseJsonResponse)
      .then((data) => {
        meta.textContent = (data.count || 0) + " propert" + ((data.count || 0) === 1 ? "y" : "ies");
        renderRows(data.properties || []);
      })
      .catch((err) => {
        meta.textContent = "Admin properties";
        showError("Failed to load properties: " + err.message);
        table.hidden = true;
        emptyEl.hidden = true;
      });
  }

  function setBatchBusy(busy) {
    runBatchBtn.disabled = !!busy;
  }

  function refreshBatchStatus() {
    return fetch(apiUrl("api/batch"), { headers: { Accept: "application/json" } })
      .then(parseJsonResponse)
      .then((data) => {
        setBatchBusy(!!data.running);
        if (data.running) {
          showBatchStatus("Batch job is running…", false);
        }
      })
      .catch(() => {
        /* ignore status probe failures */
      });
  }

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    clearError();
    const payload = {
      key: keyInput.value.trim(),
      value: valueInput.value
    };
    if (!payload.key) {
      showStatus("Key is required.", true);
      return;
    }

    fetch(apiUrl("api/admin"), {
      method: "PUT",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json"
      },
      body: JSON.stringify(payload)
    })
      .then(parseJsonResponse)
      .then((data) => {
        const saved = data.property || {};
        showStatus("Added \"" + (saved.key || payload.key) + "\".", false);
        keyInput.value = "";
        valueInput.value = "";
        return loadProperties();
      })
      .catch((err) => {
        showStatus("Add failed: " + err.message, true);
      });
  });

  runBatchBtn.addEventListener("click", () => {
    clearError();
    setBatchBusy(true);
    showBatchStatus("Starting batch job…", false);
    fetch(apiUrl("api/batch"), {
      method: "POST",
      headers: { Accept: "application/json" }
    })
      .then(async (response) => {
        const text = await response.text();
        let data = null;
        try {
          data = text ? JSON.parse(text) : null;
        } catch (_) {
          /* ignore */
        }
        if (!response.ok && response.status !== 409) {
          throw new Error((data && data.error) || (data && data.message) || text || ("HTTP " + response.status));
        }
        return data || {};
      })
      .then((data) => {
        showBatchStatus(data.message || (data.started ? "Batch job started." : "Batch already running."), !data.started && !!data.running);
        setBatchBusy(true);
        const poll = window.setInterval(() => {
          fetch(apiUrl("api/batch"), { headers: { Accept: "application/json" } })
            .then(parseJsonResponse)
            .then((status) => {
              if (!status.running) {
                window.clearInterval(poll);
                setBatchBusy(false);
                showBatchStatus("Batch job finished.", false);
              }
            })
            .catch(() => {
              window.clearInterval(poll);
              setBatchBusy(false);
            });
        }, 3000);
      })
      .catch((err) => {
        setBatchBusy(false);
        showBatchStatus("Batch failed to start: " + err.message, true);
      });
  });

  loadProperties();
  refreshBatchStatus();
})();
