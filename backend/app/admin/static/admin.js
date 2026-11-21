"use strict";

const state = { meta: null, user: null, resources: {} };
const app = document.getElementById("app");
const PAGE_SIZE = 50;
const SVG_NS = "http://www.w3.org/2000/svg";
const PIPELINE_GROUP = "Dátová pipeline";
const LINES = {
  "Dátová pipeline": { color: "#4a6274", soft: "#dce5eb", icon: "database" },
  "Obsah": { color: "#0e7c66", soft: "#d6eee5", icon: "pin" },
  "Kalendár": { color: "#b0700f", soft: "#f6e3bf", icon: "calendar" },
  "Doprava": { color: "#2563a5", soft: "#d9e6f4", icon: "bus" },
  "Používatelia a trasy": { color: "#b14f36", soft: "#f4dbd2", icon: "users" },
  "Štatistiky spätnej väzby": { color: "#73508e", soft: "#e7dcf0", icon: "chart" },
};
const HOME_LINE = { color: "#0e7c66", soft: "#d6eee5", icon: "home" };
const TONES = { completed: "green", in_progress: "blue", paused: "ochre", cancelled: "terra", not_started: "muted" };
const MONTHS = ["jan", "feb", "mar", "apr", "máj", "jún", "júl", "aug", "sep", "okt", "nov", "dec"];
const WEEKDAYS = ["pondelok", "utorok", "streda", "štvrtok", "piatok", "sobota", "nedeľa"];
const WIDE_KINDS = new Set(["longtext", "opening_hours", "json"]);
const NUMERIC_KINDS = new Set(["int", "float"]);
const numberFormat = new Intl.NumberFormat("sk-SK", { maximumFractionDigits: 6 });

const ICONS = {
  home: '<path d="M3 11.5 12 4l9 7.5"/><path d="M5.5 10v10h13V10"/><path d="M10 20v-5h4v5"/>',
  pin: '<path d="M12 21s-6.5-5.7-6.5-11.2a6.5 6.5 0 0 1 13 0C18.5 15.3 12 21 12 21z"/><circle cx="12" cy="9.8" r="2.4"/>',
  city: '<path d="M3 21h18"/><path d="M5 21V9l5-3v15"/><path d="M10 21V4l9 4v13"/><path d="M13 10.5h3M13 14h3M13 17.5h3"/>',
  route: '<circle cx="6" cy="6" r="2.2"/><circle cx="18" cy="18" r="2.2"/><path d="M8.2 6H15a3 3 0 0 1 0 6H9a3 3 0 0 0 0 6h6.8"/>',
  flag: '<path d="M5 21V4"/><path d="M5 4h11l-2 3.5L16 11H5"/>',
  calendar: '<rect x="3.5" y="5" width="17" height="15.5" rx="2.5"/><path d="M3.5 10h17M8 3v4M16 3v4"/>',
  school: '<path d="M2.5 9.5 12 5l9.5 4.5L12 14z"/><path d="M6.5 11.5v4.3c3 2.3 8 2.3 11 0v-4.3"/><path d="M21.5 9.5V15"/>',
  bus: '<rect x="5" y="3" width="14" height="15" rx="3"/><path d="M5 11h14M8 21v-3M16 21v-3"/><circle cx="8.6" cy="14.6" r=".9"/><circle cx="15.4" cy="14.6" r=".9"/>',
  chart: '<path d="M4 20h16"/><path d="M7 16v-5M12 16V6M17 16v-8"/>',
  users: '<circle cx="9" cy="8" r="3.2"/><path d="M3 20c0-3.4 2.7-6 6-6s6 2.6 6 6"/><circle cx="17" cy="9" r="2.5"/><path d="M16 14.2c2.9.3 5 2.6 5 5.8"/>',
  star: '<path d="m12 3.5 2.6 5.4 5.9.8-4.3 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8-4.3-4.1 5.9-.8z"/>',
  clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="m16 16 4.5 4.5"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  back: '<path d="M15 5l-7 7 7 7"/>',
  next: '<path d="m9 5 7 7-7 7"/>',
  logout: '<path d="M15 4h3.5A1.5 1.5 0 0 1 20 5.5v13a1.5 1.5 0 0 1-1.5 1.5H15"/><path d="M10 8l-4 4 4 4M6 12h10"/>',
  trash: '<path d="M4 7h16M10 11v6M14 11v6M6 7l1 12.5A1.5 1.5 0 0 0 8.5 21h7a1.5 1.5 0 0 0 1.5-1.5L18 7M9 7V4h6v3"/>',
  check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
  refresh: '<path d="M20 11a8 8 0 0 0-14.5-4.5L4 8"/><path d="M4 4v4h4"/><path d="M4 13a8 8 0 0 0 14.5 4.5L20 16"/><path d="M20 20v-4h-4"/>',
  menu: '<path d="M4 7h16M4 12h16M4 17h16"/>',
  map: '<path d="m9 4-5 2v14l5-2 6 2 5-2V4l-5 2z"/><path d="M9 4v14M15 6v14"/>',
  eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>',
  alert: '<path d="M12 4 2.5 20h19z"/><path d="M12 10v4.5M12 17.2v.3"/>',
  ok: '<circle cx="12" cy="12" r="8.5"/><path d="m8.5 12.3 2.4 2.4 4.8-4.9"/>',
  inbox: '<path d="M3 13h5l1.5 3h5L16 13h5"/><path d="M5 5h14l2 8v6H3v-6z"/>',
  database: '<ellipse cx="12" cy="5.5" rx="7" ry="2.8"/><path d="M5 5.5v6.5c0 1.6 3.1 2.8 7 2.8s7-1.2 7-2.8V5.5"/><path d="M5 12v6.3c0 1.6 3.1 2.8 7 2.8s7-1.2 7-2.8V12"/>',
  shield: '<path d="M12 3 5 6v5.5c0 4.4 2.9 7.8 7 9.5 4.1-1.7 7-5.1 7-9.5V6z"/><path d="m9 12 2.2 2.2L15.5 10"/>',
  download: '<path d="M12 4v11M7 10.5l5 5 5-5"/><path d="M5 20h14"/>',
  dot: '<circle cx="12" cy="12" r="3"/>',
};
const ISSUE_LABELS = {
  duplicate_key: "Duplicitné objekty OSM",
  missing_name: "Bez názvu",
  missing_coordinates: "Bez súradníc",
  outside_city: "Mimo územia mesta",
  unknown_category: "Nepovolená kategória",
  invalid_base_score: "Neplatné skóre",
  invalid_visit_duration: "Neplatná dĺžka návštevy",
  missing_description: "Bez opisu",
  possible_duplicate: "Možné duplikáty",
};
const CITY_ART = `
<svg class="city-art" viewBox="0 0 640 900" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
  <defs>
    <linearGradient id="art-bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#0e7c66"/><stop offset="1" stop-color="#074a3e"/>
    </linearGradient>
  </defs>
  <rect width="640" height="900" fill="url(#art-bg)"/>
  <g fill="none" stroke="#ffffff" stroke-opacity=".07" stroke-width="1.6">
    <path d="M-60 520c90-40 160 10 250-20s150-90 250-70 160 60 260 30"/>
    <path d="M-60 560c90-40 160 10 250-20s150-90 250-70 160 60 260 30"/>
    <path d="M-60 600c90-40 160 10 250-20s150-90 250-70 160 60 260 30"/>
    <path d="M-60 640c90-40 160 10 250-20s150-90 250-70 160 60 260 30"/>
    <path d="M-60 680c90-40 160 10 250-20s150-90 250-70 160 60 260 30"/>
    <path d="M-60 720c90-40 160 10 250-20s150-90 250-70 160 60 260 30"/>
  </g>
  <g stroke="#ffffff" stroke-opacity=".09" stroke-width="16" stroke-linecap="round" fill="none">
    <path d="M40 470 610 400"/><path d="M20 610 640 560"/><path d="M60 780 620 720"/>
    <path d="M150 380 210 900"/><path d="M330 360 380 900"/><path d="M500 340 560 900"/>
  </g>
  <path d="M-30 840c110-60 200 20 320-30s170-140 380-110" fill="none" stroke="#6fd6bd" stroke-opacity=".28" stroke-width="38" stroke-linecap="round"/>
  <path class="route" d="M96 812c38-70 98-58 120-118s-30-104 34-140 150 10 182-52 6-104 86-130" fill="none" stroke="#fbdfb0" stroke-width="8" stroke-linecap="round" stroke-linejoin="round"/>
  <g fill="#0e7c66" stroke="#ffffff" stroke-width="5">
    <circle cx="96" cy="812" r="12"/><circle cx="216" cy="694" r="9"/><circle cx="250" cy="554" r="9"/><circle cx="432" cy="502" r="9"/><circle cx="518" cy="372" r="12"/>
  </g>
  <path fill="#fbdfb0" d="M518 280c-14 0-25 11-25 25 0 19 25 41 25 41s25-22 25-41c0-14-11-25-25-25zm0 34a9 9 0 1 1 0-18 9 9 0 0 1 0 18z"/>
</svg>`;

