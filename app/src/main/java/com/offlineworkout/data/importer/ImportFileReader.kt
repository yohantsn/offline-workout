package com.offlineworkout.data.importer

// Reading the picked CSV is exactly the byte read the backup tool already
// performs through SAF (same 4 MB bound, same error types). This interface is
// the import feature's narrow view of that gateway so the ViewModel does not
// depend on the backup subsystem by name.
interface ImportFileReader {
    suspend fun readBytes(uriString: String): Result<ByteArray>
}
