import { ASSET_TYPES, LABEL_COLORS } from './constants';

test('LABEL_COLORS keys are a subset of ASSET_TYPES', () => {
  const unknown = Object.keys(LABEL_COLORS).filter((k) => !ASSET_TYPES.includes(k));
  expect(unknown).toEqual([]);
});
