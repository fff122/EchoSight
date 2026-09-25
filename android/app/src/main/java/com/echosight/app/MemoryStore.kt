package com.echosight.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条物品记忆：位置是语义位置（房间+地标+方位词），不是坐标。 */
data class ItemRec(
    val name: String,
    val room: String,
    val spot: String,
    val pos: String,
    var lastSeen: Long,
    val source: String,      // scan=扫描登记 / manual=口述 / find=找到时更新
)

/**
 * 本地记忆库（方案A）：物品登记、房间快照、个人物品参考照片。
 * 全部存 filesDir/memory/，不上云。AI 是无状态的，"记住"的是这里。
 */
object MemoryStore {

    private lateinit var dir: File
    private val items = LinkedHashMap<String, ItemRec>()
    @Volatile private var ready = false

    fun init(context: Context) {
        dir = File(context.filesDir, "memory").apply { mkdirs() }
        val f = jsonFile()
        if (f.exists()) runCatching {
            val arr = JSONObject(f.readText()).getJSONArray("items")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val rec = ItemRec(
                    o.getString("name"), o.getString("room"),
                    o.optString("spot", ""), o.optString("pos", ""),
                    o.optLong("lastSeen", 0L), o.optString("source", ""))
                items[rec.name] = rec
            }
        }.onFailure { Log.w("MemoryStore", "读取失败 $it") }
        ready = true
    }

    private fun jsonFile() = File(dir, "memory.json")
    fun snapshotsDir() = File(dir, "snapshots").apply { mkdirs() }
    fun refsDir() = File(dir, "refs").apply { mkdirs() }

    /** 登记过的物品的参考照片（扫描打框时裁剪保存），找它时按照片比对。 */
    fun refPhoto(name: String): File? =
        if (!ready) null else refsDir().listFiles()
            ?.firstOrNull { it.nameWithoutExtension == name }

    @Synchronized
    fun upsert(rec: ItemRec) {
        if (!ready) return
        items[rec.name] = rec
        save()
    }

    /** 找到物品时刷新时间；房间变了以最新为准。 */
    @Synchronized
    fun touch(name: String, room: String?) {
        if (!ready) return
        val rec = find(name) ?: return
        rec.lastSeen = System.currentTimeMillis()
        if (room != null && room != rec.room) {
            items.remove(rec.name)
            items[rec.name] = rec.copy(room = room, source = "find")
        }
        save()
    }

    @Synchronized
    fun find(name: String): ItemRec? {
        if (!ready) return null
        return items[name] ?: items.entries.firstOrNull {
            it.key.contains(name) || name.contains(it.key)
        }?.value
    }

    @Synchronized
    fun remove(name: String): Boolean {
        if (!ready) return false
        val hit = find(name) ?: return false
        items.remove(hit.name)
        save()
        return true
    }

    @Synchronized
    private fun save() {
        runCatching {
            val arr = JSONArray()
            items.values.forEach { r ->
                arr.put(JSONObject()
                    .put("name", r.name).put("room", r.room)
                    .put("spot", r.spot).put("pos", r.pos)
                    .put("lastSeen", r.lastSeen).put("source", r.source))
            }
            val tmp = File(dir, "memory.tmp")
            tmp.writeText(JSONObject().put("items", arr).toString())
            if (jsonFile().exists()) jsonFile().delete()
            tmp.renameTo(jsonFile())
        }.onFailure { Log.w("MemoryStore", "保存失败 $it") }
    }
}
