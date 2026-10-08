import { describe, expect, it } from 'vitest';
import { modelPriceLabel } from './modelPrice';

describe('modelPriceLabel', () => {
  it('shows input and output prices per 1M tokens, trimming trailing zeros', () => {
    expect(
      modelPriceLabel({ priceKnown: true, inputPricePerMillion: 1.25, outputPricePerMillion: 10 }),
    ).toBe('$1.25 in / $10 out per 1M tokens');
  });

  it('keeps one decimal place without a trailing zero', () => {
    expect(
      modelPriceLabel({ priceKnown: true, inputPricePerMillion: 0.15, outputPricePerMillion: 3 }),
    ).toBe('$0.15 in / $3 out per 1M tokens');
    expect(
      modelPriceLabel({ priceKnown: true, inputPricePerMillion: 3.5, outputPricePerMillion: 15.0 }),
    ).toBe('$3.5 in / $15 out per 1M tokens');
  });

  it('rounds prices of a cent or more to two decimal places', () => {
    expect(
      modelPriceLabel({ priceKnown: true, inputPricePerMillion: 2.499, outputPricePerMillion: 10.004 }),
    ).toBe('$2.5 in / $10 out per 1M tokens');
  });

  it('uses up to four decimal places for a price below one cent', () => {
    expect(
      modelPriceLabel({ priceKnown: true, inputPricePerMillion: 0.0004, outputPricePerMillion: 0.005 }),
    ).toBe('$0.0004 in / $0.005 out per 1M tokens');
  });

  it('shows a zero side as $0 when the other side is priced', () => {
    expect(
      modelPriceLabel({ priceKnown: true, inputPricePerMillion: 0, outputPricePerMillion: 2 }),
    ).toBe('$0 in / $2 out per 1M tokens');
  });

  it('says a model is free when both prices are zero and known', () => {
    expect(
      modelPriceLabel({ priceKnown: true, inputPricePerMillion: 0, outputPricePerMillion: 0 }),
    ).toBe('free');
  });

  it('says the price is unknown when the catalog does not know it', () => {
    expect(
      modelPriceLabel({ priceKnown: false, inputPricePerMillion: 1, outputPricePerMillion: 2 }),
    ).toBe('price unknown');
  });

  it('says the price is unknown when a price is missing even though the catalog marked it known', () => {
    expect(
      modelPriceLabel({ priceKnown: true, inputPricePerMillion: null, outputPricePerMillion: 2 }),
    ).toBe('price unknown');
  });
});
