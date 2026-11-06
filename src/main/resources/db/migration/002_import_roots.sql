-- Version 2: import roots kept in the database, so an administrator adds and removes them under Admin.
-- Version 3 removes them again; this step stays so a database at any version reaches the current one.

-- A directory on the server that editors may browse and import from. `path` is the directory's real path
-- (symbolic links resolved) when it was added; a root whose directory has since gone is kept and shown as
-- unavailable, and nothing can be imported from it. Removing a root leaves documents imported from it alone.
CREATE TABLE import_roots (
    id         TEXT NOT NULL PRIMARY KEY,
    path       TEXT NOT NULL UNIQUE CHECK (length(path) > 1 AND left(path, 1) = '/'),
    created_at TEXT NOT NULL,
    created_by TEXT REFERENCES users (id),
    updated_at TEXT NOT NULL,
    updated_by TEXT REFERENCES users (id)
);
CREATE TRIGGER import_roots_attribution BEFORE INSERT OR UPDATE ON import_roots
    FOR EACH ROW EXECUTE FUNCTION infoscry_stamp_change();