function el(tag, attrs, children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value === null || value === undefined || value === false) continue;
    if (key === "class") node.className = value;
    else if (key === "text") node.textContent = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else if (key === "value") node.value = value;
    else if (key === "checked") node.checked = Boolean(value);
    else node.setAttribute(key, value === true ? "" : value);
  }
  for (const child of [].concat(children === undefined ? [] : children)) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

function icon(name, size = 18) {
  const svg = document.createElementNS(SVG_NS, "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("width", String(size));
  svg.setAttribute("height", String(size));
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-width", "1.8");
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("stroke-linejoin", "round");
  svg.setAttribute("aria-hidden", "true");
  svg.innerHTML = ICONS[name] || ICONS.dot;
  return svg;
}

function brandMark(size = 42) {
  const box = el("span", { class: "brand-mark", style: `width:${size}px;height:${size}px` });
  const svg = document.createElementNS(SVG_NS, "svg");
  svg.setAttribute("viewBox", "0 0 32 32");
  svg.setAttribute("width", String(Math.round(size * 0.62)));
  svg.setAttribute("height", String(Math.round(size * 0.62)));
  svg.setAttribute("aria-hidden", "true");
  svg.innerHTML = '<path d="M7 25c6 0 4-9 9-9s3-9 9-9" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round"/><circle cx="7" cy="25" r="3" fill="#fbdfb0"/><circle cx="25" cy="7" r="3" fill="#fbdfb0"/>';
  box.append(svg);
  return box;
}

function lineOf(group) {
  return LINES[group] || HOME_LINE;
}

function lineStyle(line) {
  return `--line:${line.color};--line-soft:${line.soft}`;
}

function toast(message, isError) {
  const box = document.getElementById("toast");
  box.replaceChildren(icon(isError ? "alert" : "ok", 18), el("span", { text: message }));
  box.className = isError ? "toast error" : "toast";
  box.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { box.hidden = true; }, isError ? 6500 : 3200);
}

async function api(method, url, body) {
  const options = { method, credentials: "same-origin", headers: { "X-Admin-Request": "1" } };
  if (body !== undefined) {
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }
  const response = await fetch(url, options);
  let data = null;
  try { data = await response.json(); } catch (error) { data = null; }
  if (response.status === 401 && !url.endsWith("/login")) {
    state.user = null;
    renderLogin();
    throw new Error("Prihláste sa znova.");
  }
  if (!response.ok) {
    const detail = data && data.detail;
    const message = Array.isArray(detail) ? detail.map((item) => item.msg).join(", ") : detail;
    throw new Error(message || `Chyba ${response.status}`);
  }
  return data;
}

function confirmDialog(title, text, confirmLabel, options = {}) {
  const tone = options.tone || "danger";
  const iconName = options.iconName || (tone === "danger" ? "trash" : "check");
  return new Promise((resolve) => {
    const close = (result) => {
      document.removeEventListener("keydown", onKey);
      backdrop.remove();
      resolve(result);
    };
    const onKey = (event) => { if (event.key === "Escape") close(false); };
    const confirm = el("button", { class: tone === "danger" ? "btn danger solid" : "btn primary", type: "button", onclick: () => close(true) }, [icon(iconName, 16), confirmLabel]);
    const backdrop = el("div", { class: "modal-backdrop", onclick: (event) => { if (event.target === backdrop) close(false); } }, [
      el("div", { class: "modal", role: "dialog", "aria-modal": "true" }, [
        el("div", { class: `modal-icon tone-${tone}` }, icon(tone === "danger" ? "alert" : iconName, 22)),
        el("h3", { text: title }),
        el("p", { text }),
        el("div", { class: "modal-actions" }, [el("button", { class: "btn", type: "button", text: "Zrušiť", onclick: () => close(false) }), confirm]),
      ]),
    ]);
    document.addEventListener("keydown", onKey);
    document.body.append(backdrop);
    confirm.focus();
  });
}

function choiceLabel(column, value) {
  return (column.choice_labels && column.choice_labels[value]) || value;
}

function toneOf(column, value) {
  if (TONES[value]) return TONES[value];
  if (column.name === "service_bucket") return "blue";
  if (column.name === "kind") return "ochre";
  return "neutral";
}

function formatDate(value) {
  const date = new Date(`${String(value).slice(0, 10)}T00:00:00`);
  return date.toLocaleDateString("sk-SK", { day: "numeric", month: "numeric", year: "numeric" });
}

function formatDateTime(value) {
  return new Date(value).toLocaleString("sk-SK", { day: "numeric", month: "numeric", year: "numeric", hour: "2-digit", minute: "2-digit" });
}

function formatValue(column, value, labels) {
  if (value === null || value === undefined || value === "") {
    return column.empty_text ? el("span", { class: "nil-text", text: column.empty_text }) : el("span", { class: "nil", text: "—" });
  }
  if (column.kind === "bool") return el("span", { class: `tag ${value ? "tone-green" : "tone-muted"}`, text: value ? "áno" : "nie" });
  if (column.kind === "choice") return el("span", { class: `tag tone-${toneOf(column, value)}`, text: choiceLabel(column, value) });
  if (column.kind === "ref") {
    const label = labels && labels[column.name] && labels[column.name][String(value)];
    return el("span", { class: "ref", text: label || `#${value}` });
  }
  if (column.kind === "datetime") return formatDateTime(value);
  if (column.kind === "date") return formatDate(value);
  if (column.kind === "float") return numberFormat.format(Number(value));
  if (column.kind === "json") return el("code", { class: "json", text: JSON.stringify(value).slice(0, 80) });
  const text = String(value);
  return text.length > 90 ? `${text.slice(0, 90)}…` : text;
}

