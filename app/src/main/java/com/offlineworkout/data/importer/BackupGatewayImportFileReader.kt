package com.offlineworkout.data.importer

import com.offlineworkout.data.backup.BackupFileGateway

// Adapter rather than making the backup gateway implement a second interface:
// the dependency stays one-way (import reuses the file gateway) and the backup
// tool keeps knowing nothing about import.
class BackupGatewayImportFileReader(
    private val gateway: BackupFileGateway
) : ImportFileReader {
    override suspend fun readBytes(uriString: String): Result<ByteArray> =
        gateway.readBytes(uriString)
}
