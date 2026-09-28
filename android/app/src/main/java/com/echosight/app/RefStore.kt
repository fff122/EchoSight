package com.echosight.app

import android.content.Context
import java.io.File

/** 参考照片存储：AI 确认目标后自动裁剪存档，找它时按照片比对。全部本地，不上云。 */
object RefStore {

    private lateinit var dir: File
    @Volatile private var ready = false

    fun init(context: Context) {
        dir = File(context.filesDir, "memory").apply { mkdirs() }
        ready = true
    }

    fun refsDir(): File = File(dir, "refs").apply { mkdirs() }

    /** 登记过的物品的参考照片（AI 确认帧裁剪保存），找它时按照片比对。 */
    fun refPhoto(name: String): File? =
        if (!ready) null else refsDir().listFiles()
            ?.firstOrNull { it.nameWithoutExtension == name }
}
