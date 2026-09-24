package com.echosight.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** SenseAudio 云端 TTS / ASR 接口。 */
class VoiceApi(private val apiKey: String) {

    private val base = "https://api.senseaudio.cn"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 文本转语音，返回 wav 字节；失败返回 null。 */
    fun synthesize(text: String): ByteArray? {
        val body = JSONObject().apply {
            put("model", "sensenova-tts-2.0")
            put("text", text)
            put("stream", false)
            put("voice_setting", JSONObject().apply {
                put("voice_id", "female_0033_b")
                put("speed", 1); put("vol", 1); put("pitch", 0)
            })
            put("audio_setting", JSONObject().apply {
                put("format", "wav"); put("sample_rate", 32000)
                put("channel", 1)
            })
        }
        val req = Request.Builder()
            .url("$base/v1/t2a_v2")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.w("VoiceApi", "TTS失败 ${r.code}")
                    return null
                }
                val audio = JSONObject(r.body!!.string())
                    .getJSONObject("data").getString("audio")
                hexToBytes(audio)
            }
        } catch (e: Exception) {
            Log.w("VoiceApi", "TTS异常 $e")
            null
        }
    }

    /** 上传 wav 识别，返回文本；失败返回空串。 */
    fun transcribe(wav: ByteArray): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "senseaudio-asr-1.5-260319")
            .addFormDataPart("language", "zh")
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "speech.wav",
                wav.toRequestBody(WAV_MEDIA))
            .build()
        val req = Request.Builder()
            .url("$base/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    Log.w("VoiceApi", "ASR失败 ${r.code}")
                    return ""
                }
                val text = JSONObject(r.body!!.string())
                    .optString("text", "").trim()
                Log.i("VoiceApi", "听到: $text")
                text
            }
        } catch (e: Exception) {
            Log.w("VoiceApi", "ASR异常 $e")
            ""
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) +
                    Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val WAV_MEDIA = "audio/wav".toMediaType()

        /** PCM 字节加 44 字节 wav 头。 */
        fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
            val total = pcm.size + 44
            val out = ByteArrayOutputStream(total)
            fun writeShort(v: Int) {
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
            }
            fun writeInt(v: Int) {
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
                out.write((v shr 16) and 0xFF)
                out.write((v shr 24) and 0xFF)
            }
            out.write('R'.code); out.write('I'.code)
            out.write('F'.code); out.write('F'.code)
            writeInt(total - 8)
            out.write('W'.code); out.write('A'.code)
            out.write('V'.code); out.write('E'.code)
            out.write('f'.code); out.write('m'.code)
            out.write('t'.code); out.write(' '.code)
            writeInt(16); writeShort(1); writeShort(1)
            writeInt(sampleRate); writeInt(sampleRate * 2)
            writeShort(2); writeShort(16)
            out.write('d'.code); out.write('a'.code)
            out.write('t'.code); out.write('a'.code)
            writeInt(pcm.size)
            out.write(pcm)
            return out.toByteArray()
        }
    }
}

/** 按住录音、松停止：AudioRecord 持续读到内存。 */
class PushToTalk {

    private val sampleRate = 16000
    private var recorder: AudioRecord? = null
    @Volatile private var recording = false
    private var pcmOut = ByteArrayOutputStream()
    private var thread: Thread? = null

    val isRecording get() = recording

    @SuppressLint("MissingPermission")
    fun start() {
        if (recording) return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, sampleRate * 2) // 至少2秒缓冲
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            bufSize)
        recorder = rec
        pcmOut = ByteArrayOutputStream()
        recording = true
        rec.startRecording()
        thread = Thread {
            val buf = ByteArray(3200)
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) synchronized(pcmOut) { pcmOut.write(buf, 0, n) }
            }
        }.also { it.start() }
    }

    /** 停止并返回 wav 字节。 */
    fun stop(): ByteArray {
        if (!recording) return ByteArray(0)
        recording = false
        thread?.join(1000)
        recorder?.runCatching { stop() }
        recorder?.runCatching { release() }
        recorder = null
        val pcm = synchronized(pcmOut) { pcmOut.toByteArray() }
        return VoiceApi.pcmToWav(pcm, sampleRate)
    }
}