function rowKey(resource, row) {
  return resource.pk.map((name) => row[name]).join("~");
}

function navigate(hash) {
  if (location.hash === hash) route();
  else location.hash = hash;
}

function loading() {
  return el("div", { class: "loading" }, [el("span"), el("span"), el("span"), el("em", { text: "Načítavam…" })]);
}

function navigation(active) {
  const rail = el("nav", { class: "rail", "aria-label": "Navigácia" }, [
    el("div", { class: "brand" }, [brandMark(), el("div", {}, [el("strong", { text: "Smart Tourism" }), el("span", { text: "správa obsahu" })])]),
    el("a", { class: `home-link${active === "dashboard" ? " active" : ""}`, href: "#/" }, [icon("home", 18), "Prehľad"]),
  ]);
  const groups = new Map();
  for (const resource of state.meta) {
    if (!groups.has(resource.group)) groups.set(resource.group, []);
    groups.get(resource.group).push(resource);
  }
  const stop = (href, label, isActive) => el("li", {}, el("a", {
    class: `stop${isActive ? " active" : ""}`,
    href,
    "aria-current": isActive ? "page" : null,
  }, [el("span", { class: "dot" }), el("span", { text: label })]));
  for (const [group, resources] of groups) {
    const line = lineOf(group);
    const stops = resources.map((resource) => stop(`#/r/${resource.name}`, resource.label, resource.name === active));
    if (group === PIPELINE_GROUP) stops.unshift(stop("#/data", "Kontrola a schválenie", active === "data"));
    rail.append(el("section", { class: "line", style: lineStyle(line) }, [
      el("div", { class: "line-head" }, [el("span", { class: "line-badge" }, icon(line.icon, 15)), group]),
      el("ol", { class: "stops" }, stops),
    ]));
  }
  return rail;
}

async function logout() {
  try {
    await api("POST", "/admin/api/logout");
  } catch (error) {
    state.user = null;
  }
  state.user = null;
  renderLogin();
}

function userChip() {
  const user = state.user || {};
  const source = user.display_name || user.email || "S";
  const initials = source.split(/[\s@._-]+/).filter(Boolean).slice(0, 2).map((part) => part[0].toUpperCase()).join("");
  return el("div", { class: "user-chip" }, [
    el("span", { class: "avatar", text: initials || "S" }),
    el("span", { class: "user-name" }, [el("strong", { text: user.display_name || "Správca" }), el("small", { text: user.email || "" })]),
    el("button", { class: "btn icon-only ghost", type: "button", title: "Odhlásiť sa", "aria-label": "Odhlásiť sa", onclick: logout }, icon("logout", 18)),
  ]);
}

function shell({ title, kicker, line, subtitle, content, active, actions }) {
  const colors = line || HOME_LINE;
  const page = el("div", { class: "page" }, content);
  const main = el("main", { class: "main", style: lineStyle(colors) }, [
    el("button", { class: "btn icon-only menu-toggle", type: "button", "aria-label": "Zobraziť navigáciu", onclick: () => document.body.classList.toggle("nav-open") }, icon("menu", 18)),
    el("header", { class: "topbar" }, [
      el("div", { class: "heading" }, [
        el("div", { class: "kicker" }, [el("span", { class: "pip" }), kicker]),
        el("h1", { class: "title", text: title }),
        subtitle ? el("p", { class: "subtitle" }, subtitle) : null,
      ]),
      el("div", { class: "top-actions" }, [...(actions || []).filter(Boolean), userChip()]),
    ]),
    page,
  ]);
  app.replaceChildren(el("div", { class: "app" }, [navigation(active), main, el("div", { class: "scrim", onclick: () => document.body.classList.remove("nav-open") })]));
  document.body.classList.remove("nav-open");
  return page;
}

function renderLogin() {
  const error = el("div", { class: "login-error", role: "alert" });
  const email = el("input", { type: "email", autocomplete: "username", required: true, id: "login-email" });
  const password = el("input", { type: "password", autocomplete: "current-password", required: true, id: "login-password" });
  const reveal = el("button", { class: "btn icon-only ghost", type: "button", "aria-label": "Zobraziť heslo", onclick: () => {
    password.type = password.type === "password" ? "text" : "password";
  } }, icon("eye", 18));
  const submit = el("button", { class: "btn primary", type: "submit" }, ["Prihlásiť sa", icon("next", 18)]);
  const form = el("form", { class: "login-card" }, [
    el("div", { class: "kicker", style: lineStyle(HOME_LINE) }, [el("span", { class: "pip" }), "Správa systému"]),
    el("h2", { text: "Vitajte späť" }),
    el("p", { class: "lead", text: "Prihláste sa účtom s oprávnením správcu." }),
    el("div", { class: "field" }, [el("label", { for: "login-email", text: "E-mail" }), email]),
    el("div", { class: "field" }, [el("label", { for: "login-password", text: "Heslo" }), el("div", { class: "password-wrap" }, [password, reveal])]),
    error,
    submit,
  ]);
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    error.textContent = "";
    submit.disabled = true;
    try {
      const data = await api("POST", "/admin/api/login", { email: email.value, password: password.value });
      state.user = data.user;
      await loadMeta();
      route();
    } catch (exc) {
      error.textContent = exc.message;
    } finally {
      submit.disabled = false;
    }
  });
  const art = el("section", { class: "login-art" });
  art.innerHTML = CITY_ART;
  art.append(
    el("div", { class: "art-copy" }, [
      el("div", { class: "art-brand" }, [brandMark(38), "Smart Tourism"]),
      el("h1", { text: "Mesto, ktoré turistovi sedí do dňa." }),
      el("p", { text: "Body záujmu, otváracie hodiny, sviatky, školské prázdniny a cestovné poriadky MHD na jednom mieste. Každá zmena sa hneď prejaví v plánovaných trasách." }),
    ]),
    el("div", { class: "art-foot" }, ["Nitra", "Nové Zámky", "Trenčín", "Trnava", "Žilina"].map((city) => el("span", { text: city }))),
  );
  app.replaceChildren(el("div", { class: "login-screen" }, [art, el("section", { class: "login-side" }, form)]));
  email.focus();
}

async function loadMeta() {
  const data = await api("GET", "/admin/api/meta");
  state.meta = data.resources;
  state.resources = Object.fromEntries(data.resources.map((resource) => [resource.name, resource]));
}

function panelHead(iconName, title, hash) {
  return el("div", { class: "panel-head" }, [
    el("h3", {}, [el("span", { class: "ico" }, icon(iconName, 18)), title]),
    hash ? el("a", { href: hash, text: "Spravovať" }) : null,
  ]);
}

function ticket({ tone, iconName, label, value, stub, hash }) {
  return el("a", { class: `ticket tone-${tone}`, href: hash }, [
    el("div", { class: "t-label" }, [icon(iconName, 16), label]),
    el("div", { class: "t-value", text: value === null || value === undefined ? "—" : numberFormat.format(value) }),
    el("div", { class: "t-perf" }),
    el("div", { class: "t-stub", text: stub }),
  ]);
}

