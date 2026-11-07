<script lang="ts">
  import SourceReading from './SourceReading.svelte';

  export let hasReading: boolean;
  export let text: string;
  export let documentId: string | null = null;
  export let openImageUnitId: string | null = null;
</script>

<SourceReading collectionId="c1" sourceId="u1" revisionId={null} {hasReading} {documentId} {openImageUnitId}>
  <pre>{text}</pre>
</SourceReading>
