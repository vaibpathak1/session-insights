import * as SessionInsights from "@session-insights/sdk";

// `?siteKey=` overrides the configured key (demo only: the e2e wrong-key test uses it)
const siteKey =
  new URLSearchParams(location.search).get("siteKey") ??
  (import.meta.env.VITE_SI_SITE_KEY as string | undefined);
const collectorUrl =
  (import.meta.env.VITE_SI_COLLECTOR_URL as string | undefined) ??
  "http://localhost:8081";
const $ = <T extends HTMLElement>(id: string) =>
  document.getElementById(id) as T;

if (siteKey) {
  SessionInsights.init({
    siteKey,
    collectorUrl,
    flushIntervalMs: 2000,
    debug: true,
  });
  $("sdk-status").textContent = `recording to ${collectorUrl}`;
} else {
  $("sdk-status").textContent = "no site key: run scripts/dev-site-key.sh";
}
$("session-id").textContent = SessionInsights.getSessionId() ?? "not started";

// Exposed for the e2e test and for poking around in the console.
(
  window as unknown as { SessionInsights: typeof SessionInsights }
).SessionInsights = SessionInsights;

// --- SPA-style navigation (History API) ---
function render(path: string): void {
  document.querySelectorAll<HTMLElement>("[data-page]").forEach((section) => {
    section.hidden = section.dataset.page !== path;
  });
}
document.addEventListener("click", (event) => {
  const link = (event.target as Element).closest<HTMLAnchorElement>(
    "a[data-route]",
  );
  if (!link) return;
  event.preventDefault();
  history.pushState({}, "", link.pathname);
  render(link.pathname);
});
window.addEventListener("popstate", () => render(location.pathname));
render(location.pathname);

// --- error buttons ---
$("throw-error").addEventListener("click", () => {
  throw new Error("Demo exception from the throw button");
});
$("console-error").addEventListener("click", () => {
  console.error("Demo console error", { code: 42 });
});
$("reject-promise").addEventListener("click", () => {
  void Promise.reject(new Error("Demo unhandled rejection"));
});

// --- forms: never really submitted ---
$("toggle-password").addEventListener("click", () => {
  const input = $<HTMLInputElement>("password");
  input.type = input.type === "password" ? "text" : "password";
});
for (const id of ["login-form", "checkout-form"]) {
  $<HTMLFormElement>(id).addEventListener("submit", (event) => {
    event.preventDefault();
    history.pushState({}, "", "/");
    render("/");
  });
}
