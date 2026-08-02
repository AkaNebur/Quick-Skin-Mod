"use strict";

function node(tag, className, text) {
  const value = document.createElement(tag);
  if (className) value.className = className;
  if (text !== undefined) value.textContent = text;
  return value;
}

function sameOriginPath(value) {
  const url = new URL(value, window.location.href);
  if (url.origin !== window.location.origin) throw new Error("Cross-origin image path rejected");
  return url.href;
}

function httpsLink(value) {
  const url = new URL(value);
  if (url.protocol !== "https:") throw new Error("Non-HTTPS provenance link rejected");
  return url.href;
}

function option(select, value, label) {
  const item = document.createElement("option");
  item.value = value;
  item.textContent = label;
  select.append(item);
}

function unique(values) {
  return [...new Set(values)].sort((left, right) => left.localeCompare(right, undefined, { numeric: true }));
}

function friendlyScenario(value) {
  return {
    "phase0-smoke": "Smoke",
    "propagation": "Propagation",
    "propagation-live": "Live propagation",
    "full": "Full suite"
  }[value] || value;
}

function friendlyRole(value) {
  return { client_a: "Client A", client_b: "Client B" }[value] || value;
}

function captureCard(frame) {
  const figure = node("figure", "capture-card");
  const imageLink = node("a");
  imageLink.href = sameOriginPath(frame.image);
  imageLink.target = "_blank";
  imageLink.rel = "noopener";
  imageLink.setAttribute(
    "aria-label",
    `Open optimized gallery image for ${frame.title}, Minecraft ${frame.version}, ${frame.loader_name}, ${friendlyRole(frame.role)}; opens in new tab`
  );
  const image = document.createElement("img");
  image.src = sameOriginPath(frame.image);
  image.alt = frame.alt;
  image.width = frame.width;
  image.height = frame.height;
  image.loading = "lazy";
  image.decoding = "async";
  imageLink.append(image);

  const caption = document.createElement("figcaption");
  const titleRow = node("div", "capture-title-row");
  titleRow.append(node("h3", "", frame.title), node("span", "verified-badge", "Passed"));
  const metadata = node("div", "capture-meta");
  for (const text of [frame.version, frame.loader_name, friendlyScenario(frame.scenario), friendlyRole(frame.role)]) {
    metadata.append(node("span", "", text));
  }
  const details = document.createElement("details");
  const summary = document.createElement("summary");
  summary.textContent = "What this validates";
  details.append(summary, node("p", "", frame.expectation));
  const provenance = node("div", "provenance-line");
  if (frame.source_run_url === frame.target_run_url) {
    const run = node("a", "", "tested & publishing run ↗");
    run.href = httpsLink(frame.target_run_url);
    provenance.append(run, node("span", "", frame.target_sha.slice(0, 12)));
  } else {
    const sourceRun = node("a", "", "tested source run ↗");
    sourceRun.href = httpsLink(frame.source_run_url);
    const targetRun = node("a", "", "publishing target run ↗");
    targetRun.href = httpsLink(frame.target_run_url);
    provenance.append(
      sourceRun,
      node("span", "", frame.source_sha.slice(0, 12)),
      targetRun,
      node("span", "", frame.target_sha.slice(0, 12))
    );
  }
  caption.append(titleRow, metadata, details, provenance);
  figure.append(imageLink, caption);
  return figure;
}

function missingCard(label) {
  const card = node("div", "missing-card");
  const copy = node("div");
  copy.append(node("h3", "", label), node("p", "", "No validated capture was published for this exact cell."));
  card.append(copy);
  return card;
}

function notApplicableCard(label, loader) {
  const card = node("div", "missing-card");
  const copy = node("div");
  copy.append(
    node("h3", "", label),
    node("p", "", `Not applicable — ${loader} is not supported by this Minecraft version.`)
  );
  card.append(copy);
  return card;
}

class Gallery {
  constructor(data) {
    this.data = data;
    this.activeTab = 0;
    this.tabs = data.releases.map((release) => ({
        id: `v-${release.version.replaceAll(".", "-")}`,
        label: `Minecraft ${release.version}`,
        version: release.version
      })).concat([{ id: "all", label: "All versions", version: null }]);
    this.tabButtons = [];
    this.panels = [];
  }

