# Legacy changelog fixtures (FROZEN — never edit)

These `.sql` files and the `db.changelog-master.yaml` are **frozen test fixtures** that
replicate the pre-conversion (SQL/YAML) Liquibase changelogs exactly as they existed in
production databases **before** commit `caf3394` ("Convert Liquibase changelogs from
SQL/YAML to XML"). They were recovered verbatim from git history:

    git show caf3394^:src/main/resources/db/changelog/changes/<NNN>.sql
    git show caf3394^:src/main/resources/db/changelog/db.changelog-master.yaml

They are used by `changelog-rerun-safety.feature` /
`ChangelogRerunSafetySteps.java` to reconstruct a database in the state a legacy
deployment left it: the legacy schema **plus** the `DATABASECHANGELOG` rows that the
old `.sql` changelogs recorded (FILENAME = `db/changelog/changes/<NNN>.sql`). Replaying
the current `db/changelog/db.changelog-master.xml` over that state is what exposed the
changelog-identity bug (the `.sql` → `.xml` file rename changed each changeset's
Liquibase identity `(id, author, FILENAME)`, so every changeset re-ran its DDL and threw
`relation "accounts" already exists`).

**Do NOT edit these files to match new schema changes.** Their whole purpose is to be a
fixed snapshot of history. If the production schema evolves, add a new fixture or a new
scenario — never mutate these.
