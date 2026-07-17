(function () {
  const meta = document.getElementById("meta");
  const errorEl = document.getElementById("error");
  const statusEl = document.getElementById("status");
  const emptyEl = document.getElementById("empty");
  const table = document.getElementById("admin-table");
  const tbody = document.getElementById("admin-body");
  const form = document.getElementById("admin-form");
  const keyInput = document.getElementById("key");
  const valueInput = document.getElementById("value");

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
        '<tr class="main-row">' +
        "<td>" + escapeHtml(row.id == null ? "—" : row.id) + "</td>" +
        "<td>" + escapeHtml(key) + "</td>" +
        "<td class=\"admin-value\">" + escapeHtml(value) + "</td>" +
        '<td><button type="button" class="edit-btn" data-key="' +
        encodeURIComponent(key) +
        '" data-value="' +
        encodeURIComponent(value) +
        '">Edit</button></td>' +
        "</tr>"
      );
    }).join("");

    tbody.querySelectorAll(".edit-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        keyInput.value = decodeURIComponent(btn.getAttribute("data-key") || "");
        valueInput.value = decodeURIComponent(btn.getAttribute("data-value") || "");
        keyInput.focus();
        showStatus("Editing \"" + keyInput.value + "\". Save to update.", false);
      });
    });
  }

  function loadProperties() {
    meta.textContent = "Loading…";
    clearError();
    return fetch(apiUrl("api/admin"), { headers: { Accept: "application/json" } })
      .then(async (response) => {
        const text = await response.text();
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
      })
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
      .then(async (response) => {
        const text = await response.text();
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
      })
      .then((data) => {
        const saved = data.property || {};
        showStatus("Saved \"" + (saved.key || payload.key) + "\".", false);
        return loadProperties();
      })
      .catch((err) => {
        showStatus("Save failed: " + err.message, true);
      });
  });

  document.getElementById("clear-form").addEventListener("click", () => {
    keyInput.value = "";
    valueInput.value = "";
    statusEl.hidden = true;
    keyInput.focus();
  });

  loadProperties();
})();
