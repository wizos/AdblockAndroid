package io.github.edsuns.adfilter

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.work.WorkInfo
import io.github.edsuns.adblockclient.ResourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Created by Edsuns@qq.com on 2020/10/24.
 */
class AdFilter internal constructor(appContext: Context) {

    private val detector: Detector = Detector()
    internal val binaryDataStore: BinaryDataStore =
        BinaryDataStore(File(appContext.filesDir, FILE_STORE_DIR))
    private val filterDataLoader: FilterDataLoader = FilterDataLoader(detector, binaryDataStore)
    val viewModel = FilterViewModel(appContext, filterDataLoader)

    val hasInstallation: Boolean
        get() = viewModel.sharedPreferences.hasInstallation

    init {
        viewModel.isEnabled.observeForever { enable ->
            if (enable) {
                viewModel.filters.value?.values?.forEach {
                    if (it.isEnabled && it.hasDownloaded()) {
                        viewModel.enableFilter(it)
                    }
                }
            } else {
                filterDataLoader.unloadAll()
            }
            viewModel.sharedPreferences.isEnabled = enable
        }
        viewModel.workInfo.observeForever { list -> processWorkInfo(list) }
    }

    @Synchronized
    private fun processWorkInfo(workInfoList: List<WorkInfo>) {
        workInfoList.forEach { workInfo ->
            val filterId = viewModel.downloadFilterIdMap[workInfo.id.toString()]
            viewModel.filters.value?.get(filterId)?.let {
                updateFilter(it, workInfo)
            }
        }
    }

    private fun updateFilter(filter: Filter, workInfo: WorkInfo) {
        val state = workInfo.state
        val isInstallation = workInfo.tags.contains(TAG_INSTALLATION)
        var downloadState = filter.downloadState
        if (isInstallation) {
            // fix sometimes there are bad states cause by short interval between updates
            if (downloadState != DownloadState.SUCCESS) {
                downloadState =
                    when (state) {
                        WorkInfo.State.RUNNING -> DownloadState.INSTALLING
                        WorkInfo.State.SUCCEEDED -> {
                            val alreadyUpToDate =
                                workInfo.outputData.getBoolean(KEY_ALREADY_UP_TO_DATE, false)
                            if (!alreadyUpToDate) {
                                filter.filtersCount =
                                    workInfo.outputData.getInt(KEY_FILTERS_COUNT, 0)
                                workInfo.outputData.getString(KEY_RAW_CHECKSUM)?.let {
                                    filter.checksum = it
                                }
                                if (filter.isEnabled || !filter.hasDownloaded()) {
                                    viewModel.enableFilter(filter)
                                }
                            }
                            filter.updateTime = System.currentTimeMillis()
                            DownloadState.SUCCESS
                        }
                        WorkInfo.State.FAILED -> DownloadState.FAILED
                        WorkInfo.State.CANCELLED -> DownloadState.CANCELLED
                        else -> downloadState
                    }
            }
        } else {
            downloadState = when (state) {
                WorkInfo.State.ENQUEUED -> DownloadState.ENQUEUED
                WorkInfo.State.RUNNING -> DownloadState.DOWNLOADING
                WorkInfo.State.FAILED -> DownloadState.FAILED
                else -> downloadState
            }
        }
        if (state.isFinished) {
            viewModel.downloadFilterIdMap.remove(workInfo.id.toString())
            // save shared preferences
            viewModel.sharedPreferences.downloadFilterIdMap = viewModel.downloadFilterIdMap
            viewModel.workManager.pruneWork()
        }
        if (downloadState != filter.downloadState) {
            filter.downloadState = downloadState
            // don't use postValue() here
            // ensures the LiveData is updating by only one thread
            viewModel.filterMap.value = viewModel.filterMap.value
            viewModel.saveFilterMap()
        }
    }

    /**
     * Notify the application of a resource request and allow the application to return the data.
     *
     * If the return value is null, the WebView will continue to load the resource as usual.
     * Otherwise, the return response and data will be used.
     *
     * NOTE: This method is called on a thread other than the UI thread so clients should exercise
     * caution when accessing private data or the view system.
     */
    fun shouldIntercept(
        webView: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        return runBlocking {
            if (request.isForMainFrame) {
                return@runBlocking null
            }

            val url = request.url.toString()
            val documentUrl =
                withContext(Dispatchers.Main) { webView.url } ?: return@runBlocking null

            val shouldBlock = detector.shouldBlock(url, documentUrl, ResourceType.from(request))
            if (shouldBlock)
                WebResourceResponse(null, null, null)
            else
                return@runBlocking null
        }
    }

    companion object {
        @Volatile
        private var instance: AdFilter? = null

        fun get(): AdFilter =
            instance ?: throw RuntimeException("Should call create() before get()")

        fun get(context: Context): AdFilter {
            return instance ?: synchronized(this) {
                // keep application context rather than any other context to avoid memory leak
                instance = instance ?: AdFilter(context.applicationContext)
                instance!!
            }
        }

        fun create(context: Context): AdFilter = get(context)
    }
}