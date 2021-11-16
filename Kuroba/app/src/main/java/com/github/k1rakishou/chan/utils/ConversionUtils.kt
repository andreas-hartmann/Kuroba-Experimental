package com.github.k1rakishou.chan.utils

import com.github.k1rakishou.common.groupOrNull
import okhttp3.internal.and
import java.util.*
import java.util.regex.Pattern


object ConversionUtils {
  private val FILE_SIZE_RAW_PATTERN = Pattern.compile("(\\d+).?(\\d+)?\\s+(\\w+)")

  fun fileSizeRawToFileSizeInBytes(input: String): Long? {
    val matcher = FILE_SIZE_RAW_PATTERN.matcher(input)
    if (!matcher.find()) {
      return null
    }

    val value = matcher.groupOrNull(1)?.toIntOrNull()
      ?: return null
    val fileSizeType = matcher.groupOrNull(3)?.uppercase(Locale.ENGLISH)
      ?: return null

    val fraction = matcher.groupOrNull(2)?.let { fractionString ->
      if (fractionString.isEmpty()) {
        return@let null
      }

      return@let fractionString.toIntOrNull()
        ?.toFloat()
        ?.div(Math.pow(10.0, fractionString.length.toDouble()).toInt())
    } ?: 0f

    val fileSizeMultiplier = when (fileSizeType) {
      "GB" -> 1024 * 1024 * 1024
      "MB" -> 1024 * 1024
      "KB" -> 1024
      "B" -> 1
      else -> 1
    }

    return ((value * fileSizeMultiplier).toFloat() + (fraction * fileSizeMultiplier.toFloat())).toLong()
  }

  @JvmStatic
  fun intToByteArray(value: Int): ByteArray {
    return byteArrayOf(
      (value ushr 24).toByte(),
      (value ushr 16).toByte(),
      (value ushr 8).toByte(),
      value.toByte()
    )
  }

  @JvmStatic
  fun intToCharArray(value: Int): CharArray {
    return charArrayOf(
      (value ushr 24).toChar(),
      (value ushr 16).toChar(),
      (value ushr 8).toChar(),
      value.toChar()
    )
  }

  @JvmStatic
  fun byteArrayToInt(bytes: ByteArray): Int {
    return (bytes[0] and 0xFF) shl 24 or
      ((bytes[1] and 0xFF) shl 16) or
      ((bytes[2] and 0xFF) shl 8) or
      ((bytes[3] and 0xFF) shl 0)
  }

  @JvmStatic
  fun charArrayToInt(bytes: CharArray): Int {
    return (bytes[0].toByte() and 0xFF) shl 24 or
      ((bytes[1].toByte() and 0xFF) shl 16) or
      ((bytes[2].toByte() and 0xFF) shl 8) or
      ((bytes[3].toByte() and 0xFF) shl 0)
  }

  @JvmOverloads
  @JvmStatic
  fun toIntOrNull(maybeInt: String, radix: Int = 16): Int? {
    return maybeInt.toIntOrNull(radix)
  }

}