function leaf(holiday) {
  const date = new Date(`${holiday.holiday_date}T00:00:00`);
  return el("div", { class: "leaf-wrap" }, [
    el("div", { class: "leaf" }, [
      el("div", { class: "leaf-month", text: MONTHS[date.getMonth()] }),
      el("div", { class: "leaf-day", text: String(date.getDate()) }),
      el("div", { class: "leaf-weekday", text: WEEKDAYS[(date.getDay() + 6) % 7] }),
    ]),
    el("div", { class: "leaf-name", text: holiday.name }),
  ]);
}

function breakRow(period) {
  const range = period.start_date === period.end_date
    ? formatDate(period.start_date)
    : `${formatDate(period.start_date)} – ${formatDate(period.end_date)}`;
  return el("div", { class: "break" }, [
    el("div", {}, [el("strong", { text: period.name }), el("span", { class: "where", text: period.everywhere ? "všetky mestá" : period.cities })]),
    el("span", { class: "range", text: range }),
  ]);
}

function task({ iconName, title, text, controls }) {
  return el("div", { class: "task" }, [
    el("span", { class: "task-icon" }, icon(iconName, 20)),
    el("div", {}, [el("h4", { text: title }), el("p", { text }), el("div", { class: "task-control" }, controls)]),
  ]);
}

async function renderDashboard() {
  const today = new Date().toLocaleDateString("sk-SK", { weekday: "long", day: "numeric", month: "long", year: "numeric" });
  const page = shell({ title: "Prehľad", kicker: today, content: loading(), active: "dashboard" });
  const data = await api("GET", "/admin/api/dashboard");
  const counts = data.counts;
  const run = async (button, method, url, body, message) => {
    button.disabled = true;
    try {
      const result = await api(method, url, body);
      toast(typeof message === "function" ? message(result) : message);
      renderDashboard();
    } catch (exc) {
      toast(exc.message, true);
      button.disabled = false;
    }
  };
  const yearInput = el("input", { type: "number", value: String(new Date().getFullYear() + 1), min: "2000", max: "2100", "aria-label": "Rok" });
  const recompute = el("button", { class: "btn primary small", type: "button" }, [icon("refresh", 16), "Prepočítať"]);
  recompute.addEventListener("click", () => run(recompute, "POST", "/admin/api/feedback/recompute", undefined, "Agregáty spätnej väzby sú prepočítané."));
  const holidays = el("button", { class: "btn small", type: "button" }, [icon("plus", 16), "Doplniť"]);
  holidays.addEventListener("click", () => run(holidays, "POST", "/admin/api/holidays/generate", { year: Number(yearInput.value) },
    (result) => `Doplnené sviatky na rok ${result.year}: ${result.inserted}.`));
  const reload = el("button", { class: "btn small", type: "button" }, [icon("refresh", 16), "Načítať znova"]);
  reload.addEventListener("click", () => run(reload, "POST", "/admin/api/transport/reload", undefined, "Graf MHD, sviatky a prázdniny sa pri ďalšom plánovaní načítajú znova."));
  const maxPois = Math.max(1, ...data.cities.map((city) => city.pois));
  page.replaceChildren(el("div", { class: "dash" }, [
    el("div", { class: "tickets" }, [
      ticket({ tone: "green", iconName: "pin", label: "Body záujmu", value: counts.active_pois, hash: "#/r/pois",
        stub: `${counts.manual_hours} s hodinami od správcu · ${counts.pois_without_hours} bez hodín` }),
      ticket({ tone: "terra", iconName: "route", label: "Trasy návštevníkov", value: counts.sessions, hash: "#/r/route_sessions",
        stub: `${counts.completed_sessions} dokončených` }),
      ticket({ tone: "ochre", iconName: "star", label: "Hodnotenia", value: counts.feedback, hash: "#/r/route_feedback",
        stub: counts.average_rating ? `priemer ${numberFormat.format(counts.average_rating)} z 5` : "zatiaľ bez hodnotení" }),
      ticket({ tone: "blue", iconName: "users", label: "Používatelia", value: counts.users, hash: "#/r/users",
        stub: `${counts.curated_routes} okruhov · ${counts.transport_lines} liniek MHD` }),
    ]),
    el("div", { class: "dash-grid" }, [
      el("section", { class: "panel", style: lineStyle(LINES["Kalendár"]) }, [
        panelHead("calendar", "Kalendár", "#/r/school_holidays"),
        el("h4", { class: "panel-sub", text: "Najbližšie štátne sviatky" }),
        data.holidays.length ? el("div", { class: "leaves" }, data.holidays.map(leaf)) : el("p", { class: "muted", text: "Žiadne nadchádzajúce sviatky." }),
        el("h4", { class: "panel-sub", text: "Školské prázdniny – v pracovné dni platí prázdninový cestovný poriadok" }),
        data.school_holidays.length ? el("div", { class: "breaks" }, data.school_holidays.map(breakRow)) : el("p", { class: "muted", text: "Žiadne nadchádzajúce prázdniny." }),
      ]),
      el("div", { class: "dash-col" }, [
        el("section", { class: "panel", style: lineStyle(LINES["Obsah"]) }, [
          panelHead("city", "Body záujmu podľa miest", "#/r/cities"),
          el("div", { class: "bars" }, data.cities.map((city) => el("div", { class: "bar-row" }, [
            el("span", { text: city.name }),
            el("span", { class: "bar" }, el("span", { style: `width:${Math.round((city.pois / maxPois) * 100)}%` })),
            el("span", { class: "bar-value", text: String(city.pois) }),
          ]))),
        ]),
        el("section", { class: "panel", style: lineStyle(LINES["Doprava"]) }, [
          panelHead("refresh", "Údržba", null),
          el("div", { class: "tasks" }, [
            task({ iconName: "chart", title: "Agregáty spätnej väzby", text: "Prepočíta štatistiky bodov, kategórií a miest z hodnotení trás.", controls: [recompute] }),
            task({ iconName: "calendar", title: "Štátne sviatky", text: "Doplní dni pracovného pokoja podľa zákona č. 241/1993 Z. z.", controls: [yearInput, holidays] }),
            task({ iconName: "bus", title: "Cestovné poriadky", text: "Po zmene liniek, spojov alebo kalendára ich plánovač načíta znova.", controls: [reload] }),
          ]),
        ]),
      ]),
    ]),
  ]));
}

async function loadOptions(refName, query, ids) {
  const params = new URLSearchParams();
  if (query) params.set("q", query);
  if (ids && ids.length) params.set("ids", ids.join(","));
  const data = await api("GET", `/admin/api/options/${refName}?${params}`);
  return data.options;
}

