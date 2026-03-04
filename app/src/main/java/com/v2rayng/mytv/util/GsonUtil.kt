package com.v2rayng.mytv.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * 全局 Gson 实例，注册了宽松的 String TypeAdapter：
 * 当 JSON 中的字段是数字（如 "tls": 1）但 Kotlin 属性是 String 时，
 * 自动将数字转为字符串，避免 JsonSyntaxException。
 */
object GsonUtil {

    val gson: Gson = GsonBuilder()
        .registerTypeAdapter(String::class.java, LenientStringAdapter())
        .create()

    private class LenientStringAdapter : TypeAdapter<String?>() {
        override fun write(out: JsonWriter, value: String?) {
            if (value == null) out.nullValue() else out.value(value)
        }

        override fun read(reader: JsonReader): String? {
            return when (reader.peek()) {
                JsonToken.NULL -> {
                    reader.nextNull()
                    null
                }
                JsonToken.NUMBER -> {
                    val raw = reader.nextString() // nextString() works for NUMBER tokens too
                    raw
                }
                JsonToken.BOOLEAN -> {
                    reader.nextBoolean().toString()
                }
                JsonToken.BEGIN_OBJECT, JsonToken.BEGIN_ARRAY -> {
                    // JSON 对象/数组型字段（如 networkSettings 有时是 object）→ 序列化为 JSON 字符串
                    val sb = StringBuilder()
                    readFully(reader, sb)
                    sb.toString()
                }
                else -> reader.nextString()
            }
        }

        private fun readFully(reader: JsonReader, sb: StringBuilder) {
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    sb.append('{')
                    var first = true
                    while (reader.hasNext()) {
                        if (!first) sb.append(',')
                        first = false
                        sb.append('"').append(reader.nextName()).append("\":")
                        readFully(reader, sb)
                    }
                    reader.endObject()
                    sb.append('}')
                }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    sb.append('[')
                    var first = true
                    while (reader.hasNext()) {
                        if (!first) sb.append(',')
                        first = false
                        readFully(reader, sb)
                    }
                    reader.endArray()
                    sb.append(']')
                }
                JsonToken.STRING -> sb.append('"').append(reader.nextString().replace("\"", "\\\"")).append('"')
                JsonToken.NUMBER -> sb.append(reader.nextString())
                JsonToken.BOOLEAN -> sb.append(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); sb.append("null") }
                else -> reader.skipValue()
            }
        }
    }
}