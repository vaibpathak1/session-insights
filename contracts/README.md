# Wire contract fixtures

`fixtures/*.json` are real request bodies produced by the browser SDK:

- `event-batch.json` — `POST /v1/events` (`EventBatch`)
- `replay-batch.json` — `POST /v1/replay` (`ReplayBatch`, chunk 0 with a masked full snapshot)

Both sides test against them, which is how Java and TypeScript stay in sync:

- `sdk/test/contract.test.ts` asserts the SDK still produces exactly these bodies.
- `services/collector-service/.../ContractFixturesTest.java` asserts the collector accepts them
  in full and that every field the SDK sends exists in the platform-common records.

After an intentional contract change, regenerate from `sdk/` and review the diff:

```bash
UPDATE_FIXTURES=1 npx vitest run test/contract.test.ts
```
