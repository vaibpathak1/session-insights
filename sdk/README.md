# @session-insights/sdk

Browser SDK for Session Insights. Records sessions with [rrweb](https://github.com/rrweb-io/rrweb)
under privacy-by-default masking, derives a few structured events (clicks, navigation, errors),
and sends both to `collector-service`. Framework-free TypeScript (ADR-0006, ADR-0007).

The SDK runs inside your pages, so its first duty is to do no harm: it never throws into the
page, never blocks it, and fails silently when the collector is unreachable.

## Install

**npm**

```bash
npm i @session-insights/sdk
```

```ts
import * as SessionInsights from '@session-insights/sdk';

SessionInsights.init({
  siteKey: 'sk_live_…',
  collectorUrl: 'https://collect.example.com',
});
```

**Script tag** — the IIFE build defines `window.SessionInsights`:

```html
<script src="/path/to/session-insights.iife.js"></script>
<script>
  SessionInsights.init({ siteKey: 'sk_live_…', collectorUrl: 'https://collect.example.com' });
</script>
```

## API

The API below is stable. Every method is safe to call at any time, including before `init`,
and never throws.

```ts
SessionInsights.init({ siteKey, collectorUrl, sampleRate?, maskAllText?, flushIntervalMs?, debug? })
SessionInsights.identify(userId, traits?)   // reserved (F2): currently a no-op, logs in debug mode
SessionInsights.track(name, props?)         // reserved (F15): currently a no-op, logs in debug mode
SessionInsights.reset()                     // new anonymous id + new session (e.g. on logout)
SessionInsights.shutdown()                  // flush buffered data and stop
SessionInsights.getSessionId()              // current session id, or null
SessionInsights.version                     // SDK version string
```

| Option            | Default  | Meaning                                                                           |
| ----------------- | -------- | --------------------------------------------------------------------------------- |
| `siteKey`         | required | Public site key. Sent as the `?k=` query parameter, never as a header (ADR-0010). |
| `collectorUrl`    | required | Collector base URL (absolute `http(s)`).                                          |
| `sampleRate`      | `1`      | Fraction of sessions recorded (0–1), decided once per session.                    |
| `maskAllText`     | `false`  | Also mask all page text, not only inputs. Recommended for strict tenants.         |
| `flushIntervalMs` | `5000`   | How often buffered data is sent (1000–60000 ms).                                  |
| `debug`           | `false`  | Log diagnostics with `console.warn` / `console.info`.                             |

Calling `init` while the SDK is running is a no-op (with a warning in debug mode). Call
`shutdown()` first to re-initialise with different options. Invalid options leave the SDK
inert; they never throw.

## Privacy

Masking happens in the browser, before anything is sent; the server redacts again (ADR-0006).

- **All inputs are masked** by default (`*` per character).
- **Sensitive fields are never recorded**, whatever the configuration, and are never
  unmasked even inside `data-si-unmask`:
  - `input[type=password]`, `autocomplete` containing `password`, `cc-` (card number, CVC,
    expiry, …) or `one-time-code`: replaced by a same-size placeholder, with no value and
    no input events (a field that was a password stays masked after a "show password"
    toggle).
  - inputs, textareas and selects whose `name` or `id` has the **token** `otp`, `cvv`,
    `cvc` or `cvn`. Names are split at non-alphanumerics, camelCase and letter/digit
    boundaries, so `otpCode`, `card_cvv`, `user-otp`, `CVC2` match and `footprint` or
    `hotpot` do not. These are recorded as fields, but their value is always the same
    6-character mask, whatever its length, and never used for click labels. The SDK does
    not write anything to your page to do this.
- **`data-si-block`**: the element and everything inside it are never recorded.
- **`data-si-unmask`**: inputs inside the element are recorded unmasked (except sensitive
  fields, see above).
- **`maskAllText: true`** also masks all text on the page. Off by default.
- Not recorded in v1: canvas content, video, cross-origin iframes.

```html
<div data-si-block>Account balance: …</div>
<form data-si-unmask>
  <input name="search" />
  <!-- recorded as typed -->
  <input type="password" />
  <!-- never recorded -->
</form>
```

## Transport

- Batches go to `POST {collectorUrl}/v1/events` (structured events) and `/v1/replay` (rrweb
  chunks), every `flushIntervalMs` or when a replay chunk reaches ~256 KB. A session's first
  chunk (its full snapshot) is sent right away.
- The site key is always the `?k=` query parameter, never a header. Bodies are `text/plain`,
  gzipped with `CompressionStream` when the browser has it (`Content-Encoding: gzip`, which
  costs one cached CORS preflight), plain JSON otherwise.
- On page hide (`visibilitychange` → hidden, `pagehide`) the SDK uses `navigator.sendBeacon`
  (uncompressed; falls back to `fetch` with `keepalive`). Browsers cap these bodies at ~64 KB
  per page, so the most recent events go first, then replay chunks oldest-first; the replay
  tail that does not fit is not sent (it stays queued in case the page comes back).
  Normal sends of up to 32 KB use `fetch` with `keepalive`, so one in flight at page hide
  survives the unload and is not sent again by the beacon.
- Retries: network errors, `429` (honouring `Retry-After`) and `5xx`, with exponential
  backoff and full jitter (≤ 30 s). `400`, `413` and other `4xx` are dropped, not retried.
- `401`/`403` (bad key or origin) stop the SDK for the page on the first refusal. The
  collector makes them readable to browsers (ADR-0012), and with `debug: true` the console
  says "invalid site key" or "origin not allowed for this site". As a fallback for network
  errors (collector down, CSP, ad blockers), the SDK also stops after 5 consecutive failures
  when nothing has ever been accepted on this page.
- Everything waiting to be sent is held in memory, bounded at 2 MB; when full, the oldest
  replay data is dropped first (then the oldest events), and a new full snapshot is taken
  once the queue drains so replay can resume.
- Large pages: the newest full snapshot is held outside that bound, up to 16 MB of JSON
  (the collector's replay limit), and sent as one gzip-compressed `fetch` (never a beacon).
  A snapshot over 16 MB is dropped and replay turns off for that page (events continue);
  with `debug: true` the console says "full snapshot too large … replay off for this page".

## Content Security Policy

If your site sends a CSP header, allow the SDK to reach the collector:

```
Content-Security-Policy: connect-src 'self' https://collect.example.com
```

`connect-src` covers `fetch` and `navigator.sendBeacon`. With a script tag, also allow the
SDK's origin in `script-src` (or self-host the IIFE file). The SDK needs no `unsafe-eval`
and injects no inline scripts. If the collector is blocked by CSP, requests fail like
network errors and the SDK stops itself for the page after a few attempts.

The collector must also list your site's origin in the site's allowed origins
(ADR-0010); otherwise it refuses the requests.

## Do no harm

- Every public method and every listener or wrapper the SDK installs is guarded: nothing
  the SDK does can throw into the page. Patched functions (`console.error`,
  `history.pushState/replaceState`) always call the original, keep its return value and
  errors, and are restored on `shutdown()` unless something else wrapped them afterwards.
- Error capture uses listeners (never replaces `window.onerror`), so the page's handlers run
  unchanged. The SDK never captures its own errors, and logs only with `console.warn/info`
  when `debug` is on.
- Work is kept off the page's hot path: events are serialised once as they arrive, batches
  are assembled in `requestIdleCallback`, and compression runs in `CompressionStream`.
- Unsampled sessions install nothing but a few passive activity listeners.
