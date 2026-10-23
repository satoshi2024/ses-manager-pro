# NF01 read-only preflight

This checker reads the current schema and tenant ownership candidates before any NF01 schema change. It does not start Spring Boot or Flyway. It runs fixed metadata/`SELECT` statements inside a MySQL `READ ONLY` transaction and writes `nf01-preflight.json` and `nf01-preflight.md` to the configured output directory.

Use a database credential that has only `SELECT` and metadata privileges. The transaction is also opened with MySQL `START TRANSACTION READ ONLY`. The checker never prints the JDBC URL, username, password, SQL exception, or document names/storage keys. Organization-unit `tenant_id` remains a numeric legacy value in the report and is never treated as a string tenant ID.

Set these environment variables in the current PowerShell session before running the script:

- `NF01_PREFLIGHT_SCHEMA`: the database/catalog name.
- `NF01_PREFLIGHT_CODE_BASELINE`: the full source-code baseline commit being checked.
- `NF01_PREFLIGHT_OUTPUT_DIR`: a local report directory.
- `NF01_PREFLIGHT_JDBC_URL`: an explicit `jdbc:mysql:` URL for that schema.
- `NF01_PREFLIGHT_DB_USER` and `NF01_PREFLIGHT_DB_PASSWORD`: the read-only account credentials.

Run `.scripts\nf01-readonly-preflight.ps1`. The script compiles the standalone Java checker with `javac` and uses the MySQL Connector/J already present in the local Maven cache; it does not invoke Maven or run migrations. Exit code 0 means the collected checks found no blocking anomaly. `BLOCKED_MAPPING` and `FAIL` return a nonzero exit code. A `PASS` is only a clean read-only collection result; it does not authorize a migration, tenant mapping, or production change.
