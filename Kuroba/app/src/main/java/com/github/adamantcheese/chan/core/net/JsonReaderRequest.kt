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
package com.github.adamantcheese.chan.core.net

import android.util.JsonReader
import com.github.adamantcheese.chan.utils.Logger
import com.github.adamantcheese.common.ModularResult.Companion.Try
import com.github.adamantcheese.common.suspendCall
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue

abstract class JsonReaderRequest<T>(
  protected val requestType: RequestType,
  protected val request: Request,
  private val okHttpClient: OkHttpClient
) {

  @OptIn(ExperimentalTime::class)
  open suspend fun execute(): JsonReaderResponse<T> {
    val response = Try {
      val timedValue = measureTimedValue {
        okHttpClient.suspendCall(request)
      }

      Logger.d(TAG, "Request \"${requestType.requestTag}\" to \"${request.url}\" " +
        "took ${timedValue.duration.inMilliseconds}ms")

      return@Try timedValue.value
    }.safeUnwrap { error ->
      Logger.e(TAG, "Network request error", error)
      return JsonReaderResponse.UnknownServerError(error)
    }

    if (!response.isSuccessful) {
      return JsonReaderResponse.ServerError(response.code)
    }

    if (response.body == null) {
      return JsonReaderResponse.UnknownServerError(IOException("Response has no body"))
    }

    try {
      return response.body!!.use { body ->
        return@use body.byteStream().use { inputStream ->
          return@use JsonReader(InputStreamReader(inputStream, UTF8)).use { jsonReader ->
            return@use JsonReaderResponse.Success(readJson(jsonReader))
          }
        }
      }
    } catch (error: Throwable) {
      return JsonReaderResponse.ParsingError(error)
    }
  }

  protected abstract suspend fun readJson(reader: JsonReader): T

  sealed class JsonReaderResponse<out T> {
    class Success<out T>(val result: T) : JsonReaderResponse<T>()
    class ServerError(val statusCode: Int) : JsonReaderResponse<Nothing>()
    class UnknownServerError(val error: Throwable) : JsonReaderResponse<Nothing>()
    class ParsingError(val error: Throwable) : JsonReaderResponse<Nothing>()
  }

  protected fun <T> JsonReader.withObject(next: JsonReader.() -> T): T {
    beginObject()

    try {
      return next(this)
    } finally {
      endObject()
    }
  }

  protected fun <T> JsonReader.withArray(next: JsonReader.() -> T): T {
    beginArray()

    try {
      return next(this)
    } finally {
      endArray()
    }
  }

  enum class RequestType(val requestTag: String) {
    Chan420BoardsRequest("Chan420Boards"),
    Chan4BoardsRequest("Chan4Boards"),
    Chan4PagesRequest("Chan4Pages"),
    DevUpdateApiRequest("DevUpdateApi"),
    DvachBoardsRequest("DvachBoards"),
    ReleaseUpdateApiRequest("ReleaseUpdateApi")
  }

  companion object {
    private const val TAG = "JsonReaderRequest"
    private val UTF8 = Charset.forName("UTF-8")
  }

}