  start() {
    this.renderSummary();
    this.populateFilters();
    this.createTabs();
    this.bindFilters();
    this.bindViewSwitch();
    this.renderGallery();
    this.renderComparison();
  }

  renderSummary() {
    const summary = document.querySelector("#release-summary");
    const total = this.data.frames.length;
    summary.append(node("span", "summary-item", `${this.data.releases.length} Minecraft versions`));
    summary.append(node("span", "summary-item", `${total} validated captures`));
    for (const release of this.data.releases) {
      const item = node("span", "summary-item");
      const strong = document.createElement("strong");
      strong.textContent = release.version;
      item.append(strong, document.createTextNode(` · ${release.loader_names.join(" + ")}`));
      summary.append(item);
    }
  }

  populateFilters() {
    const loaders = unique(this.data.frames.map((frame) => frame.loader));
    const scenarios = unique(this.data.frames.map((frame) => frame.scenario));
    const roles = unique(this.data.frames.map((frame) => frame.role));
    for (const loader of loaders) {
      const label = this.data.frames.find((frame) => frame.loader === loader).loader_name;
      option(document.querySelector("#loader-filter"), loader, label);
      option(document.querySelector("#compare-loader"), loader, label);
    }
    for (const scenario of scenarios) option(document.querySelector("#scenario-filter"), scenario, friendlyScenario(scenario));
    for (const role of roles) option(document.querySelector("#role-filter"), role, friendlyRole(role));

    const captures = new Map();
    for (const frame of this.data.frames) {
      if (!captures.has(frame.capture_id)) {
        captures.set(frame.capture_id, `${frame.title} · ${friendlyScenario(frame.scenario)} · ${friendlyRole(frame.role)}`);
      }
    }
    const compare = document.querySelector("#capture-compare");
    for (const [captureId, label] of [...captures].sort((left, right) => left[1].localeCompare(right[1]))) {
      option(compare, captureId, label);
    }
  }

  createTabs() {
    const tablist = document.querySelector("#version-tabs");
    const panels = document.querySelector("#version-panels");
    this.tabs.forEach((tab, index) => {
      const button = node("button", "version-tab", tab.label);
      button.type = "button";
      button.id = `tab-${tab.id}`;
      button.setAttribute("role", "tab");
      button.setAttribute("aria-controls", `panel-${tab.id}`);
      button.setAttribute("aria-selected", index === 0 ? "true" : "false");
      button.tabIndex = index === 0 ? 0 : -1;
      button.addEventListener("click", () => this.activateTab(index, false));
      button.addEventListener("keydown", (event) => this.handleTabKey(event, index));
      tablist.append(button);
      this.tabButtons.push(button);

      const panel = node("section", "version-panel");
      panel.id = `panel-${tab.id}`;
      panel.setAttribute("role", "tabpanel");
      panel.setAttribute("aria-labelledby", button.id);
      panel.tabIndex = 0;
      panel.hidden = index !== 0;
      panels.append(panel);
      this.panels.push(panel);
    });
  }

  handleTabKey(event, index) {
    let target = null;
    if (event.key === "ArrowRight") target = (index + 1) % this.tabs.length;
    if (event.key === "ArrowLeft") target = (index - 1 + this.tabs.length) % this.tabs.length;
    if (event.key === "Home") target = 0;
    if (event.key === "End") target = this.tabs.length - 1;
    if (target !== null) {
      event.preventDefault();
      this.activateTab(target, true);
    }
  }

  activateTab(index, focus) {
    this.activeTab = index;
    this.tabButtons.forEach((button, buttonIndex) => {
      const active = buttonIndex === index;
      button.setAttribute("aria-selected", active ? "true" : "false");
      button.tabIndex = active ? 0 : -1;
      this.panels[buttonIndex].hidden = !active;
    });
    if (focus) this.tabButtons[index].focus();
    this.renderGallery();
  }

  bindFilters() {
    document.querySelector("#gallery-filters").addEventListener("submit", (event) => event.preventDefault());
    for (const selector of ["#loader-filter", "#scenario-filter", "#role-filter"]) {
      document.querySelector(selector).addEventListener("change", () => this.renderGallery());
    }
    document.querySelector("#capture-search").addEventListener("input", () => this.renderGallery());
    document.querySelector("#capture-compare").addEventListener("change", () => this.renderComparison());
    document.querySelector("#compare-loader").addEventListener("change", () => this.renderComparison());
  }

