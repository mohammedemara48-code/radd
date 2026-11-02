package app.radd

import app.radd.android.RaddApp

/** A fresh database file per test: Robolectric keeps SQLite connection pools by path across tests. */
class TestApp : RaddApp() {
    override val databaseName: String = "test-" + java.util.UUID.randomUUID() + ".db"
}
