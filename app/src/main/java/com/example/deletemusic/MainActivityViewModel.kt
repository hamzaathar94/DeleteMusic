package com.example.deletemusic

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Application
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivityViewModel(application: Application,val context: Context) : AndroidViewModel(application) {
    private val _images = MutableLiveData<List<Image>>()
    val images: LiveData<List<Image>> get() = _images

    private var contentObserver: ContentObserver? = null

    private var pendingDeleteImage: Image? = null
    private val _permissionNeededForDelete = MutableLiveData<IntentSender?>()
    val permissionNeededForDelete: LiveData<IntentSender?> = _permissionNeededForDelete

    fun loadMusic(){
        val musicLiveData=MutableLiveData<List<Image>>()

        fun getMusic(){
            val audios= mutableListOf<Image>()
            val sortOrder="${MediaStore.Audio.Media.DATE_ADDED}DESC"
            val cursor=context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                null,null,null,null)
            if (cursor!=null && cursor.moveToFirst()){
                do {
                    val title=
                        cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE))
                    val album=
                        cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM))
                    val artist=
                        cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST))
                    val duration=
                        cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION))
                    val path=
                        cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA))
                    val id=
                        cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id.toLong()
                    )
                    audios.add(Image(id.toLong(),title,contentUri))
                }while (cursor.moveToNext())
                cursor.close()
            }
            musicLiveData.postValue(audios)
        }

        fun getAudiosLiveData(): LiveData<List<Image>> {
            return musicLiveData
        }

    }


    fun loadImages() {
        GlobalScope.launch {
            val imageList = queryImages()
            _images.postValue(imageList)

            if (contentObserver == null) {
                contentObserver = getApplication<Application>().contentResolver.registerObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                ) {
                    loadImages()
                }
            }
        }
    }

    fun deleteImage(image: Image) {
        GlobalScope.launch {
            performDeleteImage(image)
        }
    }

    fun deletePendingImage() {
        pendingDeleteImage?.let { image ->
            pendingDeleteImage = null
            deleteImage(image)
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private suspend fun queryImages(): List<Image> {
        var imageList = mutableListOf<Image>()

        withContext(Dispatchers.IO) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN
            )

            val selection = "${MediaStore.Images.Media.DATE_TAKEN} >= ?"

            val selectionArgs = arrayOf(
                dateToTimestamp(day = 1, month = 1, year = 2020).toString()
            )

            val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

            getApplication<Application>().contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                imageList = addImagesFromCursor(cursor)
            }
        }

        return imageList
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun addImagesFromCursor(cursor: Cursor): MutableList<Image> {
        val images = mutableListOf<Image>()


        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
        val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

        while (cursor.moveToNext()) {

            val id = cursor.getLong(idColumn)
            val dateTaken = Date(cursor.getLong(dateTakenColumn))
            val displayName = cursor.getString(displayNameColumn)

            val contentUri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                id
            )

            val image = Image(id, displayName,contentUri)
            images += image

        }
        return images
    }

    private suspend fun performDeleteImage(image: Image) {
        withContext(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.delete(
                    image.contentUri,
                    "${MediaStore.Images.Media._ID} = ?",
                    arrayOf(image.id.toString()
                    )
                )
            } catch (securityException: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val recoverableSecurityException =
                        securityException as? RecoverableSecurityException
                            ?: throw securityException
                    pendingDeleteImage = image
                    _permissionNeededForDelete.postValue(
                        recoverableSecurityException.userAction.actionIntent.intentSender
                    )
                } else {
                    throw securityException
                }
            }
        }
    }

    @Suppress("SameParameterValue")
    @SuppressLint("SimpleDateFormat")
    private fun dateToTimestamp(day: Int, month: Int, year: Int): Long =
        SimpleDateFormat("dd.MM.yyyy").let { formatter ->
            formatter.parse("$day.$month.$year")?.time ?: 0
        }


    override fun onCleared() {
        contentObserver?.let {
            getApplication<Application>().contentResolver.unregisterContentObserver(it)
        }
    }


}

/**
 * Extension method to register a [ContentObserver]
 */
private fun ContentResolver.registerObserver(
    uri: Uri,
    observer: (selfChange: Boolean) -> Unit
): ContentObserver {
    val contentObserver = object : ContentObserver(Handler()) {
        override fun onChange(selfChange: Boolean) {
            observer(selfChange)
        }
    }
    registerContentObserver(uri, true, contentObserver)
    return contentObserver
}
