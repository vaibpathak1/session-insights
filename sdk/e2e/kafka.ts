import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = fileURLToPath(new URL('../..', import.meta.url));

export interface KafkaRecord {
  key: string;
  value: string;
}

/** Reads a whole topic with kafka-console-consumer inside the compose Kafka container. */
export function consumeTopic(topic: string, timeoutMs = 8000): KafkaRecord[] {
  const out = execFileSync(
    'docker',
    [
      'compose',
      'exec',
      '-T',
      'kafka',
      '/opt/kafka/bin/kafka-console-consumer.sh',
      '--bootstrap-server',
      'localhost:9092',
      '--topic',
      topic,
      '--from-beginning',
      '--timeout-ms',
      String(timeoutMs),
      '--property',
      'print.key=true',
      '--property',
      'key.separator=\t',
    ],
    {
      cwd: REPO_ROOT,
      encoding: 'utf8',
      maxBuffer: 512 * 1024 * 1024,
      stdio: ['ignore', 'pipe', 'ignore'],
    },
  );
  return out
    .split('\n')
    .filter((line) => line.includes('\t'))
    .map((line) => {
      const tab = line.indexOf('\t');
      return { key: line.slice(0, tab), value: line.slice(tab + 1) };
    });
}