function refField(column, value, label, onChange) {
  let current = value === null || value === undefined ? "" : String(value);
  let currentLabel = label || null;
  const select = el("select", { "aria-label": column.label });
  const searchBox = el("input", { type: "search", placeholder: onChange ? `${column.label}: hľadať…` : "Hľadať…", "aria-label": `${column.label} – hľadať` });
  const fill = (options) => {
    select.replaceChildren(el("option", { value: "", text: onChange ? `${column.label}: všetko` : "– nevybrané –" }));
    const seen = new Set();
    if (current && currentLabel) { select.append(el("option", { value: current, text: currentLabel })); seen.add(current); }
    for (const option of options) {
      const optionValue = String(option.value);
      if (!seen.has(optionValue)) { select.append(el("option", { value: optionValue, text: option.label })); seen.add(optionValue); }
    }
    select.value = current;
    select.classList.toggle("filter-active", Boolean(onChange && current));
  };
  const load = (query) => loadOptions(column.ref, query, []).then(fill).catch(() => {});
  fill([]);
  if (current && !currentLabel) {
    loadOptions(column.ref, "", [current]).then((options) => {
      if (options.length) currentLabel = options[0].label;
      return load("");
    }).catch(() => {});
  } else {
    load("");
  }
  let timer = null;
  searchBox.addEventListener("input", () => {
    clearTimeout(timer);
    timer = setTimeout(() => load(searchBox.value), 300);
  });
  select.addEventListener("change", () => {
    current = select.value;
    const chosen = select.selectedOptions[0];
    currentLabel = current && chosen ? chosen.textContent : null;
    if (onChange) onChange(current);
  });
  return { node: el("div", { class: "ref-field" }, [searchBox, select]), read: () => current };
}

function filterControl(column, current, onChange) {
  if (column.kind === "ref") return refField(column, current, null, onChange).node;
  if (!["bool", "choice", "text", "opening_hours", "longtext"].includes(column.kind)) {
    const input = el("input", { class: `filter-input${current ? " filter-active" : ""}`, type: "search", placeholder: column.label, value: current || "" });
    input.addEventListener("change", () => onChange(input.value));
    return input;
  }
  const select = el("select", { class: current ? "filter-active" : null, "aria-label": column.label, onchange: (event) => onChange(event.target.value) });
  const add = (value, label) => select.append(el("option", { value, text: label }));
  add("", `${column.label}: všetko`);
  if (column.kind === "bool") { add("true", `${column.label}: áno`); add("false", `${column.label}: nie`); }
  else if (column.kind === "choice") column.choices.forEach((choice) => add(choice, choiceLabel(column, choice)));
  else { add("set", `${column.label}: vyplnené`); add("empty", `${column.label}: prázdne`); }
  select.value = current || "";
  return select;
}

function countLabel(total) {
  if (total === 1) return "1 záznam";
  if (total >= 2 && total <= 4) return `${total} záznamy`;
  return `${numberFormat.format(total)} záznamov`;
}

async function renderList(name, params) {
  const resource = state.resources[name];
  if (!resource) { navigate("#/"); return; }
  const line = lineOf(resource.group);
  const query = { q: params.get("q") || "", sort: params.get("sort") || "", desc: params.get("desc"), page: Number(params.get("page") || 1) };
  const filters = {};
  for (const [key, value] of params.entries()) if (key.startsWith("f_")) filters[key.slice(2)] = value;
  const update = (changes) => {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value === "" || value === null || value === undefined) next.delete(key); else next.set(key, value);
    }
    if (!("page" in changes)) next.delete("page");
    navigate(`#/r/${name}?${next}`);
  };
  const columns = resource.columns.filter((column) => column.in_list && column.kind !== "password");
  const tbody = el("tbody", {}, el("tr", {}, el("td", { colspan: String(columns.length) }, loading())));
  const pager = el("div", { class: "pager" });
  const count = el("span", { text: "Načítavam záznamy…" });
  const searchInput = el("input", { type: "search", placeholder: "Hľadať…", value: query.q, "aria-label": "Hľadať" });
  searchInput.addEventListener("keydown", (event) => { if (event.key === "Enter") update({ q: searchInput.value }); });
  const toolbar = el("div", { class: "toolbar" }, [
    resource.search ? el("label", { class: "search" }, [icon("search", 17), searchInput]) : null,
    ...resource.filters.map((filterName) => {
      const column = resource.columns.find((item) => item.name === filterName);
      return filterControl(column, filters[filterName], (value) => update({ [`f_${filterName}`]: value }));
    }),
  ]);
  const header = el("tr", {}, columns.map((column) => {
    const sorted = query.sort === column.name;
    return el("th", {
      class: [sorted ? "sorted" : "", NUMERIC_KINDS.has(column.kind) ? "num" : ""].join(" ").trim() || null,
      scope: "col",
      onclick: () => update({ sort: column.name, desc: sorted && query.desc !== "true" ? "true" : "false" }),
    }, [column.label, sorted ? el("span", { class: "arrow", text: query.desc === "true" ? "↓" : "↑" }) : null]);
  }));
  const addButton = resource.can_create
    ? el("button", { class: "btn primary", type: "button", onclick: () => navigate(`#/r/${name}/new`) }, [icon("plus", 18), "Pridať"])
    : null;
  shell({
    title: resource.label,
    kicker: resource.group,
    line,
    subtitle: count,
    active: name,
    actions: [addButton],
    content: el("div", { class: "sheet" }, [
      resource.search || resource.filters.length ? toolbar : null,
      el("div", { class: "table-wrap" }, el("table", { class: "grid" }, [el("thead", {}, header), tbody])),
      pager,
    ]),
  });
  const apiParams = new URLSearchParams({ page: String(query.page), page_size: String(PAGE_SIZE) });
  if (query.q) apiParams.set("q", query.q);
  if (query.sort) { apiParams.set("sort", query.sort); apiParams.set("desc", query.desc === "true" ? "true" : "false"); }
  for (const [key, value] of Object.entries(filters)) apiParams.set(`f_${key}`, value);
  const data = await api("GET", `/admin/api/r/${name}?${apiParams}`);
  const filtered = Object.keys(filters).length > 0 || Boolean(query.q);
  count.textContent = `${countLabel(data.total)}${filtered ? " podľa filtra" : ""}`;
  if (!data.rows.length) {
    tbody.replaceChildren(el("tr", {}, el("td", { colspan: String(columns.length) }, el("div", { class: "empty-state" }, [icon("inbox", 40), "Žiadne záznamy nezodpovedajú výberu."]))));
  } else {
    tbody.replaceChildren(...data.rows.map((row) => el("tr", {
      onclick: () => navigate(`#/r/${name}/${encodeURIComponent(rowKey(resource, row))}`),
    }, columns.map((column) => el("td", {
      class: [column.name === resource.title ? "cell-title" : "", NUMERIC_KINDS.has(column.kind) ? "num" : ""].join(" ").trim() || null,
    }, formatValue(column, row[column.name], data.labels))))));
  }
  const pages = Math.max(1, Math.ceil(data.total / data.page_size));
  const first = data.total ? (data.page - 1) * data.page_size + 1 : 0;
  const last = Math.min(data.total, data.page * data.page_size);
  pager.replaceChildren(
    el("span", { text: `${first}–${last} z ${numberFormat.format(data.total)}` }),
    el("div", { class: "pages" }, [
      el("button", { class: "btn small", type: "button", disabled: data.page <= 1, onclick: () => update({ page: String(data.page - 1) }) }, [icon("back", 16), "Predchádzajúca"]),
      el("button", { class: "btn small", type: "button", disabled: data.page >= pages, onclick: () => update({ page: String(data.page + 1) }) }, ["Ďalšia", icon("next", 16)]),
    ]),
  );
}

