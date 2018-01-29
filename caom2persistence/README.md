# caom2db/caom2persistence

If a file named 'test.schema' is available in this directory, the contents of that file will be used as the schema for running the unit tests.

For PostgreSQL tests, pass in the `-Dtest.db.skip_init` system property to skip database initialization for an already
empty database:

`gradle -Dtest.db.skip_init clean build test`

