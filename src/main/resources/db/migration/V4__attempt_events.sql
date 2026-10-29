ALTER TABLE operation_events ADD COLUMN attempt_no INT;
ALTER TABLE operation_events ALTER COLUMN details CLOB;
