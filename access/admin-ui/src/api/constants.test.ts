import { STALE_AFTER_MS } from './constants';

// @types/node в проекте нет, а новую зависимость добавляет архитектор: берём только нужное из Node через require.
declare const require: (id: string) => any;
declare const __dirname: string;
const { readFileSync } = require('fs') as { readFileSync: (path: string, encoding: 'utf8') => string };
const { resolve } = require('path') as { resolve: (...parts: string[]) => string };

const MCP_APPLICATION_YML = resolve(__dirname, '../../../mcp-server/src/main/resources/application.yml');

/** Порог «устарел» в UI равен `archrag.query.stale-after` у MCP (поддержан только формат `PnD`). */
test('STALE_AFTER_MS matches archrag.query.stale-after of the MCP server', () => {
  const yml = readFileSync(MCP_APPLICATION_YML, 'utf8');
  const match = /^\s*stale-after:\s*P(\d+)D\s*$/m.exec(yml);
  expect(match).not.toBeNull();
  expect(STALE_AFTER_MS).toBe(Number(match![1]) * 24 * 60 * 60 * 1000);
});
