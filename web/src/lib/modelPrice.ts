/**
 * The reader-facing price of a catalog model, per 1M tokens in USD. Prices of a cent or more keep at most two
 * decimal places; a smaller price keeps up to four, so a cheap model's price is not rounded to zero.
 */

export type ModelPriceFields = {
  priceKnown: boolean;
  inputPricePerMillion: number | null;
  outputPricePerMillion: number | null;
};

/** Drops trailing zeros after the decimal point, and the point itself when nothing follows it. */
function trimZeros(fixed: string): string {
  return fixed.includes('.') ? fixed.replace(/0+$/, '').replace(/\.$/, '') : fixed;
}

function formatUsd(value: number): string {
  if (value === 0) return '$0';
  if (value >= 0.01) return `$${trimZeros(value.toFixed(2))}`;
  const fixed = trimZeros(value.toFixed(4));
  return fixed === '0' ? '$<0.0001' : `$${fixed}`;
}

/**
 * The price part of a model's option label: the input and output prices, "free" when both are known to be zero,
 * or "price unknown" when the catalog does not know the price (or a price is missing).
 */
export function modelPriceLabel(model: ModelPriceFields): string {
  const { inputPricePerMillion: input, outputPricePerMillion: output } = model;
  if (!model.priceKnown || input === null || output === null) return 'price unknown';
  if (input === 0 && output === 0) return 'free';
  return `${formatUsd(input)} in / ${formatUsd(output)} out per 1M tokens`;
}
