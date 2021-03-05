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
package com.github.k1rakishou.chan.core.cache

import android.os.Environment
import com.github.k1rakishou.ChanSettings
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils
import com.github.k1rakishou.chan.utils.BackgroundUtils
import com.github.k1rakishou.chan.utils.ConversionUtils.charArrayToInt
import com.github.k1rakishou.chan.utils.ConversionUtils.intToCharArray
import com.github.k1rakishou.chan.utils.HashingUtil
import com.github.k1rakishou.common.AndroidUtils
import com.github.k1rakishou.common.StringUtils
import com.github.k1rakishou.common.mbytesToBytes
import com.github.k1rakishou.common.mutableListWithCap
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.fsaf.FileManager
import com.github.k1rakishou.fsaf.file.AbstractFile
import com.github.k1rakishou.fsaf.file.FileDescriptorMode
import com.github.k1rakishou.fsaf.file.FileSegment
import com.github.k1rakishou.fsaf.file.RawFile
import com.github.k1rakishou.model.util.ChanPostUtils
import org.joda.time.format.DateTimeFormatterBuilder
import org.joda.time.format.ISODateTimeFormat
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.io.PrintWriter
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * CacheHandler has been re-worked a little bit because old implementation was relying on the
 * lastModified file flag which doesn't work on some Android versions/different phones. It was decided
 * to instead use a meta file for every cache file which will contain the following information:
 * 1. Time of creation of the cache file (in millis).
 * 2. A flag that indicates whether the download has been completed or not.
 *
 * We need creation time to not delete cache file for active downloads or for downloads that has
 * just been completed (otherwise the user may see a black screen instead of an image/webm). The
 * minimum cache file life time is 5 minutes. That means we won't delete any cache files (and their
 * meta files) for at least 5 minutes.
 *
 * CacheHandler now also caches file chunks that are used by [ConcurrentChunkedFileDownloader] as well
 * as all media files retrieved via [ImageLoaderV2]
 */
