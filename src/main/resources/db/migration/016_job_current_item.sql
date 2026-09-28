-- The file an import is working on right now, named the way its reader knows it.
--
-- An import holds one file for as long as a copy and a read take, so a progress line that only counts
-- finished files cannot say what the wait is for. The name is the file's own last segment and never the
-- path it was selected from: the source paths the user picked are the archive's own bookkeeping and do
-- not cross the API boundary. A job that is not reading a file leaves the column NULL.
ALTER TABLE jobs ADD COLUMN current_item TEXT;