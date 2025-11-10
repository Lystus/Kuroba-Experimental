package com.github.k1rakishou.chan.features.thread_importing

import android.content.Context
import android.net.Uri
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import android.view.Gravity
import android.view.ViewGroup
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.controller.Controller
import com.github.k1rakishou.chan.core.di.component.activity.ActivityComponent
import com.github.k1rakishou.chan.core.helper.DialogFactory
import com.github.k1rakishou.chan.core.manager.GlobalWindowInsetsManager
import com.github.k1rakishou.chan.features.drawer.MainControllerCallbacks
import com.github.k1rakishou.chan.ui.controller.LoadingViewController
import com.github.k1rakishou.chan.ui.controller.BaseFloatingController
import com.github.k1rakishou.chan.ui.controller.FloatingListMenuController
import com.github.k1rakishou.chan.ui.view.floating_menu.FloatingListMenuItem
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.common.errorMessageOrClassName
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.fsaf.FileChooser
import com.github.k1rakishou.fsaf.callback.directory.DirectoryChooserCallback
import com.github.k1rakishou.fsaf.FileManager
import com.github.k1rakishou.fsaf.callback.FileChooserCallback
import com.github.k1rakishou.chan.utils.BackgroundUtils
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ThreadImportController(
    context: Context,
    private val mainControllerCallbacks: MainControllerCallbacks
) : BaseFloatingController(context) {
    
    companion object {
        private const val ACTION_IMPORT_SINGLE_THREAD = 0
        private const val ACTION_IMPORT_MULTIPLE_THREADS = 1
        private const val TAG = "ThreadImportController"
    }
    
    override fun getLayoutId(): Int = R.layout.controller_invisible
    
    @Inject
    lateinit var _dialogFactory: Lazy<DialogFactory>
    @Inject
    lateinit var _fileChooser: Lazy<FileChooser>
    @Inject
    lateinit var _fileManager: Lazy<FileManager>
    @Inject
    lateinit var _importThreadFromZipUseCase: Lazy<ImportThreadFromZipUseCase>
    @Inject
    lateinit var _importThreadsFromDirectoryUseCase: Lazy<ImportThreadsFromDirectoryUseCase>
    
    private val dialogFactory: DialogFactory
        get() = _dialogFactory.get()
    private val fileChooser: FileChooser
        get() = _fileChooser.get()
    private val fileManager: FileManager
        get() = _fileManager.get()
    private val importThreadFromZipUseCase: ImportThreadFromZipUseCase
        get() = _importThreadFromZipUseCase.get()
    private val importThreadsFromDirectoryUseCase: ImportThreadsFromDirectoryUseCase
        get() = _importThreadsFromDirectoryUseCase.get()
    
    override fun injectDependencies(component: ActivityComponent) {
        component.inject(this)
    }
    
    override fun onCreate() {
        super.onCreate()
        
        navigation.setTitle(AppModuleAndroidUtils.getString(R.string.thread_import_title))
        navigation.swipeable = false
        
        // Show import method selection menu
        showImportMethodSelectionMenu()
    }
    
    private fun showImportMethodSelectionMenu() {
        val items = listOf(
            FloatingListMenuItem(
                ACTION_IMPORT_SINGLE_THREAD,
                AppModuleAndroidUtils.getString(R.string.thread_import_single_file)
            ),
            FloatingListMenuItem(
                ACTION_IMPORT_MULTIPLE_THREADS,
                AppModuleAndroidUtils.getString(R.string.thread_import_directory)
            )
        )
        
        val floatingListMenuController = FloatingListMenuController(
            context = context,
            constraintLayoutBias = globalWindowInsetsManager.lastTouchCoordinatesAsConstraintLayoutBias(),
            items = items,
            itemClickListener = { clickedItem ->
                when (clickedItem.key as Int) {
                    ACTION_IMPORT_SINGLE_THREAD -> showSingleFileChooser()
                    ACTION_IMPORT_MULTIPLE_THREADS -> showDirectoryChooser()
                }
            }
        )
        
        presentController(floatingListMenuController)
    }
    
    private fun showSingleFileChooser() {
        val fileChooserCallback = object : FileChooserCallback() {
            override fun onResult(uri: Uri) {
                importSingleThread(uri)
            }
            
            override fun onCancel(reason: String) {
                Logger.d(TAG, "Single file chooser cancelled: $reason")
                navigationController?.popController()
            }
        }
        
        fileChooser.openChooseFileDialog(fileChooserCallback)
    }
    
    private fun showDirectoryChooser() {
        val directoryChooserCallback = object : DirectoryChooserCallback() {
            override fun onResult(uri: Uri) {
                importThreadsFromDirectory(uri)
            }
            
            override fun onCancel(reason: String) {
                Logger.d(TAG, "Directory chooser cancelled: $reason")
                navigationController?.popController()
            }
        }
        
        fileChooser.openChooseDirectoryDialog(directoryChooserCallback)
    }
    
    private fun importSingleThread(uri: Uri) {
        Logger.d(TAG, "Starting import for URI: $uri")
        
        val loadingViewController = LoadingViewController(
            context,
            true,
            AppModuleAndroidUtils.getString(R.string.thread_import_importing_progress)
        )
        
        presentController(loadingViewController, false)
        
        mainScope.launch {
            try {
                // Convert URI to ExternalFile
                Logger.d(TAG, "Converting URI to ExternalFile: $uri")
                val externalFile = fileManager.fromUri(uri)
                if (externalFile == null) {
                    Logger.e(TAG, "Failed to convert URI to ExternalFile: $uri")
                    AppModuleAndroidUtils.showToast(context, "Failed to open file")
                    return@launch
                }
                
                Logger.d(TAG, "Successfully converted URI to ExternalFile: ${externalFile.getFullPath()}")
                
                val params = ImportThreadFromZipUseCase.Params(
                    zipFile = externalFile,
                    appContext = context,
                    onProgress = { progress ->
                        // Update loading message if possible
                        Logger.d(TAG, "Progress: ${progress.message} (${progress.percentage})")
                    }
                )
                
                // Execute the use case on a background thread
                Logger.d(TAG, "Executing ImportThreadFromZipUseCase...")
                val result = withContext(Dispatchers.IO) {
                    importThreadFromZipUseCase.execute(params)
                }
                
                Logger.d(TAG, "ImportThreadFromZipUseCase completed with result: ${result::class.simpleName}")
                
                when (result) {
                    is ModularResult.Error -> {
                        val errorMessage = result.error.errorMessageOrClassName()
                        Logger.e(TAG, "Error importing thread: $errorMessage", result.error)
                        AppModuleAndroidUtils.showToast(context, errorMessage)
                    }
                    is ModularResult.Value -> {
                        Logger.d(TAG, "Thread imported successfully: ${result.value}")
                        dialogFactory.createSimpleInformationDialog(
                            context = context,
                            titleText = AppModuleAndroidUtils.getString(R.string.thread_import_success),
                            descriptionText = AppModuleAndroidUtils.getString(R.string.thread_import_success_description),
                            onDismissListener = { 
                                navigationController?.popController()
                            }
                        )
                    }
                }
            } catch (error: Throwable) {
                Logger.e(TAG, "Unexpected error importing thread", error)
                AppModuleAndroidUtils.showToast(context, AppModuleAndroidUtils.getString(R.string.thread_import_error_format))
            } finally {
                loadingViewController.stopPresenting()
                navigationController?.popController()
            }
        }
    }
    
    private fun importThreadsFromDirectory(uri: Uri) {
        Logger.d(TAG, "Starting directory import for URI: $uri")
        
        val loadingViewController = LoadingViewController(
            context,
            true,
            AppModuleAndroidUtils.getString(R.string.thread_import_directory_scanning)
        )
        
        presentController(loadingViewController, false)
        
        mainScope.launch {
            try {
                // Convert URI to ExternalFile
                val externalFile = fileManager.fromUri(uri)
                if (externalFile == null) {
                    Logger.e(TAG, "Failed to convert URI to ExternalFile: $uri")
                    AppModuleAndroidUtils.showToast(context, "Failed to open directory")
                    return@launch
                }
                
                Logger.d(TAG, "Successfully converted URI to ExternalFile: ${externalFile.getFullPath()}")
                
                val params = ImportThreadsFromDirectoryUseCase.Params(
                    directory = externalFile,
                    appContext = context,
                    onProgress = { progress ->
                        // Update loading message with detailed progress
                        Logger.d(TAG, "Batch Progress: ${progress.message} (${progress.currentFile}/${progress.totalFiles})")
                        // You can update the loading view here if it supports dynamic text updates
                    }
                )
                
                // Execute the use case on a background thread
                val result = withContext(Dispatchers.IO) {
                    importThreadsFromDirectoryUseCase.execute(params)
                }
                
                when (result) {
                    is ModularResult.Error -> {
                        val errorMessage = result.error.errorMessageOrClassName()
                        AppModuleAndroidUtils.showToast(context, errorMessage)
                        Logger.e(TAG, "Error importing threads from directory", result.error)
                    }
                    is ModularResult.Value -> {
                        val batchResult = result.value
                        val titleText = if (batchResult.failedImports.isEmpty()) {
                            AppModuleAndroidUtils.getString(R.string.thread_import_batch_success_format, batchResult.successfulImports.size)
                        } else {
                            AppModuleAndroidUtils.getString(R.string.thread_import_batch_partial_format, 
                                batchResult.successfulImports.size, batchResult.failedImports.size)
                        }
                        
                        val descriptionText = if (batchResult.failedImports.isEmpty()) {
                            AppModuleAndroidUtils.getString(R.string.thread_import_batch_success_description)
                        } else {
                            AppModuleAndroidUtils.getString(R.string.thread_import_batch_partial_description)
                        }
                        
                        dialogFactory.createSimpleInformationDialog(
                            context = context,
                            titleText = titleText,
                            descriptionText = descriptionText,
                            onDismissListener = { 
                                navigationController?.popController()
                            }
                        )
                        Logger.d(TAG, "Batch import completed. Success: ${batchResult.successfulImports.size}, Failed: ${batchResult.failedImports.size}")
                        
                        // Log failed imports for debugging
                        batchResult.failedImports.forEach { failedImport ->
                            Logger.e(TAG, "Failed to import ${failedImport.fileName}", failedImport.error)
                        }
                    }
                }
            } catch (error: Throwable) {
                AppModuleAndroidUtils.showToast(context, AppModuleAndroidUtils.getString(R.string.thread_import_directory_error))
                Logger.e(TAG, "Error importing threads from directory", error)
            } finally {
                loadingViewController.stopPresenting()
                navigationController?.popController()
            }
        }
    }
}
