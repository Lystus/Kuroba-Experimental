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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.k1rakishou.chan.core.base.BaseViewModel
import com.github.k1rakishou.chan.core.di.component.viewmodel.ViewModelComponent
import com.github.k1rakishou.chan.core.manager.ThreadDownloadManager
import com.github.k1rakishou.chan.features.thread_importing.ImportThreadFromZipUseCase
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.fsaf.FileManager
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ThreadImportViewModel : BaseViewModel() {
    
    @Inject
    lateinit var fileManager: FileManager
    
    @Inject
    lateinit var threadImportParser: ThreadImportParser
    
    @Inject
    lateinit var importThreadFromZipUseCase: ImportThreadFromZipUseCase
    
    @Inject
    lateinit var threadDownloadManager: ThreadDownloadManager
    
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()
    
    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()
    
    override fun injectDependencies(component: ViewModelComponent) {
        component.inject(this)
    }
    
    override suspend fun onViewModelReady() {
        // Initialize any required state here
    }
    
    suspend fun importThreadFromFile(uri: Uri, context: android.content.Context): ModularResult<ChanDescriptor.ThreadDescriptor> {
        return withContext(Dispatchers.IO) {
            _importState.value = ImportState.Loading
            
            try {
                val externalFile = fileManager.fromUri(uri)
                    ?: return@withContext ModularResult.error(IllegalArgumentException("Invalid URI: $uri"))
                
                Logger.d(TAG, "Starting thread import from file: ${externalFile.getFullPath()}")
                
                // Update progress
                _importProgress.value = ImportProgress("Analyzing file...", 0.1f)
                
                val result = importThreadFromZipUseCase.execute(
                    ImportThreadFromZipUseCase.Params(
                        zipFile = externalFile,
                        appContext = context,
                        onProgress = { progress -> 
                            _importProgress.value = ImportProgress(progress.message, progress.percentage)
                        }
                    )
                )
                
                when (result) {
                    is ModularResult.Value -> {
                        Logger.d(TAG, "Thread import successful: ${result.value}")
                        _importState.value = ImportState.Success(result.value)
                        _importProgress.value = ImportProgress("Import complete", 1.0f)
                        return@withContext result
                    }
                    is ModularResult.Error -> {
                        Logger.e(TAG, "Thread import failed", result.error)
                        _importState.value = ImportState.Error(result.error)
                        _importProgress.value = null
                        return@withContext result
                    }
                }
            } catch (error: Throwable) {
                Logger.e(TAG, "Thread import error", error)
                _importState.value = ImportState.Error(error)
                _importProgress.value = null
                return@withContext ModularResult.error(error)
            }
        }
    }
    
    suspend fun importThreadFromClipboard(content: String): ModularResult<ChanDescriptor.ThreadDescriptor> {
        return withContext(Dispatchers.IO) {
            _importState.value = ImportState.Loading
            
            try {
                Logger.d(TAG, "Starting thread import from clipboard")
                
                _importProgress.value = ImportProgress("Parsing clipboard content...", 0.1f)
                
                val parseResult = threadImportParser.parseFromClipboard(content)
                
                when (parseResult) {
                    is ModularResult.Value -> {
                        _importProgress.value = ImportProgress("Importing thread...", 0.5f)
                        
                        val threadDescriptor = parseResult.value
                        Logger.d(TAG, "Parsed thread descriptor: $threadDescriptor")
                        
                        // If this is a URL, we can try to download it instead
                        if (content.startsWith("http")) {
                            val downloadResult = initiateThreadDownload(threadDescriptor)
                            
                            when (downloadResult) {
                                is ModularResult.Value -> {
                                    _importState.value = ImportState.Success(threadDescriptor)
                                    _importProgress.value = ImportProgress("Download started", 1.0f)
                                    return@withContext downloadResult
                                }
                                is ModularResult.Error -> {
                                    _importState.value = ImportState.Error(downloadResult.error)
                                    _importProgress.value = null
                                    return@withContext downloadResult
                                }
                            }
                        } else {
                            _importState.value = ImportState.Success(threadDescriptor)
                            _importProgress.value = ImportProgress("Import complete", 1.0f)
                            return@withContext parseResult
                        }
                    }
                    is ModularResult.Error -> {
                        Logger.e(TAG, "Failed to parse clipboard content", parseResult.error)
                        _importState.value = ImportState.Error(parseResult.error)
                        _importProgress.value = null
                        return@withContext parseResult
                    }
                }
            } catch (error: Throwable) {
                Logger.e(TAG, "Thread import from clipboard error", error)
                _importState.value = ImportState.Error(error)
                _importProgress.value = null
                return@withContext ModularResult.error(error)
            }
        }
    }
    
    private suspend fun initiateThreadDownload(threadDescriptor: ChanDescriptor.ThreadDescriptor): ModularResult<ChanDescriptor.ThreadDescriptor> {
        return try {
            threadDownloadManager.startDownloading(
                threadDescriptor = threadDescriptor,
                threadThumbnailUrl = null,
                downloadMedia = true
            )
            
            ModularResult.value(threadDescriptor)
        } catch (error: Throwable) {
            Logger.e(TAG, "Failed to start thread download", error)
            ModularResult.error(error)
        }
    }
    
    fun resetState() {
        _importState.value = ImportState.Idle
        _importProgress.value = null
    }
    
    sealed class ImportState {
        object Idle : ImportState()
        object Loading : ImportState()
        data class Success(val threadDescriptor: ChanDescriptor.ThreadDescriptor) : ImportState()
        data class Error(val error: Throwable) : ImportState()
    }
    
    data class ImportProgress(
        val message: String,
        val percentage: Float
    )
    
    companion object {
        private const val TAG = "ThreadImportViewModel"
    }
}
