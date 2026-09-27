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