function openingHoursField(value, osmValue) {
  const input = el("input", { type: "text", value: value || "", placeholder: "napr. Mo-Fr 09:00-17:00; Sa 10:00-14:00", spellcheck: "false" });
  const preview = el("div", { class: "week" });
  let timer = null;
  const dayRow = (day) => {
    const date = new Date(`${day.date}T00:00:00`);
    const track = el("div", { class: "track" }, day.intervals.map(([start, end]) => el("span", {
      class: "seg",
      style: `left:${(start / 1440) * 100}%;width:${((Math.min(end, 1440) - start) / 1440) * 100}%`,
    })));
    return el("div", { class: "day-row" }, [
      el("span", { class: "day-name" }, [day.day.slice(0, 2), el("small", { text: `${date.getDate()}. ${date.getMonth() + 1}.` })]),
      track,
      el("span", { class: `day-hours${day.intervals.length ? "" : " closed"}`, text: day.hours }),
    ]);
  };
  const scale = () => el("div", { class: "week-scale" }, [el("span"), el("div", { class: "ticks" }, ["0", "6", "12", "18", "24"].map((hour) => el("span", { text: hour }))), el("span")]);
  const show = (message, valid, week) => {
    preview.replaceChildren(...[
      el("div", { class: `week-message${valid ? "" : " invalid"}` }, [icon(valid ? "clock" : "alert", 16), message]),
      ...(week || []).map(dayRow),
      week && week.length ? scale() : null,
    ].filter(Boolean));
  };
  const check = async () => {
    const typed = input.value.trim();
    if (!typed && !osmValue) {
      show("Bod nemá otváracie hodiny, plánovač ho považuje za prístupný nonstop.", true, []);
      return;
    }
    try {
      const result = await api("POST", "/admin/api/opening-hours/check", { value: typed || osmValue });
      if (typed) show(result.message, result.valid, result.valid ? result.week : []);
      else if (result.valid) show("Pole je prázdne, plánovač použije hodiny z OSM:", true, result.week);
      else show("Hodiny z OSM sa nepodarilo rozpoznať, plánovač preto bod považuje za prístupný nonstop.", true, []);
    } catch (exc) {
      show(exc.message, false, []);
    }
  };
  input.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(check, 350); });
  const preset = (label, text) => el("button", { class: "chip", type: "button", text: label, onclick: () => { input.value = text; check(); } });
  check();
  return {
    node: el("div", { style: "display:grid;gap:10px" }, [
      input,
      el("div", { class: "hours-presets" }, [
        preset("Nonstop", "24/7"),
        preset("Po – Pi 9:00 – 17:00", "Mo-Fr 09:00-17:00"),
        preset("Ut – Ne 10:00 – 18:00", "Tu-Su 10:00-18:00"),
        preset("Zatvorené", "Mo-Su off"),
        preset("Podľa OSM", ""),
      ]),
      preview,
    ]),
    read: () => input.value,
  };
}

function inputField(column, value) {
  if (column.kind === "bool") {
    const box = el("input", { type: "checkbox", class: "switch", checked: Boolean(value), "aria-label": column.label });
    const caption = el("span", { text: value ? "áno" : "nie" });
    box.addEventListener("change", () => { caption.textContent = box.checked ? "áno" : "nie"; });
    return { node: el("label", { class: "switch-row" }, [box, caption]), read: () => box.checked };
  }
  if (column.kind === "longtext") {
    const area = el("textarea", { value: value || "" });
    return { node: area, read: () => area.value };
  }
  if (column.kind === "choice") {
    const select = el("select", {}, [el("option", { value: "", text: "– nevybrané –" }), ...column.choices.map((choice) => el("option", { value: choice, text: choiceLabel(column, choice) }))]);
    select.value = value || "";
    return { node: select, read: () => select.value };
  }
  if (column.kind === "password") {
    const input = el("input", { type: "password", autocomplete: "new-password" });
    return { node: input, read: () => input.value };
  }
  const types = { int: "number", float: "number", date: "date", datetime: "datetime-local" };
  const shown = column.kind === "datetime" && value ? String(value).slice(0, 16) : value;
  const input = el("input", {
    type: types[column.kind] || "text",
    step: column.kind === "float" ? "any" : null,
    value: shown === null || shown === undefined ? "" : String(shown),
  });
  return { node: input, read: () => input.value };
}

function sectionHead(title, extra) {
  return el("div", { class: "section-head" }, [el("h2", { text: title }), el("span", { class: "rule" }), extra || null]);
}

async function renderForm(name, key) {
  const resource = state.resources[name];
  if (!resource) { navigate("#/"); return; }
  const line = lineOf(resource.group);
  const creating = key === "new";
  if (creating && !resource.can_create) { navigate(`#/r/${name}`); return; }
  shell({ title: resource.label, kicker: resource.group, line, active: name, content: loading() });
  let row = {};
  let labels = {};
  if (creating) {
    for (const column of resource.columns) {
      if (column.default !== null && column.default !== undefined) row[column.name] = column.default;
    }
  } else {
    const data = await api("GET", `/admin/api/r/${name}/${encodeURIComponent(key)}`);
    row = data.row;
    labels = data.labels;
  }
  const editable = creating || resource.can_edit;
  const readers = {};
  const sections = new Map();
  for (const column of resource.columns) {
    if (creating && !column.editable && column.kind !== "password") continue;
    let control;
    if (column.kind === "json") {
      control = el("pre", { class: "json-block", text: row[column.name] ? JSON.stringify(row[column.name], null, 2) : "—" });
    } else if (!column.editable || !editable) {
      control = el("div", { class: "readonly-value" }, formatValue(column, row[column.name], labels));
    } else if (column.kind === "ref") {
      const field = refField(column, row[column.name], labels[column.name] && labels[column.name][String(row[column.name])], null);
      readers[column.name] = field.read;
      control = field.node;
    } else if (column.kind === "opening_hours") {
      const field = openingHoursField(row[column.name], row.opening_hours_raw);
      readers[column.name] = field.read;
      control = field.node;
    } else {
      const field = inputField(column, row[column.name]);
      readers[column.name] = field.read;
      control = field.node;
    }
    const node = el("div", { class: `field${WIDE_KINDS.has(column.kind) ? " wide" : ""}` }, [
      el("label", {}, [column.label, column.required && column.editable ? el("span", { class: "required", text: "*" }) : null]),
      control,
      column.help ? el("p", { class: "help", text: column.help }) : null,
    ]);
    const section = column.section || "Údaje";
    if (!sections.has(section)) sections.set(section, []);
    sections.get(section).push(node);
  }
  const save = el("button", { class: "btn primary", type: "button" }, [icon("check", 18), creating ? "Vytvoriť" : "Uložiť zmeny"]);
  save.addEventListener("click", async () => {
    const payload = {};
    for (const [columnName, read] of Object.entries(readers)) {
      const column = resource.columns.find((item) => item.name === columnName);
      const value = read();
      if (column.kind === "password" && !value) continue;
      payload[columnName] = value;
    }
    save.disabled = true;
    try {
      if (creating) {
        const result = await api("POST", `/admin/api/r/${name}`, payload);
        toast("Záznam bol vytvorený.");
        navigate(`#/r/${name}/${encodeURIComponent(result.key)}`);
      } else {
        await api("PATCH", `/admin/api/r/${name}/${encodeURIComponent(key)}`, payload);
        toast("Zmeny boli uložené.");
        renderForm(name, key);
      }
    } catch (exc) {
      toast(exc.message, true);
    } finally {
      save.disabled = false;
    }
  });
  const remove = el("button", { class: "btn danger", type: "button" }, [icon("trash", 17), "Vymazať"]);
  remove.addEventListener("click", async () => {
    if (!(await confirmDialog("Vymazať záznam?", "Záznam sa natrvalo odstráni a túto akciu nemožno vrátiť.", "Vymazať"))) return;
    try {
      await api("DELETE", `/admin/api/r/${name}/${encodeURIComponent(key)}`);
      toast("Záznam bol vymazaný.");
      navigate(`#/r/${name}`);
    } catch (exc) {
      toast(exc.message, true);
    }
  });
  const hasMap = !creating && row.lat !== undefined && row.lat !== null && row.lon !== undefined && row.lon !== null;
  const mapLink = hasMap
    ? el("a", { href: `https://www.openstreetmap.org/?mlat=${row.lat}&mlon=${row.lon}#map=17/${row.lat}/${row.lon}`, target: "_blank", rel: "noopener" }, [icon("map", 15), "Otvoriť na mape"])
    : null;
  const recordTitle = creating ? "Nový záznam" : (resource.title && row[resource.title]) || `${resource.label} #${key}`;
  shell({
    title: recordTitle,
    kicker: resource.label,
    line,
    active: name,
    subtitle: creating ? "Vyplňte povinné polia označené hviezdičkou." : `Záznam #${key.split("~").join(" · ")}`,
    content: el("div", { class: "form" }, [
      ...[...sections.entries()].map(([title, nodes]) => el("section", { class: "section" }, [
        sectionHead(title, title === "Poloha" ? mapLink : null),
        el("div", { class: "fields" }, nodes),
      ])),
      el("div", { class: "actionbar" }, [
        el("button", { class: "btn ghost", type: "button", onclick: () => navigate(`#/r/${name}`) }, [icon("back", 17), "Späť"]),
        editable ? save : null,
        !creating && resource.can_delete ? remove : null,
      ]),
    ]),
  });
}

