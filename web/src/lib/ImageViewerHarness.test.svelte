<script lang="ts">
  import type { ImageView } from './api';
  import ImageViewer from './ImageViewer.svelte';

  export let image: ImageView;
  let open = false;
</script>

<button type="button" onclick={() => (open = true)}>Open image</button>
<ImageViewer collectionId="c" documentId="d1" {image} {open} onClose={() => (open = false)} />
