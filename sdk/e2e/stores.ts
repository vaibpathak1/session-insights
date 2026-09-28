/**
 * Read-only access to the pipeline's stores for the e2e test (Phase 4): ClickHouse over HTTP
 * and the S3 API (SeaweedFS) with a minimal SigV4 signer, so no extra npm dependencies.
 * Connection settings come from the repository .env, exported by scripts/e2e-pipeline.sh.
 */
import { createHash, createHmac } from 'node:crypto';
import { zstdDecompressSync } from 'node:zlib';

function env(name: string, fallback: string): string {
  return process.env[name] ?? fallback;
}

const CLICKHOUSE_URL = env('CLICKHOUSE_URL', 'http://localhost:8123');
const S3_ENDPOINT = env('S3_ENDPOINT', 'http://localhost:8333');
const S3_REGION = env('S3_REGION', 'us-east-1');
export const S3_BUCKET = env('S3_REPLAY_BUCKET', 'session-replays');

/** Runs a query with named parameters ({name:Type}) and returns JSONEachRow lines, parsed. */
export async function clickhouse<T>(
  sql: string,
  params: Record<string, string> = {},
): Promise<T[]> {
  const url = new URL(CLICKHOUSE_URL);
  url.searchParams.set('database', env('CLICKHOUSE_DB', 'insights'));
  for (const [k, v] of Object.entries(params)) url.searchParams.set(`param_${k}`, v);
  const auth = Buffer.from(
    `${env('CLICKHOUSE_USER', 'insights')}:${env('CLICKHOUSE_PASSWORD', 'insights_dev_pw')}`,
  ).toString('base64');
  const res = await fetch(url, {
    method: 'POST',
    headers: { Authorization: `Basic ${auth}` },
    body: `${sql} FORMAT JSONEachRow`,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`ClickHouse ${res.status}: ${text.slice(0, 200)}`);
  return text
    .split('\n')
    .filter((line) => line.length > 0)
    .map((line) => JSON.parse(line) as T);
}

/** GET an object's bytes (path-style); null on 404. */
export async function s3Get(key: string): Promise<Buffer | null> {
  const res = await s3Request(`/${S3_BUCKET}/${key}`, {});
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`S3 GET ${res.status}`);
  return Buffer.from(await res.arrayBuffer());
}

/** Keys under a prefix (ListObjectsV2, one page is plenty for one session). */
export async function s3List(prefix: string): Promise<string[]> {
  const res = await s3Request(`/${S3_BUCKET}`, { 'list-type': '2', prefix });
  const xml = await res.text();
  if (!res.ok) throw new Error(`S3 LIST ${res.status}: ${xml.slice(0, 200)}`);
  return [...xml.matchAll(/<Key>([^<]+)<\/Key>/g)].map((m) => m[1]!);
}

export function unzstd(bytes: Buffer): string {
  return zstdDecompressSync(bytes).toString('utf8');
}

// ---------------------------------------------------------------- SigV4

const enc = (s: string) =>
  encodeURIComponent(s).replace(
    /[!'()*]/g,
    (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`,
  );
const sha256 = (s: string) => createHash('sha256').update(s).digest('hex');
const hmac = (key: Buffer | string, s: string) => createHmac('sha256', key).update(s).digest();

async function s3Request(path: string, query: Record<string, string>): Promise<Response> {
  const endpoint = new URL(S3_ENDPOINT);
  const now = new Date().toISOString().replace(/[:-]|\.\d{3}/g, ''); // 20260928T101500Z
  const day = now.slice(0, 8);
  const canonicalUri = path.split('/').map(enc).join('/');
  const canonicalQuery = Object.keys(query)
    .sort()
    .map((k) => `${enc(k)}=${enc(query[k]!)}`)
    .join('&');
  const headers: Record<string, string> = {
    host: endpoint.host,
    'x-amz-content-sha256': 'UNSIGNED-PAYLOAD',
    'x-amz-date': now,
  };
  const signedHeaders = Object.keys(headers).sort().join(';');
  const canonicalHeaders = Object.keys(headers)
    .sort()
    .map((k) => `${k}:${headers[k]}\n`)
    .join('');
  const canonicalRequest = [
    'GET',
    canonicalUri,
    canonicalQuery,
    canonicalHeaders,
    signedHeaders,
    'UNSIGNED-PAYLOAD',
  ].join('\n');
  const scope = `${day}/${S3_REGION}/s3/aws4_request`;
  const toSign = ['AWS4-HMAC-SHA256', now, scope, sha256(canonicalRequest)].join('\n');
  let key: Buffer = hmac(`AWS4${env('S3_SECRET_KEY', 'insights_dev_secret')}`, day);
  for (const part of [S3_REGION, 's3', 'aws4_request']) key = hmac(key, part);
  const signature = createHmac('sha256', key).update(toSign).digest('hex');
  const accessKey = env('S3_ACCESS_KEY', 'insights_dev_key');
  const url = `${endpoint.origin}${canonicalUri}${canonicalQuery ? `?${canonicalQuery}` : ''}`;
  return fetch(url, {
    headers: {
      'x-amz-content-sha256': headers['x-amz-content-sha256']!,
      'x-amz-date': now,
      Authorization: `AWS4-HMAC-SHA256 Credential=${accessKey}/${scope}, SignedHeaders=${signedHeaders}, Signature=${signature}`,
    },
  });
}
