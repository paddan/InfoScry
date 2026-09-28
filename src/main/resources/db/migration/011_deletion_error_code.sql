-- Why a deletion stopped, as a stable code beside the message a human reads.
--
-- The stored message names absolute paths inside the data directory, because that is what an operator
-- reading the log needs; a client must not receive it. The code is what crosses the wire, and it is a
-- small, stable vocabulary a client maps to a remedy. `last_error` keeps the detail for the log.
ALTER TABLE deletion_operations ADD COLUMN error_code TEXT;
