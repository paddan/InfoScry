<script lang="ts">
  import type { EffortLevel, EffortPreset, LlmProvider, NativeReasoning, ResolvedInvestigationLimits } from './api';
  export let overrides: Partial<Record<EffortLevel, EffortPreset>> | null = null;
  export let provider: LlmProvider;
  export let maxOutputTokens: number;
  const levels: EffortLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'XHIGH'];
  const defaults: Record<EffortLevel, ResolvedInvestigationLimits> = {
    LOW: { maxToolRounds: 10, maxToolCalls: 15, maxTurnSeconds: 120 },
    MEDIUM: { maxToolRounds: 50, maxToolCalls: 50, maxTurnSeconds: 600 },
    HIGH: { maxToolRounds: 50, maxToolCalls: 75, maxTurnSeconds: 1200 },
    XHIGH: { maxToolRounds: 50, maxToolCalls: 100, maxTurnSeconds: 1800 },
  };
  function preset(level: EffortLevel): EffortPreset {
    return overrides?.[level] ?? { limits: defaults[level], reasoning: { type: 'None' } };
  }
  function limit(level: EffortLevel, field: keyof ResolvedInvestigationLimits, event: Event): void {
    const current = preset(level);
    overrides = { ...overrides, [level]: { ...current, limits: { ...current.limits, [field]: (event.currentTarget as HTMLInputElement).valueAsNumber } } };
  }
  function reasoning(level: EffortLevel, event: Event): void {
    const value = (event.currentTarget as HTMLInputElement | HTMLSelectElement).value;
    const native: NativeReasoning = value === '' ? { type: 'None' }
      : provider === 'ANTHROPIC' ? { type: 'AnthropicThinkingBudget', tokens: Number(value) }
      : { type: 'OpenAiReasoningEffort', value: value as 'low' | 'medium' | 'high' };
    overrides = { ...overrides, [level]: { ...preset(level), reasoning: native } };
  }
  function clear(level: EffortLevel): void {
    const next = { ...overrides };
    delete next[level];
    overrides = Object.keys(next).length ? next : null;
  }
  function nativeValue(level: EffortLevel, _overrides: typeof overrides): string | number {
    const value = preset(level).reasoning;
    return value.type === 'AnthropicThinkingBudget' ? value.tokens : value.type === 'OpenAiReasoningEffort' ? value.value : '';
  }
</script>

<fieldset class="effort-levels">
  <legend>Investigate effort levels</legend>
  <p>Inherited limits apply until a row is edited. Native reasoning is optional; enable it only for a model that supports it.</p>
  {#each levels as level}
    <fieldset class="effort-row">
      <legend>{level} — {overrides?.[level] ? 'Override' : 'Inherited'}</legend>
      <label>{level} tool rounds
        <input type="number" min="1" max="50" step="1" required value={(overrides?.[level]?.limits ?? defaults[level]).maxToolRounds} oninput={(event) => limit(level, 'maxToolRounds', event)} />
      </label>
      <label>{level} tool calls
        <input type="number" min="1" max="100" step="1" required value={(overrides?.[level]?.limits ?? defaults[level]).maxToolCalls} oninput={(event) => limit(level, 'maxToolCalls', event)} />
      </label>
      <label>{level} turn seconds
        <input type="number" min="10" max="1800" step="1" required value={(overrides?.[level]?.limits ?? defaults[level]).maxTurnSeconds} oninput={(event) => limit(level, 'maxTurnSeconds', event)} />
      </label>
      <label>{level} native reasoning
        {#if provider === 'ANTHROPIC'}
          <input type="number" min="1024" max={maxOutputTokens - 1} step="1" placeholder="None" value={nativeValue(level, overrides)} oninput={(event) => reasoning(level, event)} />
        {:else}
          <select value={nativeValue(level, overrides)} onchange={(event) => reasoning(level, event)}>
            <option value="">None</option>
            <option value="low">Low</option><option value="medium">Medium</option><option value="high">High</option>
          </select>
        {/if}
      </label>
      <button type="button" aria-label={`Clear ${level} override`} onclick={() => clear(level)} disabled={!overrides?.[level]}>Clear</button>
    </fieldset>
  {/each}
</fieldset>

<style>
  .effort-levels { min-width: 0; }
  p { font-size: .85rem; }
  .effort-row { display: flex; flex-wrap: wrap; gap: .65rem; margin-block: .65rem; min-width: 0; }
  label { display: grid; gap: .25rem; font-size: .8rem; flex: 1 1 8rem; min-width: 0; }
  input, select { width: 100%; min-width: 0; }
  button { align-self: end; }
</style>
