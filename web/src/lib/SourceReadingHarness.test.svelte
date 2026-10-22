<script lang="ts">
  import SourceReading from './SourceReading.svelte';

  export let hasReading: boolean;
  export let text: string;
</script>

<SourceReading collectionId="c1" sourceId="u1" revisionId={null} {hasReading}>
  <pre>{text}</pre>
</SourceReading>