function choiceLabelsOf(resourceName, columnName) {
  const resource = state.resources[resourceName];
  const column = resource && resource.columns.find((item) => item.name === columnName);
  return (column && column.choice_labels) || {};
}

function approvalTag(approval) {
  if (approval.status === "approved") return el("span", { class: "tag tone-green", text: "Schválené" });
  if (approval.status === "changed") return el("span", { class: "tag tone-terra", text: "Zmenené po schválení" });
  return el("span", { class: "tag tone-ochre", text: "Čaká na schválenie" });
}

function loadTag(data) {
  if (data.load.in_sync === true) return el("span", { class: "tag tone-blue", text: "V databáze" });
  if (data.approval.status === "approved") return el("span", { class: "tag tone-ochre", text: "Čaká na načítanie" });
  if (data.load.in_sync === false) return el("span", { class: "tag tone-muted", text: "V databáze je iná verzia" });
  return el("span", { class: "tag tone-muted", text: "Načítanie nezaznamenané" });
}

function qualityChip(value, label, critical) {
  return el("span", { class: `q-chip${critical && value ? " bad" : ""}` }, [label, el("b", { text: numberFormat.format(value || 0) })]);
}

function staleNote(text) {
  return el("p", { class: "stale" }, [icon("alert", 15), text]);
}

function issueList(title, items) {
  return el("details", { class: "issues" }, [el("summary", { text: `${title} (${items.length})` }), el("ul", {}, items.map((text) => el("li", { text })))]);
}

function trail(data) {
  const approval = data.approval;
  const lines = [
    approval.status === "missing"
      ? el("p", { class: "trail" }, [icon("shield", 15), "Dáta zatiaľ nikto neschválil."])
      : el("p", { class: "trail" }, [icon("shield", 15), `${approval.status === "approved" ? "Schválil" : "Predchádzajúcu verziu schválil"} ${approval.reviewer} · ${formatDateTime(approval.approved_at)}`]),
  ];
  if (data.load.loaded_at) {
    lines.push(el("p", { class: "trail" }, [icon("download", 15), `Do databázy načítal ${data.load.loaded_by || "príkazový riadok"} · ${formatDateTime(data.load.loaded_at)}`]));
  }
  return lines;
}

function datasetActions(city, dataset, data, refresh) {
  const reportCurrent = dataset === "transport" ? data.report_current : data.review_current;
  const canApprove = reportCurrent && data.approval.status !== "approved";
  const canLoad = data.approval.status === "approved" && data.load.in_sync !== true;
  const check = el("button", { class: `btn small${reportCurrent ? "" : " primary"}`, type: "button" }, [icon("search", 16), "Skontrolovať"]);
  const approve = el("button", { class: `btn small${canApprove ? " primary" : ""}`, type: "button", disabled: !canApprove },
    [icon("shield", 16), data.approval.status === "approved" ? "Schválené" : "Schváliť"]);
  const load = el("button", { class: `btn small${canLoad ? " primary" : ""}`, type: "button", disabled: !canLoad },
    [icon("download", 16), data.load.in_sync === true ? "Načítané" : "Načítať do databázy"]);
  const run = async (button, action, body, message) => {
    [check, approve, load].forEach((item) => { item.disabled = true; });
    button.classList.add("working");
    try {
      const response = await api("POST", `/admin/api/pipeline/${city.slug}/${dataset}/${action}`, body);
      toast(message(response.result));
    } catch (exc) {
      toast(exc.message, true);
    }
    refresh();
  };
  const what = dataset === "transport" ? "cestovné poriadky" : "body záujmu";
  check.addEventListener("click", () => run(check, "check", {}, () => "Kontrola je hotová, report je aktuálny."));
  approve.addEventListener("click", async () => {
    let allowErrors = false;
    if (dataset === "pois" && data.review && data.review.errors) {
      const accepted = await confirmDialog("Schváliť aj napriek chybám?",
        `Kontrola našla ${data.review.errors} chýb. Ak dáta schválite, body s chybami sa načítajú tak, ako sú.`, "Schváliť aj tak");
      if (!accepted) return;
      allowErrors = true;
    } else {
      const accepted = await confirmDialog(`Schváliť ${what} – ${city.name}?`,
        "Potvrdzujete, že ste report skontrolovali. Schválenie sa uloží s vaším menom a platí len pre súčasný obsah súboru.",
        "Schváliť", { tone: "primary", iconName: "shield" });
      if (!accepted) return;
    }
    run(approve, "approve", { allow_errors: allowErrors }, (result) => `Schválené na meno ${result.reviewer}.`);
  });
  load.addEventListener("click", async () => {
    const text = dataset === "transport"
      ? `Cestovné poriadky mesta ${city.name} sa v databáze nahradia schválenými dátami, a to vrátane ručných úprav v sekcii Doprava. Plánovač ich začne používať hneď.`
      : `Body záujmu mesta ${city.name} sa doplnia a aktualizujú podľa schváleného súboru. Body chránené pred importom a otváracie hodiny od správcu zostanú bez zmeny.`;
    if (!(await confirmDialog("Načítať do databázy?", text, "Načítať", { tone: "primary", iconName: "download" }))) return;
    run(load, "load", {}, (result) => (dataset === "transport"
      ? `Načítané: ${numberFormat.format(result.lines)} liniek, ${numberFormat.format(result.trips)} spojov a ${numberFormat.format(result.stops)} zastávok.`
      : `Načítané: ${result.inserted} nových a ${result.updated} aktualizovaných bodov, ${result.kept} chránených ponechaných.`));
  });
  return el("div", { class: "dataset-actions" }, [check, approve, load]);
}

