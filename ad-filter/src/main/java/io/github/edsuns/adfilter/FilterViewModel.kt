package io.github.edsuns.adfilter

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.work.*
import io.github.edsuns.adfilter.workers.DownloadWorker
import io.github.edsuns.adfilter.workers.InstallationWorker
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Created by Edsuns@qq.com on 2021/1/1.
 */
class FilterViewModel internal constructor(
    application: Application,
    private val filterDataLoader: FilterDataLoader
) {

    internal val sharedPreferences: FilterSharedPreferences =
        FilterSharedPreferences(application)

    val isEnabled: MutableLiveData<Boolean> by lazy { MutableLiveData(sharedPreferences.isEnabled) }

    internal val workManager: WorkManager = WorkManager.getInstance(application)

    val workInfo: LiveData<List<WorkInfo>> = workManager.getWorkInfosByTagLiveData(TAG_WORK)

    private val filterMap: MutableLiveData<LinkedHashMap<String, Filter>> by lazy {
        MutableLiveData(Json.decodeFromString(sharedPreferences.filterMap))
    }

    val filters: LiveData<LinkedHashMap<String, Filter>> = filterMap

    val downloadFilterIdMap: HashMap<String, String> by lazy { sharedPreferences.downloadFilterIdMap }

    init {
        workManager.pruneWork()
    }

    fun addFilter(name: String, url: String): Filter {
        val filter = Filter(url)
        filter.name = name
        filterMap.value?.set(filter.id, filter)
        // refresh
        filterMap.postValue(filterMap.value)
        saveFilterMap()
        return filter
    }

    internal fun updateFilter(filter: Filter) {
        filterMap.value?.get(filter.id)?.let {
            it.name = filter.name
            it.updateTime = filter.updateTime
            it.isEnabled = filter.isEnabled
            // refresh
            filterMap.postValue(filterMap.value)
            saveFilterMap()
        }
    }

    fun removeFilter(id: String) {
        cancelDownload(id)
        filterDataLoader.remove(id)
        filterMap.value?.remove(id)
        // refresh
        filterMap.postValue(filterMap.value)
        saveFilterMap()
    }

    fun setFilterEnabled(id: String, enabled: Boolean) {
        setFilterEnabled(id, enabled, true)
    }

    fun setFilterEnabled(id: String, enabled: Boolean, post: Boolean) {
        filterMap.value?.get(id)?.let {
            val enableMask = enabled && it.hasDownloaded()
            if (it.isEnabled != enableMask) {
                if (isEnabled.value == true) {
                    if (enableMask)
                        filterDataLoader.load(it.id)
                    else
                        filterDataLoader.unload(it.id)
                }
                it.isEnabled = enableMask
                // refresh
                if (post)
                    filterMap.postValue(filterMap.value)
                saveFilterMap()
            }
        }
    }

    internal fun enableFilter(id: String) {
        filterMap.value?.get(id)?.let {
            if (isEnabled.value == true)
                filterDataLoader.load(id)
            it.isEnabled = true
            // refresh
            filterMap.postValue(filterMap.value)
            saveFilterMap()
        }
    }

    fun renameFilter(id: String, name: String) {
        filterMap.value?.get(id)?.let {
            it.name = name
            // refresh
            filterMap.postValue(filterMap.value)
            saveFilterMap()
        }
    }

    fun download(id: String) {
        filterMap.value?.get(id)?.let {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresCharging(false)
                .build()
            val inputData = workDataOf(
                KEY_FILTER_ID to it.id,
                KEY_DOWNLOAD_URL to it.url
            )
            val download =
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setConstraints(constraints)
                    .addTag(TAG_WORK)
                    .setInputData(inputData)
                    .build()
            val install =
                OneTimeWorkRequestBuilder<InstallationWorker>()
                    .addTag(TAG_WORK)
                    .addTag(TAG_INSTALLATION)
                    .build()
            val continuation = workManager.beginUniqueWork(
                it.id, ExistingWorkPolicy.KEEP, download
            ).then(install)
            // record worker ids
            downloadFilterIdMap[download.id.toString()] = it.id
            downloadFilterIdMap[install.id.toString()] = it.id
            sharedPreferences.downloadFilterIdMap = downloadFilterIdMap
            // start the work
            continuation.enqueue()
        }
    }

    fun cancelDownload(id: String) {
        workManager.cancelUniqueWork(id)
    }

    private fun saveFilterMap() {
        sharedPreferences.filterMap = Json.encodeToString(filterMap.value)
        Timber.v("FilterViewModel: saveFilterMap")
    }

    companion object {
        private const val TAG_WORK = "tag_ad_filter_work"
    }
}