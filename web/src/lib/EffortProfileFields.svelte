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
  <p class="hint">Inherited limits apply until a row is edited. Native reasoning is optional; enable it only for a model that supports it.</p>
  <div class="effort-table">
    <div class="effort-head" aria-hidden="true">
      <span>Level</span><span>Tool rounds</span><span>Tool calls</span><span>Turn seconds</span><span>Native reasoning</span><span></span>
    </div>
    {#each levels as level}
      <div class="effort-row" role="group" aria-labelledby={`effort-${level}`}>
        <div class="level" id={`effort-${level}`}>
          <span class="name">{level}</span>
          <span class="state" class:override={!!overrides?.[level]}>{overrides?.[level] ? 'Override' : 'Inherited'}</span>
        </div>
        <label><span class="cell-label">Tool rounds</span>
          <input type="number" aria-label={`${level} tool rounds`} min="1" max="50" step="1" required value={(overrides?.[level]?.limits ?? defaults[level]).maxToolRounds} oninput={(event) => limit(level, 'maxToolRounds', event)} />
        </label>
        <label><span class="cell-label">Tool calls</span>
          <input type="number" aria-label={`${level} tool calls`} min="1" max="100" step="1" required value={(overrides?.[level]?.limits ?? defaults[level]).maxToolCalls} oninput={(event) => limit(level, 'maxToolCalls', event)} />
        </label>
        <label><span class="cell-label">Turn seconds</span>
          <input type="number" aria-label={`${level} turn seconds`} min="10" max="1800" step="1" required value={(overrides?.[level]?.limits ?? defaults[level]).maxTurnSeconds} oninput={(event) => limit(level, 'maxTurnSeconds', event)} />
        </label>
        <label><span class="cell-label">Native reasoning</span>
          {#if provider === 'ANTHROPIC'}
            <input type="number" aria-label={`${level} native reasoning`} min="1024" max={maxOutputTokens - 1} step="1" placeholder="None" value={nativeValue(level, overrides)} oninput={(event) => reasoning(level, event)} />
          {:else}
            <select aria-label={`${level} native reasoning`} value={nativeValue(level, overrides)} onchange={(event) => reasoning(level, event)}>
              <option value="">None</option>
              <option value="low">Low</option><option value="medium">Medium</option><option value="high">High</option>
            </select>
          {/if}
        </label>
        <button type="button" aria-label={`Clear ${level} override`} onclick={() => clear(level)} disabled={!overrides?.[level]}>Clear</button>
      </div>
    {/each}
  </div>
</fieldset>

<style>
  .effort-levels { min-width: 0; margin: 0; border: 1px solid #303535; border-radius: 0.55rem; padding: 1rem; container: effort / inline-size; }
  legend { padding: 0 0.35rem; color: #b8bcbb; font-size: 0.85rem; }
  .hint { margin: 0 0 0.9rem; color: #929997; font-size: 0.78rem; }
  .effort-table { display: grid; gap: 0.55rem; }
  .effort-head, .effort-row {
    display: grid;
    grid-template-columns: 7rem repeat(3, minmax(0, 9rem)) minmax(0, 12rem) minmax(0, 1fr);
    gap: 0.75rem;
    align-items: center;
  }
  .effort-head { color: #858a8a; font-size: 0.68rem; font-weight: 700; letter-spacing: 0.12em; text-transform: uppercase; }
  .effort-row { padding-top: 0.55rem; border-top: 1px solid #2d3131; }
  .level { display: grid; gap: 0.2rem; }
  .name { color: #e8e9e7; font-size: 0.85rem; font-weight: 650; }
  .state { color: #929997; font-size: 0.72rem; }
  .state.override { color: #c4a77d; }
  button { justify-self: start; }
  label { display: grid; gap: 0.35rem; min-width: 0; color: #b8bcbb; font-size: 0.82rem; }
  .cell-label { display: none; }
  select, input {
    width: 100%;
    min-width: 0;
    border: 1px solid #383d3e;
    border-radius: 0.48rem;
    background: #202324;
    padding: 0.62rem 0.72rem;
    color: #e8e9e7;
  }
  select:hover, input:hover { border-color: #515757; }
  @container effort (max-width: 46rem) {
    .effort-head { display: none; }
    .effort-row { grid-template-columns: repeat(2, minmax(0, 1fr)); align-items: end; }
    .level { grid-column: 1 / -1; grid-auto-flow: column; justify-content: start; align-items: baseline; gap: 0.6rem; }
    .cell-label { display: block; }
  }
</style>
