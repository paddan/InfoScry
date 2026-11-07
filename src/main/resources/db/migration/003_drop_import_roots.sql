-- Version 3: imports come only from files uploaded from the client's computer, so the server keeps no import
-- roots. Version 2 added them; a database that had them loses only that list, never a document.
DROP TABLE IF EXISTS import_roots;
