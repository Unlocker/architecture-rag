import { parseRoute } from './useHashRoute';

test('parses known hashes', () => {
  expect(parseRoute('#/graph')).toBe('graph');
  expect(parseRoute('#/sync')).toBe('sync');
});

test('falls back to graph for empty or unknown hash', () => {
  expect(parseRoute('')).toBe('graph');
  expect(parseRoute('#/nope')).toBe('graph');
});
