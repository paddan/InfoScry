-- An image's caption may come from the text of its page: the lines next to a PDF image or a scanned-page picture.
-- Rows that exist keep their value; only the set of allowed sources grows.
ALTER TABLE document_images DROP CONSTRAINT document_images_caption_source_check;

ALTER TABLE document_images
    ADD CONSTRAINT document_images_caption_source_check
        CHECK (caption_source IS NULL OR caption_source IN ('ALT_TEXT', 'FIGCAPTION', 'LAYOUT_CAPTION', 'PAGE_TEXT'));
