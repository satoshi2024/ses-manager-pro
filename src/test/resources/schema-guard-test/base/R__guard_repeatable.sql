-- ProductionSchemaVersionGuardTest 用。placeholderを含むRepeatable（guardは置換せずに照合する）。
CREATE TABLE IF NOT EXISTS guard_repeatable (marker VARCHAR(64));
DELETE FROM guard_repeatable;
INSERT INTO guard_repeatable (marker) VALUES ('${guard_secret}');
