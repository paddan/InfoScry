-- Version 8: the caption and page text an image analysis was made with. They are not part of the analysis key (the
-- same picture keeps one analysis whatever text surrounds it), but a tag edit republishes the picture's unit text
-- and needs them to rebuild its layout without reading the picture again. Existing analyses keep no source text,
-- which is the honest answer for an analysis made before this column existed.
ALTER TABLE image_analyses
    ADD COLUMN caption      TEXT,
    ADD COLUMN page_context TEXT;
