package com.bnyro.recorder.ui.models

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bnyro.recorder.App
import com.bnyro.recorder.enums.SortOrder
import com.bnyro.recorder.obj.RecordingItemData
import com.bnyro.recorder.util.FileRepository
import kotlinx.coroutines.launch

class PlayerModel(context: Context, private val fileRepository: FileRepository) : ViewModel() {
    @UnstableApi
    var player = ExoPlayer.Builder(context)
        .setUsePlatformDiagnostics(false)
        .build()

    var selectedFiles by mutableStateOf(listOf<RecordingItemData>())

    private var sortOrder = SortOrder.MODIFIED

    var audioRecordingItems by mutableStateOf(listOf<RecordingItemData>())
    var screenRecordingItems by mutableStateOf(listOf<RecordingItemData>())

    init {
        loadFiles()
    }

    fun loadFiles() {
        viewModelScope.launch {
            audioRecordingItems = fileRepository.getAudioRecordingItems(sortOrder)
            screenRecordingItems = fileRepository.getVideoRecordingItems(sortOrder)
        }
    }

    fun sortItems(newSortOrder: SortOrder) {
        if (newSortOrder == sortOrder) return
        sortOrder = newSortOrder
        loadFiles()
    }

    /**
     * Deletes whatever's currently selected. If nothing is selected,
     * [fallbackItems] is deleted instead - the caller passes in whichever
     * type's list is currently on screen (audio or video), so "delete all"
     * only ever clears the type you're actually looking at, never both at
     * once.
     */
    fun deleteFiles(fallbackItems: List<RecordingItemData> = emptyList()) {
        viewModelScope.launch {
            val toDelete = selectedFiles.ifEmpty { fallbackItems }
            fileRepository.deleteFiles(toDelete.map { it.recordingFile })
            selectedFiles = emptyList()
            loadFiles()
        }
    }

    fun stopPlaying() {
        player.stop()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as App)
                PlayerModel(application, application.fileRepository)
            }
        }
    }
}