class CacheHandler(
  private val fileManager: FileManager,
  private val cacheDirFile: RawFile,
  private val chunksCacheDirFile: RawFile,
  private val autoLoadThreadImages: Boolean
) {
  private val executor = Executors.newSingleThreadExecutor()

  /**
   * An estimation of the current size of the directory. Used to check if trim must be run
   * because the folder exceeds the maximum size.
   */
  private val size = AtomicLong()
  private val lastTrimTime = AtomicLong(0)
  private val trimRunning = AtomicBoolean(false)
  private val recalculationRunning = AtomicBoolean(false)
  private val trimChunksRunning = AtomicBoolean(false)
  private val directoriesChecked = AtomicBoolean(false)

  @Suppress("JoinDeclarationAndAssignment")
  private val fileCacheDiskSizeBytes: Long

  init {
    fileCacheDiskSizeBytes = if (autoLoadThreadImages) {
      ChanSettings.prefetchDiskCacheSizeMegabytes.get().mbytesToBytes()
    } else {
      ChanSettings.diskCacheSizeMegabytes.get().mbytesToBytes()
    }

    Logger.d(TAG, "fileCacheDiskSizeMBytes=${(fileCacheDiskSizeBytes / (1024L * 1024L))}")

    backgroundRecalculateSize()
    clearChunksCacheDir()
  }

  @Synchronized
  fun getCacheFileOrNull(url: String): RawFile? {
    createDirectories()
    val cacheFile = getCacheFileInternal(url)

    try {
      if (!fileManager.exists(cacheFile)) {
        return null
      }

      if (!fileManager.exists(getCacheFileMetaInternal(url))) {
        return null
      }

      return cacheFile
    } catch (error: IOException) {
      Logger.e(TAG, "Error while trying to get cache file", error)
      deleteCacheFile(cacheFile)
      return null
    }
  }

  /**
   * Either returns already downloaded file or creates an empty new one on the disk (also creates
   * cache file meta with default parameters)
   * */
  @Synchronized
  fun getOrCreateCacheFile(url: String): RawFile? {
    createDirectories()
    var cacheFile = getCacheFileInternal(url)

    try {
      if (!fileManager.exists(cacheFile)) {
        val createdFile = fileManager.create(cacheFile) as RawFile?
          ?: throw IOException(
            "Couldn't create cache file, path = ${cacheFile.getFullPath()}")

        cacheFile = createdFile
      }

      val cacheFileMeta = getCacheFileMetaInternal(url)
      if (!fileManager.exists(cacheFileMeta)) {
        val createdFile = fileManager.create(cacheFileMeta) as RawFile?
          ?: throw IOException(
            "Couldn't create cache file meta, path = ${cacheFileMeta.getFullPath()}")

        val result = updateCacheFileMeta(
          createdFile,
          true,
          System.currentTimeMillis(),
          false
        )

        if (!result) {
          throw IOException("Cache file meta update failed")
        }
      }

      return cacheFile
    } catch (error: IOException) {
      Logger.e(TAG, "Error while trying to get or create cache file", error)
      deleteCacheFile(cacheFile)
      return null
    }
  }

  @Synchronized
  fun getChunkCacheFileOrNull(chunkStart: Long, chunkEnd: Long, url: String): RawFile? {
    val chunkCacheFile = getChunkCacheFileInternal(chunkStart, chunkEnd, url)

    if (fileManager.exists(chunkCacheFile)) {
      return chunkCacheFile
    }

    return null
  }

  @Synchronized
  fun getOrCreateChunkCacheFile(chunkStart: Long, chunkEnd: Long, url: String): RawFile? {
    val chunkCacheFile = getChunkCacheFileInternal(chunkStart, chunkEnd, url)

    if (fileManager.exists(chunkCacheFile)) {
      if (!fileManager.delete(chunkCacheFile)) {
        throw IOException("Couldn't delete old chunk cache file")
      }
    }

    return fileManager.create(chunkCacheFile) as RawFile?
  }

  @Synchronized
  fun cacheFileExists(fileUrl: String): Boolean {
    return fileManager.exists(getCacheFileInternal(fileUrl))
  }

  @Synchronized
  fun deleteCacheFileByUrl(url: String): Boolean {
    return deleteCacheFile(hashUrl(url))
  }

  @Synchronized
  fun isAlreadyDownloaded(fileUrl: String): Boolean {
    return isAlreadyDownloaded(getCacheFileInternal(fileUrl))
  }

  /**
   * Checks whether this file is already downloaded by reading it's meta info. If a file has no
   * meta info or it cannot be read - deletes the file so it can be re-downloaded again with all
   * necessary information
   *
   * [cacheFile] must be the cache file, not cache file meta!
   * */
  @Synchronized
  fun isAlreadyDownloaded(cacheFile: RawFile): Boolean {
    try {
      createDirectories()

      if (!fileManager.exists(cacheFile)) {
        deleteCacheFile(cacheFile)
        return false
      }

      if (!fileManager.getName(cacheFile).endsWith(CACHE_EXTENSION)) {
        Logger.e(TAG, "Not a cache file! file = " + cacheFile.getFullPath())
        deleteCacheFile(cacheFile)
        return false
      }

      val cacheFileMetaFile = getCacheFileMetaByCacheFile(cacheFile)
      if (cacheFileMetaFile == null) {
        Logger.e(
          TAG, "Couldn't get cache file meta by cache file, " +
          "file = ${cacheFile.getFullPath()}"
        )
        deleteCacheFile(cacheFile)
        return false
      }

      if (!fileManager.exists(cacheFileMetaFile)) {
        Logger.e(
          TAG, "Cache file meta does not exist, " +
          "cacheFileMetaFile = ${cacheFileMetaFile.getFullPath()}"
        )

        deleteCacheFile(cacheFile)
        return false
      }

      if (fileManager.getLength(cacheFileMetaFile) <= 0) {
        // File is empty
        deleteCacheFile(cacheFile)
        return false
      }

      val cacheFileMeta = readCacheFileMeta(cacheFileMetaFile)
      if (cacheFileMeta == null) {
        deleteCacheFile(cacheFile)
        return false
      }

      return cacheFileMeta.isDownloaded
    } catch (error: Throwable) {
      Logger.e(TAG, "Error while trying to check whether the file is already downloaded", error)
      deleteCacheFile(cacheFile)
      return false
    }
  }

  /**
   * Marks the file as a downloaded (sets a flag in it's meta info). If meta info cannot be read
   * deletes the file so it can be re-downloaded again.
   * */
  @Synchronized
  fun markFileDownloaded(output: AbstractFile): Boolean {
    try {
      createDirectories()

      if (!fileManager.exists(output)) {
        Logger.e(TAG, "File does not exist! file = ${output.getFullPath()}")
        deleteCacheFile(output)
        return false
      }

      val cacheFileMeta = getCacheFileMetaByCacheFile(output)
      if (cacheFileMeta == null) {
        deleteCacheFile(output)
        return false
      }

      val updateResult = updateCacheFileMeta(
        cacheFileMeta,
        false,
        null,
        true
      )

      if (!updateResult) {
        deleteCacheFile(output)
      }

      return updateResult
    } catch (error: Throwable) {
      Logger.e(TAG, "Error while trying to mark file as downloaded", error)
      deleteCacheFile(output)
      return false
    }
  }

  fun getSize(): Long {
    return size.get()
  }

  /**
   * When a file is downloaded we add it's size to the total cache directory size variable and
   * check whether it exceeds the maximum cache size or not. If it does then the trim() operation
   * is executed in a background thread.
   * */
  @Synchronized
  fun fileWasAdded(fileLen: Long) {
    val totalSize = size.addAndGet(fileLen.coerceAtLeast(0))
    val trimTime = lastTrimTime.get()
    val now = System.currentTimeMillis()

    val minTrimInterval = if (AppModuleAndroidUtils.isDevBuild()) {
      0
    } else {
      // If the user scrolls through high-res images very fast we may end up in a situation
      // where the cache limit is hit but all the files in it were created earlier than
      // MIN_CACHE_FILE_LIFE_TIME ago. So in such case trim() will be called on EVERY
      // new opened image and since trim() is a pretty slow operation it may overload the
      // disk IO. So to avoid it we run trim() only once per MIN_TRIM_INTERVAL.
      MIN_TRIM_INTERVAL
    }

    if (AppModuleAndroidUtils.isDevBuild()) {
      Logger.d(TAG, "fileWasAdded() " +
        "fileLen=${ChanPostUtils.getReadableFileSize(fileLen)}, " +
        "totalSize=${ChanPostUtils.getReadableFileSize(totalSize)}")
    }

    val canRunTrim = totalSize > fileCacheDiskSizeBytes
      && now - trimTime > minTrimInterval
      && trimRunning.compareAndSet(false, true)

    if (canRunTrim) {
      executor.execute {
        try {
          trim()
        } catch (e: Exception) {
          Logger.e(TAG, "trim() error", e)
        } finally {
          lastTrimTime.set(now)
          trimRunning.set(false)
        }
      }
    }
  }

  /**
   * For now only used in developer settings. Clears the cache completely.
   * */
  @Synchronized
  fun clearCache() {
    Logger.d(TAG, "Clearing cache")

    if (fileManager.exists(cacheDirFile) && fileManager.isDirectory(cacheDirFile)) {
      for (file in fileManager.listFiles(cacheDirFile)) {
        if (!deleteCacheFile(file)) {
          Logger.d(
            TAG,
            "Could not delete cache file while clearing" +
              " cache ${fileManager.getName(file)}"
          )
        }
      }
    }

    if (fileManager.exists(chunksCacheDirFile) && fileManager.isDirectory(chunksCacheDirFile)) {
      for (file in fileManager.listFiles(chunksCacheDirFile)) {
        if (!fileManager.delete(file)) {
          Logger.d(
            TAG,
            "Could not delete cache chunk file while clearing" +
              " cache ${fileManager.getName(file)}"
          )
        }
      }
    }

    recalculateSize()
  }

  /**
   * Deletes a cache file with it's meta. Also decreases the total cache size variable by the size
   * of the file.
   * */
  @Synchronized
  fun deleteCacheFile(cacheFile: AbstractFile): Boolean {
    val fileName = fileManager.getName(cacheFile)

    return deleteCacheFile(fileName)
  }

  private fun deleteCacheFile(fileName: String): Boolean {
    val originalFileName = StringUtils.removeExtensionFromFileName(fileName)

    if (originalFileName.isEmpty()) {
      Logger.e(TAG, "Couldn't parse original file name, fileName = $fileName")
      return false
    }

    val cacheFileName = formatCacheFileName(originalFileName)
    val cacheMetaFileName = formatCacheFileMetaName(originalFileName)

    val cacheFile = cacheDirFile.clone(FileSegment(cacheFileName)) as RawFile
    val cacheMetaFile = cacheDirFile.clone(FileSegment(cacheMetaFileName)) as RawFile
    val cacheFileSize = fileManager.getLength(cacheFile)

    val deleteCacheFileResult = fileManager.delete(cacheFile)
    if (!deleteCacheFileResult) {
      Logger.e(TAG, "Failed to delete cache file, fileName = ${cacheFile.getFullPath()}")
    }

    val deleteCacheFileMetaResult = fileManager.delete(cacheMetaFile)
    if (!deleteCacheFileMetaResult) {
      Logger.e(TAG, "Failed to delete cache file meta = ${cacheMetaFile.getFullPath()}")
    }

    if (deleteCacheFileResult && deleteCacheFileMetaResult) {
      val fileSize = if (cacheFileSize < 0) {
        0
      } else {
        cacheFileSize
      }

      if (fileSize > 0) {
        size.getAndAdd(-fileSize)
        if (size.get() < 0L) {
          size.set(0L)
        }

        Logger.d(TAG, "Deleted $cacheFileName and it's meta $cacheMetaFileName, " +
          "fileSize = $fileSize, cache size = ${size.get()}")
      }

      return true
    }

    // Only one of the files could be deleted
    return false
  }

  private fun getCacheFileMetaByCacheFile(cacheFile: AbstractFile): AbstractFile? {
    val fileNameWithExtension = fileManager.getName(cacheFile)
    if (!fileNameWithExtension.endsWith(CACHE_EXTENSION)) {
      Logger.e(TAG, "Bad file (not a cache file), file = ${cacheFile.getFullPath()}")
      return null
    }

    val originalFileName = StringUtils.removeExtensionFromFileName(fileNameWithExtension)
    if (originalFileName.isEmpty()) {
      Logger.e(TAG, "Bad fileNameWithExtension, fileNameWithExtension = $fileNameWithExtension")
      return null
    }

    return synchronized(this) {
      cacheDirFile.clone(FileSegment(formatCacheFileMetaName(originalFileName)))
    }
  }

  @Throws(IOException::class)
  private fun updateCacheFileMeta(
    file: AbstractFile,
    overwrite: Boolean,
    createdOn: Long?,
    fileDownloaded: Boolean?
  ): Boolean {
    if (!fileManager.exists(file)) {
      Logger.e(TAG, "Cache file meta does not exist!")
      return false
    }

    if (!fileManager.getName(file).endsWith(CACHE_META_EXTENSION)) {
      Logger.e(TAG, "Not a cache file meta! file = ${file.getFullPath()}")
      return false
    }

    val prevCacheFileMeta = readCacheFileMeta(file).let { cacheFileMeta ->
      when {
        !overwrite && cacheFileMeta != null -> {
          require(!(createdOn == null && fileDownloaded == null)) {
            "Only one parameter may be null when updating!"
          }

          val updatedCreatedOn = createdOn ?: cacheFileMeta.createdOn
          val updatedFileDownloaded = fileDownloaded ?: cacheFileMeta.isDownloaded

          return@let CacheFileMeta(
            CURRENT_META_FILE_VERSION,
            updatedCreatedOn,
            updatedFileDownloaded
          )
        }
        else -> {
          if (createdOn == null || fileDownloaded == null) {
            throw IOException(
              "Both parameters must not be null when writing! " +
                "(Probably prevCacheFileMeta couldn't be read, check the logs)"
            )
          }

          return@let CacheFileMeta(CURRENT_META_FILE_VERSION, createdOn, fileDownloaded)
        }
      }
    }

    val outputStream = fileManager.getOutputStream(file)
    if (outputStream == null) {
      Logger.e(TAG, "Couldn't create OutputStream for file = ${file.getFullPath()}")
      return false
    }

    return outputStream.use { stream ->
      return@use PrintWriter(stream).use { pw ->
        val toWrite = String.format(
          Locale.ENGLISH,
          CACHE_FILE_META_CONTENT_FORMAT,
          CURRENT_META_FILE_VERSION,
          prevCacheFileMeta.createdOn,
          prevCacheFileMeta.isDownloaded
        )

        val lengthChars = intToCharArray(toWrite.length)
        pw.write(lengthChars)
        pw.write(toWrite)
        pw.flush()

        return@use true
      }
    }
  }

  @Throws(IOException::class)
  private fun readCacheFileMeta(cacheFileMate: AbstractFile): CacheFileMeta? {
    if (!fileManager.exists(cacheFileMate)) {
      throw IOException("Cache file meta does not exist, path = ${cacheFileMate.getFullPath()}")
    }

    if (!fileManager.isFile(cacheFileMate)) {
      throw IOException("Input file is not a file!")
    }

    if (!fileManager.canRead(cacheFileMate)) {
      throw IOException("Couldn't read cache file meta")
    }

    if (fileManager.getLength(cacheFileMate) <= 0) {
      // This is a valid case
      return null
    }

    if (!fileManager.getName(cacheFileMate).endsWith(CACHE_META_EXTENSION)) {
      throw IOException("Not a cache file meta! file = ${cacheFileMate.getFullPath()}")
    }

    return fileManager.withFileDescriptor(cacheFileMate, FileDescriptorMode.Read) { fd ->
      return@withFileDescriptor FileReader(fd).use { reader ->
        val lengthBuffer = CharArray(CACHE_FILE_META_HEADER_SIZE)

        var read = reader.read(lengthBuffer)
        if (read != CACHE_FILE_META_HEADER_SIZE) {
          throw IOException(
            "Couldn't read content size of cache file meta, read $read"
          )
        }

        val length = charArrayToInt(lengthBuffer)
        if (length < 0 || length > MAX_CACHE_META_SIZE) {
          throw IOException("Cache file meta is too big or negative (${length} bytes)." +
            " It was probably corrupted. Deleting it.")
        }

        val contentBuffer = CharArray(length)
        read = reader.read(contentBuffer)

        if (read != length) {
          throw IOException("Couldn't read content cache file meta, read = $read, expected = $length")
        }

        val content = String(contentBuffer)
        val split = content.split(",").toTypedArray()

        if (split.size != CacheFileMeta.PARTS_COUNT) {
          throw IOException("Couldn't split meta content ($content), split.size = ${split.size}")
        }

        val fileVersion = split[0].toInt()
        if (fileVersion != CURRENT_META_FILE_VERSION) {
          throw IOException("Bad file version: $fileVersion")
        }

        return@use CacheFileMeta(
          fileVersion,
          split[1].toLong(),
          split[2].toBoolean()
        )
      }
    }
  }

  private fun getCacheFileInternal(url: String): RawFile {
    createDirectories()

    val fileName = formatCacheFileName(hashUrl(url))
    return cacheDirFile.clone(FileSegment(fileName)) as RawFile
  }

  private fun getChunkCacheFileInternal(
    chunkStart: Long,
    chunkEnd: Long,
    url: String
  ): RawFile {
    createDirectories()

    val fileName = formatChunkCacheFileName(chunkStart, chunkEnd, hashUrl(url))
    return chunksCacheDirFile.clone(FileSegment(fileName)) as RawFile
  }

  internal fun getCacheFileMetaInternal(url: String): RawFile {
    createDirectories()

    // AbstractFile expects all file names to have extensions
    val fileName = formatCacheFileMetaName(hashUrl(url))
    return cacheDirFile.clone(FileSegment(fileName)) as RawFile
  }

  internal fun hashUrl(url: String): String {
    return HashingUtil.stringHash(url)
  }

  private fun formatChunkCacheFileName(
    chunkStart: Long,
    chunkEnd: Long,
    originalFileName: String
  ): String {
    return String.format(
      Locale.ENGLISH,
      CHUNK_CACHE_FILE_NAME_FORMAT,
      originalFileName,
      chunkStart,
      chunkEnd,
      // AbstractFile expects all file names to have extensions
      CHUNK_CACHE_EXTENSION
    )
  }

  private fun formatCacheFileName(originalFileName: String): String {
    return String.format(
      Locale.ENGLISH,
      CACHE_FILE_NAME_FORMAT,
      originalFileName,
      // AbstractFile expects all file names to have extensions
      CACHE_EXTENSION
    )
  }

  private fun formatCacheFileMetaName(originalFileName: String): String {
    return String.format(
      Locale.ENGLISH,
      CACHE_FILE_NAME_FORMAT,
      originalFileName,
      // AbstractFile expects all file names to have extensions
      CACHE_META_EXTENSION
    )
  }

  private fun createDirectories() {
    if (!directoriesChecked.compareAndSet(false, true)) {
      return
    }

    if (!fileManager.exists(cacheDirFile) && fileManager.create(cacheDirFile) == null) {
      val rawFile = File(cacheDirFile.getFullPath())
      if (!rawFile.exists() && !rawFile.mkdirs()) {
        throw RuntimeException(
          "Unable to create file cache dir ${cacheDirFile.getFullPath()}, " +
            "additional info = ${getAdditionalDebugInfo(rawFile)}")
      } else {
        Logger.e(TAG, "fileManager.create failed, but rawFile.mkdirs() succeeded, " +
          "cacheDirFile = ${cacheDirFile.getFullPath()}")
      }
    }

    if (!fileManager.exists(chunksCacheDirFile) && fileManager.create(chunksCacheDirFile) == null) {
      val rawFile = File(chunksCacheDirFile.getFullPath())
      if (!rawFile.exists() && !rawFile.mkdirs()) {
        throw RuntimeException(
          "Unable to create file chunks cache dir ${chunksCacheDirFile.getFullPath()}, " +
            "additional info = ${getAdditionalDebugInfo(rawFile)}")
      } else {
        Logger.e(TAG, "fileManager.create failed, but rawFile.mkdirs() succeeded, " +
          "chunksCacheDirFile = ${chunksCacheDirFile.getFullPath()}")
      }
    }
  }

  private fun getAdditionalDebugInfo(file: File): String {
    val state = Environment.getExternalStorageState(file)
    val externalCacheDir = AndroidUtils.getAppContext().externalCacheDir?.absolutePath ?: "<null>"
    val internalCacheDir = AndroidUtils.getAppContext().cacheDir ?: "<null>"
    val availableSpace = AppModuleAndroidUtils.getAvailableSpaceInBytes(file)

    return "(exists = ${file.exists()}, " +
      "parent exists = ${file.parentFile?.exists()}, " +
      "canRead = ${file.canRead()}, " +
      "canWrite = ${file.canWrite()}, " +
      "isDirectory = ${file.isDirectory}, " +
      "state = ${state}, " +
      "availableSpace = ${availableSpace}, " +
      "externalCacheDir = ${externalCacheDir}, " +
      "internalCacheDir = ${internalCacheDir})"
  }

  private fun clearChunksCacheDir() {
    if (trimChunksRunning.compareAndSet(false, true)) {
      executor.execute { clearChunksCacheDirInternal() }
    }
  }

  @Synchronized
  private fun clearChunksCacheDirInternal() {
    if (trimChunksRunning.get()) {
      return
    }

    try {
      if (fileManager.exists(chunksCacheDirFile)) {
        fileManager.deleteContent(chunksCacheDirFile)
      }
    } finally {
      trimChunksRunning.set(false)
    }
  }

  private fun backgroundRecalculateSize() {
    if (recalculationRunning.get()) {
      return
    }

    executor.execute { recalculateSize() }
  }

  @Synchronized
  private fun recalculateSize() {
    var calculatedSize: Long = 0

    if (!recalculationRunning.compareAndSet(false, true)) {
      return
    }

    try {
      val files = fileManager.listFiles(cacheDirFile)

      for (file in files) {
        if (fileManager.getName(file).endsWith(CACHE_META_EXTENSION)) {
          continue
        }

        calculatedSize += fileManager.getLength(file)
      }

      size.set(calculatedSize)
    } finally {
      recalculationRunning.set(false)
    }
  }

  @Synchronized
  private fun trim() {
    BackgroundUtils.ensureBackgroundThread()
    createDirectories()

    val directoryFiles = fileManager.listFiles(cacheDirFile)
    // Don't try to trim empty directories or just two files in it.
    // Two (not one) because for every cache file we now have a cache file meta with some
    // additional info.
    if (directoryFiles.size <= 2) {
      return
    }

    val start = System.currentTimeMillis()
    Logger.d(TAG, "trim() started")

    // LastModified doesn't work on some platforms/phones
    // (https://issuetracker.google.com/issues/36930892)
    // so we have to use a workaround. When creating a cache file for a download we also create a
    // meta file where we will put some info about this download: the main file creation time and
    // a flag that will tell us whether the download is complete or not. So now we need to parse
    // the creation time from the meta file to sort cache files in ascending order (from the
    // oldest cache file to the newest).

    val sortedFiles = groupFilterAndSortFiles(directoryFiles)
    var totalDeleted = 0L
    var filesDeleted = 0
    val now = System.currentTimeMillis()

    val sizeToFree = size.get().let { currentCacheSize ->
      if (currentCacheSize > fileCacheDiskSizeBytes) {
        currentCacheSize / 2
      } else {
        fileCacheDiskSizeBytes / 2
      }
    }

    // We either delete all files we can in the cache directory or at most half of the cache
    for (cacheFile in sortedFiles) {
      val file = cacheFile.file
      val createdOn = cacheFile.createdOn

      val minCacheFileLifeTime = if (AppModuleAndroidUtils.isDevBuild()) {
        0
      } else {
        // Do not delete fresh files because it may happen right at the time user switched
        // to it. Since the list is sorted there is no point to iterate it anymore since all
        // the following files will be "too young" to be deleted so we just break out of
        // the loop.
        MIN_CACHE_FILE_LIFE_TIME
      }

      if (now - createdOn < minCacheFileLifeTime) {
        break
      }

      if (totalDeleted >= sizeToFree) {
        break
      }

      val fileSize = fileManager.getLength(file)

      if (deleteCacheFile(file)) {
        totalDeleted += fileSize
        ++filesDeleted
      }
    }

    recalculateSize()

    val timeDiff = System.currentTimeMillis() - start
    Logger.d(TAG, "trim() ended (took ${timeDiff} ms), filesDeleted=$filesDeleted, " +
      "total space freed=${ChanPostUtils.getReadableFileSize(totalDeleted)}")
  }

  private fun groupFilterAndSortFiles(directoryFiles: List<AbstractFile>): List<CacheFile> {
    BackgroundUtils.ensureBackgroundThread()

    val groupedCacheFiles = filterAndGroupCacheFilesWithMeta(directoryFiles)
    val cacheFiles = ArrayList<CacheFile>(groupedCacheFiles.size)

    for ((abstractFile, abstractFileMeta) in groupedCacheFiles) {
      val cacheFileMeta = try {
        readCacheFileMeta(abstractFileMeta)
      } catch (error: IOException) {
        null
      }

      if (cacheFileMeta == null) {
        Logger.e(TAG, "Couldn't read cache meta for file = ${abstractFile.getFullPath()}")

        if (!deleteCacheFile(abstractFile)) {
          Logger.e(TAG, "Couldn't delete cache file with meta for file = ${abstractFile.getFullPath()}")
        }
        continue
      }

      cacheFiles.add(CacheFile(abstractFile, cacheFileMeta))
    }

    // Sort in ascending order, the oldest files are in the beginning of the list
    Collections.sort(cacheFiles, CACHE_FILE_COMPARATOR)
    return cacheFiles
  }

  private fun filterAndGroupCacheFilesWithMeta(
    directoryFiles: List<AbstractFile>
  ): List<GroupedCacheFile> {
    BackgroundUtils.ensureBackgroundThread()

    val grouped = directoryFiles
      .map { file -> Pair(file, fileManager.getName(file)) }
      .filter { (_, fileName) ->
        // Either cache file or cache meta
        fileName.endsWith(CACHE_EXTENSION) || fileName.endsWith(CACHE_META_EXTENSION)
      }
      .groupBy { (_, fileName) ->
        StringUtils.removeExtensionFromFileName(fileName)
      }

    val groupedCacheFileList = mutableListWithCap<GroupedCacheFile>(grouped.size / 2)

    for ((fileName, groupOfFiles) in grouped) {
      // We have already filtered all non-cache related files so it's safe to delete them here.
      // We delete files without where either the cache file or cache file meta (or both) are
      // missing.
      if (groupOfFiles.isEmpty()) {
        deleteCacheFile(fileName)
        continue
      }

      // We also handle a hypothetical case where there are more than one cache file/meta with
      // the same name
      if (groupOfFiles.size != 2) {
        groupOfFiles.forEach { (file, _) -> deleteCacheFile(file) }
        continue
      }

      val (file1, fileName1) = groupOfFiles[0]
      val (file2, fileName2) = groupOfFiles[1]

      val cacheFile = when {
        fileName1.endsWith(CACHE_EXTENSION) -> file1
        fileName2.endsWith(CACHE_EXTENSION) -> file2
        else -> throw IllegalStateException(
          "Neither of grouped files is a cache file! " +
            "fileName1 = $fileName1, fileName2 = $fileName2"
        )
      }

      val cacheFileMeta = when {
        fileName1.endsWith(CACHE_META_EXTENSION) -> file1
        fileName2.endsWith(CACHE_META_EXTENSION) -> file2
        else -> throw IllegalStateException(
          "Neither of grouped files is a cache file meta! " +
            "fileName1 = $fileName1, fileName2 = $fileName2"
        )
      }

      groupedCacheFileList += GroupedCacheFile(
        cacheFile as RawFile,
        cacheFileMeta as RawFile
      )
    }

    return groupedCacheFileList
  }

  private data class GroupedCacheFile(
    val cacheFile: RawFile,
    val cacheFileMeta: RawFile
  )

  private class CacheFile(
    val file: AbstractFile,
    private val cacheFileMeta: CacheFileMeta
  ) {

    val createdOn: Long
      get() = cacheFileMeta.createdOn

    override fun toString(): String {
      return "CacheFile{" +
        "file=${file.getFullPath()}" +
        ", cacheFileMeta=${cacheFileMeta}" +
        "}"
    }

  }

  internal class CacheFileMeta(
    val version: Int = CURRENT_META_FILE_VERSION,
    val createdOn: Long,
    val isDownloaded: Boolean
  ) {

    override fun toString(): String {
      return "CacheFileMeta{" +
        "createdOn=${formatter.print(createdOn)}" +
        ", downloaded=$isDownloaded" +
        '}'
    }

    companion object {
      const val PARTS_COUNT = 3

      private val formatter = DateTimeFormatterBuilder()
        .append(ISODateTimeFormat.date())
        .appendLiteral(' ')
        .append(ISODateTimeFormat.hourMinuteSecond())
        .appendTimeZoneOffset(null, true, 2, 2)
        .toFormatter()
    }
  }

  companion object {
    private const val TAG = "CacheHandler"

    private const val CURRENT_META_FILE_VERSION = 1
    private const val CACHE_FILE_META_HEADER_SIZE = 4

    // I don't think it will ever get this big but just in case don't forget to update it if it
    // ever gets
    private const val MAX_CACHE_META_SIZE = 1024L

    private const val CACHE_FILE_NAME_FORMAT = "%s.%s"
    private const val CHUNK_CACHE_FILE_NAME_FORMAT = "%s_%d_%d.%s"
    private const val CACHE_FILE_META_CONTENT_FORMAT = "%d,%d,%b"
    internal const val CACHE_EXTENSION = "cache"
    internal const val CACHE_META_EXTENSION = "cache_meta"
    internal const val CHUNK_CACHE_EXTENSION = "chunk"

    private val MIN_CACHE_FILE_LIFE_TIME = TimeUnit.MINUTES.toMillis(1)
    private val MIN_TRIM_INTERVAL = TimeUnit.SECONDS.toMillis(15)

    private val CACHE_FILE_COMPARATOR = Comparator<CacheFile> { cacheFile1, cacheFile2 ->
      cacheFile1.createdOn.compareTo(cacheFile2.createdOn)
    }
  }
}