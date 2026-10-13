package com.offlineworkout.data.repository

// T2.1: public write failure. Carried directly by Result.failure (it is a
// Throwable by design) so ViewModels never see SQLException or Room types.
// Messages are stable and contain no SQL, DB paths or internal class names.
sealed class RepositoryFailure(message: String) : Exception(message) {

    // Invalid field/rule: code, name, position, type/target combo, timestamps, units.
    class Validation(reason: String) : RepositoryFailure(reason)

    // Referenced workout, exercise or execution does not exist.
    class NotFound(what: String) : RepositoryFailure(what)

    // Duplicate code, duplicate position, or reorder violating uniqueness.
    class Conflict(reason: String) : RepositoryFailure(reason)

    // SQLite/schema/unexpected Room failure. Technical cause stays in data.
    class Storage(reason: String) : RepositoryFailure(reason)
}

// T2.1: public read failure for Flow contracts. ViewModels catch this from the
// Flow and convert it to UiState; partial emissions never occur.
class RepositoryException(
    val failure: RepositoryFailure,
    cause: Throwable? = null
) : Exception(failure.message, cause)
