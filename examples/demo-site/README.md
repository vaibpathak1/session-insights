# Demo site (local only)

A small page for trying the browser SDK against the local stack: a login form with a
password, a checkout form with card and one-time-code fields, a `data-si-block` panel, a
`data-si-unmask` input, SPA navigation and buttons that throw / log errors.

```bash
# prerequisites: compose up, api-service seeded once (dev profile), collector running
../../scripts/dev-site-key.sh           # writes .env.local with a fresh dev site key
(cd ../../sdk && npm ci && npm run build)
npm ci && npm run dev                   # http://localhost:5173
```

It uses the local SDK build (`file:../../sdk`), so rebuild the SDK after changing it.
`../../scripts/e2e-sdk.sh` runs the end-to-end privacy test against this page.