function datasetHead(iconName, title, tags) {
  return el("header", { class: "dataset-head" }, [el("h4", {}, [icon(iconName, 18), title]), el("div", { class: "tags" }, tags)]);
}

function transportBlock(city, data, refresh) {
  if (!data) {
    return el("article", { class: "dataset" }, [datasetHead("bus", "Cestovné poriadky MHD", []), el("p", { class: "muted", text: "Graf MHD pre toto mesto ešte nebol spracovaný." })]);
  }
  const labels = choiceLabelsOf("transport_trips", "service_bucket");
  const order = Object.keys(labels);
  const rank = (key) => (order.indexOf(key) === -1 ? 99 : order.indexOf(key));
  const keys = [...new Set([...Object.keys(data.database.buckets), ...Object.keys(data.buckets)])].sort((a, b) => rank(a) - rank(b));
  const quality = data.quality;
  const number = (value) => numberFormat.format(value || 0);
  return el("article", { class: "dataset" }, [
    datasetHead("bus", "Cestovné poriadky MHD", [approvalTag(data.approval), loadTag(data)]),
    el("p", { class: "dataset-meta", text: `Spracované ${formatDateTime(data.generated_at)} · ${number(data.lines)} liniek · ${number(data.trips)} spojov · ${number(data.stops)} zastávok` }),
    quality ? el("div", { class: "quality" }, [
      qualityChip(quality.validation_warning_count, "Zistenia kontroly", true),
      qualityChip(quality.invalid_trip_count, "Neplatné spoje", true),
      qualityChip(quality.unmatched_stop_count, "Nespárované zastávky", true),
      qualityChip(quality.warnings_count, "Upozornenia pri spracovaní", false),
      el("span", { class: "q-chip" }, ["Zastávky spárované s mapou", el("b", { text: `${Math.round((quality.coverage_ratio || 0) * 100)} %` })]),
    ]) : null,
    data.report_current ? null : staleNote("Report kontroly je starší ako dáta. Pred schválením spustite kontrolu."),
    el("table", { class: "compare" }, [
      el("thead", {}, el("tr", {}, [el("th", { text: "Typ dní" }), el("th", { class: "num", text: "V databáze" }), el("th", { class: "num", text: "V súbore" })])),
      el("tbody", {}, keys.map((key) => {
        const stored = data.database.buckets[key] || 0;
        const incoming = data.buckets[key] || 0;
        return el("tr", { class: stored === incoming ? null : "diff" }, [
          el("td", { text: labels[key] || key }),
          el("td", { class: "num", text: number(stored) }),
          el("td", { class: "num", text: number(incoming) }),
        ]);
      })),
      el("tfoot", {}, el("tr", {}, [el("td", { text: "Spoje spolu" }), el("td", { class: "num", text: number(data.database.trips) }), el("td", { class: "num", text: number(data.trips) })])),
    ]),
    data.issues.length ? issueList("Zistenia kontroly", data.issues.map((item) => `${item.code}: ${item.message}`)) : null,
    ...trail(data),
    datasetActions(city, "transport", data, refresh),
  ]);
}

function issueText(item) {
  return `${ISSUE_LABELS[item.code] || item.code}: ${item.name || item.key} (${item.key})`;
}

function poiBlock(city, data, refresh) {
  if (!data) {
    return el("article", { class: "dataset" }, [datasetHead("pin", "Body záujmu", []), el("p", { class: "muted", text: "Body záujmu pre toto mesto ešte neboli spracované." })]);
  }
  const categories = choiceLabelsOf("pois", "category");
  const review = data.review;
  const locked = data.database.locked ? ` · ${numberFormat.format(data.database.locked)} chránených pred importom` : "";
  return el("article", { class: "dataset" }, [
    datasetHead("pin", "Body záujmu", [approvalTag(data.approval), loadTag(data)]),
    el("p", { class: "dataset-meta", text: `V súbore ${numberFormat.format(data.active)} aktívnych bodov · v databáze ${numberFormat.format(data.database.active)}${locked}` }),
    el("div", { class: "quality" }, Object.entries(data.by_category).map(([key, value]) => el("span", { class: "q-chip" }, [categories[key] || key, el("b", { text: numberFormat.format(value) })]))),
    review ? el("div", { class: "quality" }, [
      qualityChip(review.errors, "Chyby", true),
      ...Object.entries(review.warning_codes).map(([code, value]) => qualityChip(value, ISSUE_LABELS[code] || code, false)),
    ]) : el("p", { class: "muted", text: "Kontrola ešte nebola spustená." }),
    data.review_current ? null : staleNote("Kontrola je staršia ako dáta. Pred schválením ju spustite znova."),
    review && review.changes ? el("p", { class: "dataset-meta", text: `Od posledného schválenia: ${review.changes.added} nových, ${review.changes.removed} odstránených a ${review.changes.changed} zmenených bodov.` }) : null,
    review && review.error_items.length ? issueList("Chyby", review.error_items.map(issueText)) : null,
    review && review.warning_items.length ? issueList("Upozornenia", review.warning_items.map(issueText)) : null,
    ...trail(data),
    datasetActions(city, "pois", data, refresh),
  ]);
}

async function renderData() {
  const scroll = window.scrollY;
  const page = shell({
    title: "Kontrola a schválenie dát",
    kicker: PIPELINE_GROUP,
    line: lineOf(PIPELINE_GROUP),
    subtitle: "Spracované dáta sa do databázy dostanú až po tom, čo ich správca skontroluje a schváli. Schválenie platí len pre konkrétny obsah súboru.",
    active: "data",
    content: loading(),
  });
  const data = await api("GET", "/admin/api/pipeline");
  if (!data.available) {
    page.replaceChildren(el("section", { class: "panel" }, el("p", { class: "muted", text: data.message })));
    return;
  }
  const refresh = () => { renderData().catch((exc) => toast(exc.message, true)); };
  page.replaceChildren(el("div", { class: "data-cities" }, data.cities.map((city) => el("section", { class: "panel city-data" }, [
    el("div", { class: "panel-head" }, [el("h3", {}, [el("span", { class: "ico" }, icon("city", 18)), city.name]), el("span", { class: "slug", text: city.slug })]),
    el("div", { class: "dataset-grid" }, [transportBlock(city, city.transport, refresh), poiBlock(city, city.pois, refresh)]),
  ]))));
  window.scrollTo(0, scroll);
}

async function route() {
  if (!state.user) return;
  const hash = location.hash.slice(1) || "/";
  const [path, queryString] = hash.split("?");
  const params = new URLSearchParams(queryString || "");
  const parts = path.split("/").filter(Boolean);
  try {
    if (parts[0] === "r" && parts.length === 2) await renderList(parts[1], params);
    else if (parts[0] === "r" && parts.length === 3) await renderForm(parts[1], decodeURIComponent(parts[2]));
    else if (parts[0] === "data") await renderData();
    else await renderDashboard();
  } catch (exc) {
    if (state.user) toast(exc.message, true);
  }
}

window.addEventListener("hashchange", route);

(async () => {
  try {
    const data = await api("GET", "/admin/api/me");
    state.user = data.user;
    await loadMeta();
    route();
  } catch (exc) {
    renderLogin();
  }
})();
