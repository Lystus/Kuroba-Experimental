/*
 * KurobaEx - *chan browser https://github.com/K1rakishou/Kuroba-Experimental/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.k1rakishou.chan.features.thread_importing

import android.net.Uri
import com.github.k1rakishou.chan.core.usecase.ISuspendUseCase
import com.github.k1rakishou.chan.utils.BackgroundUtils
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.common.StringUtils
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.fsaf.FileManager
import com.github.k1rakishou.fsaf.file.ExternalFile
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import javax.inject.Inject

class ImportThreadsFromDirectoryUseCase @Inject constructor(
    private val fileManager: FileManager,
    private val importThreadFromZipUseCase: ImportThreadFromZipUseCase
) : ISuspendUseCase<ImportThreadsFromDirectoryUseCase.Params, ModularResult<ImportThreadsFromDirectoryUseCase.Result>> {

    override suspend fun execute(parameter: Params): ModularResult<ImportThreadsFromDirectoryUseCase.Result> {
        BackgroundUtils.ensureBackgroundThread()
        
        return ModularResult.Try {
            val directory = parameter.directory
            val onProgress = parameter.onProgress
            
            Logger.d(TAG, "Starting batch thread import from directory: ${directory.getFullPath()}")
            
            onProgress(BatchProgressUpdate("Scanning directory for ZIP files...", 0, 0, 0.0f))
            
            // Find all ZIP files in the directory
            val zipFiles = findZipFiles(directory)
            
            if (zipFiles.isEmpty()) {
                Logger.d(TAG, "No ZIP files found in directory: ${directory.getFullPath()}")
                return@Try Result(
                    totalFiles = 0,
                    successfulImports = emptyList(),
                    failedImports = emptyList()
                )
            }
            
            Logger.d(TAG, "Found ${zipFiles.size} ZIP files to import")
            
            val successfulImports = mutableListOf<ChanDescriptor.ThreadDescriptor>()
            val failedImports = mutableListOf<FailedImport>()
            
            // Process each ZIP file
            zipFiles.forEachIndexed { index, zipFile ->
                val fileName = zipFile.getFullPath()
                val zipFileName = fileName.substringAfterLast('/')
                Logger.d(TAG, "Processing file ${index + 1}/${zipFiles.size}: $fileName")
                
                onProgress(BatchProgressUpdate(
                    "Importing $zipFileName (${index + 1}/${zipFiles.size})",
                    index,
                    zipFiles.size,
                    index.toFloat() / zipFiles.size
                ))
                
                try {
                    // Create parameters for single file import
                    val singleImportParams = ImportThreadFromZipUseCase.Params(
                        zipFile = zipFile,
                        appContext = parameter.appContext,
                        onProgress = { singleProgress ->
                            // Update progress for current file
                            val overallProgress = (index + singleProgress.percentage) / zipFiles.size
                            onProgress(BatchProgressUpdate(
                                "Importing $zipFileName: ${singleProgress.message}",
                                index,
                                zipFiles.size,
                                overallProgress
                            ))
                        }
                    )
                    
                    // Import the single ZIP file
                    val result = importThreadFromZipUseCase.execute(singleImportParams)
                    
                    when (result) {
                        is ModularResult.Error -> {
                            Logger.e(TAG, "Failed to import $zipFileName", result.error)
                            failedImports += FailedImport(
                                fileName = zipFileName,
                                error = result.error
                            )
                        }
                        is ModularResult.Value -> {
                            Logger.d(TAG, "Successfully imported $zipFileName")
                            successfulImports += result.value
                        }
                    }
                } catch (error: Throwable) {
                    Logger.e(TAG, "Unexpected error importing $zipFileName", error)
                    failedImports += FailedImport(
                        fileName = zipFileName,
                        error = error
                    )
                }
            }
            
            onProgress(BatchProgressUpdate(
                "Import complete",
                zipFiles.size,
                zipFiles.size,
                1.0f
            ))
            
            Logger.d(TAG, "Batch import complete. Success: ${successfulImports.size}, Failed: ${failedImports.size}")
            
            Result(
                totalFiles = zipFiles.size,
                successfulImports = successfulImports,
                failedImports = failedImports
            )
        }.peekError { error ->
            Logger.e(TAG, "Error in batch thread import", error)
        }
    }
    
    private fun findZipFiles(directory: ExternalFile): List<ExternalFile> {
        if (!fileManager.isDirectory(directory)) {
            Logger.e(TAG, "Provided path is not a directory: ${directory.getFullPath()}")
            return emptyList()
        }
        
        val files = fileManager.listFiles(directory) ?: return emptyList()
        Logger.d(TAG, "Found ${files.size} total files in directory")
        
        // Filter ZIP files and cast to ExternalFile
        val zipFiles = files.filter { file ->
            val fileName = file.getFullPath().substringAfterLast('/').lowercase()
            val isZip = fileName.endsWith(".zip") && fileManager.isFile(file)
            Logger.d(TAG, "Checking file: $fileName, isZip: $isZip, isFile: ${fileManager.isFile(file)}")
            isZip
        }.mapNotNull { file ->
            // Try to cast to ExternalFile or convert if possible
            when (file) {
                is ExternalFile -> file
                else -> {
                    Logger.e(TAG, "File ${file.getFullPath()} is not an ExternalFile, it's ${file.javaClass.simpleName}")
                    // Try to create an ExternalFile from the URI if available
                    try {
                        val uri = Uri.parse(file.getFullPath())
                        fileManager.fromUri(uri)
                    } catch (error: Throwable) {
                        Logger.e(TAG, "Failed to convert file to ExternalFile: ${file.getFullPath()}", error)
                        null
                    }
                }
            }
        }.filterNotNull()
        
        Logger.d(TAG, "Found ${zipFiles.size} ZIP files in directory")
        zipFiles.forEach { zipFile ->
            Logger.d(TAG, "  - ${zipFile.getFullPath().substringAfterLast('/')}")
        }
        
        return zipFiles
    }
    
    data class Params(
        val directory: ExternalFile,
        val appContext: android.content.Context,
        val onProgress: (BatchProgressUpdate) -> Unit
    )
    
    data class Result(
        val totalFiles: Int,
        val successfulImports: List<ChanDescriptor.ThreadDescriptor>,
        val failedImports: List<FailedImport>
    )
    
    data class FailedImport(
        val fileName: String,
        val error: Throwable
    )
    
    data class BatchProgressUpdate(
        val message: String,
        val currentFile: Int,
        val totalFiles: Int,
        val overallProgress: Float
    )
    
    companion object {
        private const val TAG = "ImportThreadsFromDirectoryUseCase"
    }
}
