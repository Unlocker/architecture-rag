import { ASSET_TYPES, LABEL_COLORS } from './constants';

test('LABEL_COLORS keys are a subset of ASSET_TYPES', () => {
  expect(ASSET_TYPES).toEqual(expect.arrayContaining(Object.keys(LABEL_COLORS)));
});

test('LABEL_COLORS does not colour a label outside ASSET_TYPES', () => {
  const unknown = Object.keys(LABEL_COLORS).filter((k) => !ASSET_TYPES.includes(k));
  expect(unknown).toEqual([]);
});