  bindViewSwitch() {
    const galleryButton = document.querySelector("#gallery-view-button");
    const compareButton = document.querySelector("#compare-view-button");
    const setView = (comparison) => {
      document.querySelector("#gallery-view").hidden = comparison;
      document.querySelector("#compare-view").hidden = !comparison;
      galleryButton.classList.toggle("is-active", !comparison);
      compareButton.classList.toggle("is-active", comparison);
      galleryButton.setAttribute("aria-pressed", comparison ? "false" : "true");
      compareButton.setAttribute("aria-pressed", comparison ? "true" : "false");
      if (comparison) this.renderComparison(); else this.renderGallery();
    };
    galleryButton.addEventListener("click", () => setView(false));
    compareButton.addEventListener("click", () => setView(true));
  }

  filteredFrames() {
    const tab = this.tabs[this.activeTab];
    const loader = document.querySelector("#loader-filter").value;
    const scenario = document.querySelector("#scenario-filter").value;
    const role = document.querySelector("#role-filter").value;
    const search = document.querySelector("#capture-search").value.trim().toLocaleLowerCase();
    return this.data.frames.filter((frame) => {
      if (tab.version && frame.version !== tab.version) return false;
      if (loader !== "all" && frame.loader !== loader) return false;
      if (scenario !== "all" && frame.scenario !== scenario) return false;
      if (role !== "all" && frame.role !== role) return false;
      if (search && !`${frame.title} ${frame.expectation} ${frame.capture_id}`.toLocaleLowerCase().includes(search)) return false;
      return true;
    });
  }

  renderGallery() {
    const panel = this.panels[this.activeTab];
    panel.replaceChildren();
    const frames = this.filteredFrames();
    if (!frames.length) {
      panel.append(node("div", "empty-state", "No validated captures match these filters."));
    } else {
      const grid = node("div", "capture-grid");
      for (const frame of frames) grid.append(captureCard(frame));
      panel.append(grid);
    }
    document.querySelector("#gallery-status").textContent = `${frames.length} validated capture${frames.length === 1 ? "" : "s"} shown`;
  }

  renderComparison() {
    const captureId = document.querySelector("#capture-compare").value;
    const selectedLoader = document.querySelector("#compare-loader").value;
    const grid = document.querySelector("#comparison-grid");
    grid.replaceChildren();
    if (!captureId) return;
    let cells = 0;
    for (const release of this.data.releases) {
      const loaders = selectedLoader === "all" ? release.loaders : [selectedLoader];
      for (const loader of loaders) {
        const label = `${release.version} · ${release.loader_names[release.loaders.indexOf(loader)] || loader}`;
        const cell = node("section", "comparison-cell");
        const cellLabel = node("span", "compare-column-label", label);
        cellLabel.id = `compare-cell-${cells}`;
        cell.setAttribute("aria-labelledby", cellLabel.id);
        cell.append(cellLabel);
        if (!release.loaders.includes(loader)) {
          cell.append(notApplicableCard(label, loader));
          grid.append(cell);
          cells += 1;
          continue;
        }
        const frame = this.data.frames.find(
          (item) => item.version === release.version && item.loader === loader && item.capture_id === captureId
        );
        cell.append(frame ? captureCard(frame) : missingCard(label));
        grid.append(cell);
        cells += 1;
      }
    }
    document.querySelector("#gallery-status").textContent = `${cells} version/loader cell${cells === 1 ? "" : "s"} aligned by semantic checkpoint`;
  }
}

async function startGallery() {
  const status = document.querySelector("#gallery-status");
  try {
    const response = await fetch("gallery-data.json", { credentials: "same-origin" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const data = await response.json();
    if (data.schema_version !== 1 || !Array.isArray(data.frames) || !data.frames.length || !Array.isArray(data.releases)) {
      throw new Error("Unsupported or empty gallery inventory");
    }
    new Gallery(data).start();
  } catch (error) {
    status.dataset.error = "true";
    status.textContent = `The evidence gallery could not be loaded: ${error.message}`;
  }
}

startGallery();
