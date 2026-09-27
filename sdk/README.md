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
  unmasked even inside `data-si-unmask`: they are replaced by a same-size placeholder, with
  no value and no input events. Sensitive means:
  - `input[type=password]` and `autocomplete` containing `password`
    (a field that was a password stays masked after a "show password" toggle)
  - `autocomplete` containing `cc-` (card number, CVC, expiry, …) or `one-time-code`
  - inputs, textareas and selects whose `name` or `id` contains `otp`, `cvv` or `cvc`
    (case-insensitive)
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
