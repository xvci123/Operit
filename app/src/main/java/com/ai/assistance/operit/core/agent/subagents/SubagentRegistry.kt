package com.ai.assistance.operit.core.agent.subagents

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 子智能体登记表（P3④）。
 *
 * 解决的问题：子会话默认会出现在会话列表里，用户会看到一堆"[子代理]"对话，
 * 而且父会话里没有任何痕迹说明"我派过谁出去"。
 *
 * 为什么不用 Room、也不用 `ChatHistory.parentChatId`：
 *  - Room 这边 `exportSchema=false` + 20 段手写迁移，加字段只在运行时炸（本项目硬约束）。
 *  - `parentChatId` 已经被"分支对话"占用（`ChatDao.getBranchesByParentId`），
 *    拿来存子代理会让子会话变成用户看得见的"分支"，语义打架。
 *  所以用一份旁路 JSON，只管"这个 chatId 是子代理、它爹是谁"。
 *
 * 隐藏只影响**会话列表的展示**，不影响工具层：`core.chatHistories` 仍然是全集，
 * 否则 `send_message_to_ai` 会认为子会话不存在（`StandardChatManagerTool.kt:1863`）。
 *
 * 落盘位置：`filesDir/hermes/subagents.json`。
 */
object SubagentRegistry {

    private const val TAG = "SubagentRegistry"

    data class Entry(
            val childChatId: String,
            val parentChatId: String?,
            val label: String = "",
            val forked: Boolean = false,
            val background: Boolean = false,
            val createdAt: Long = System.currentTimeMillis()
    ) {
        fun toJson(): JSONObject =
                JSONObject().apply {
                    put("childChatId", childChatId)
                    put("parentChatId", parentChatId ?: JSONObject.NULL)
                    put("label", label)
                    put("forked", forked)
                    put("background", background)
                    put("createdAt", createdAt)
                }

        companion object {
            fun fromJson(obj: JSONObject): Entry? =
                    try {
                        Entry(
                                childChatId = obj.getString("childChatId"),
                                parentChatId =
                                        if (obj.isNull("parentChatId")) null
                                        else obj.optString("parentChatId").takeIf { it.isNotBlank() },
                                label = obj.optString("label"),
                                forked = obj.optBoolean("forked", false),
                                background = obj.optBoolean("background", false),
                                createdAt = obj.optLong("createdAt")
                        )
                    } catch (e: Exception) {
                        null
                    }
        }
    }

    private val lock = Any()

    @Volatile private var cache: Map<String, Entry> = emptyMap()

    @Volatile private var loadedMtime: Long = -1L

    private fun registryFile(context: Context): File =
            File(File(context.filesDir, "hermes"), "subagents.json")

    /** 文件变了就重读。会话列表每次变化都会走到这里，所以必须便宜：一个 mtime 比较 + 几 KB 解析。 */
    private fun ensureLoaded(context: Context) {
        val file = registryFile(context)
        val mtime = if (file.exists()) file.lastModified() else 0L
        if (mtime == loadedMtime) return
        synchronized(lock) {
            if (mtime == loadedMtime) return
            val map = LinkedHashMap<String, Entry>()
            if (file.exists()) {
                try {
                    val arr = JSONArray(file.readText())
                    for (i in 0 until arr.length()) {
                        Entry.fromJson(arr.getJSONObject(i))?.let { map[it.childChatId] = it }
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "读取子代理登记表失败，按空表处理", e)
                }
            }
            cache = map
            loadedMtime = mtime
        }
    }

    private fun persist(context: Context, map: Map<String, Entry>) {
        try {
            val dir = File(context.filesDir, "hermes").apply { mkdirs() }
            val file = File(dir, "subagents.json")
            val arr = JSONArray()
            map.values.forEach { arr.put(it.toJson()) }
            file.writeText(arr.toString())
            loadedMtime = file.lastModified()
        } catch (e: Exception) {
            AppLogger.w(TAG, "写子代理登记表失败", e)
        }
    }

    fun register(context: Context, entry: Entry) {
        ensureLoaded(context)
        synchronized(lock) {
            val map = LinkedHashMap(cache)
            map[entry.childChatId] = entry
            cache = map
            persist(context, map)
        }
        AppLogger.i(TAG, "子代理已登记：${entry.childChatId} -> ${entry.parentChatId ?: "（无父会话）"}")
    }

    fun isSubagent(context: Context, chatId: String): Boolean {
        ensureLoaded(context)
        return cache.containsKey(chatId)
    }

    /** 会被会话列表隐藏的 chatId 集合。 */
    fun hiddenIds(context: Context): Set<String> {
        ensureLoaded(context)
        return cache.keys
    }

    fun entriesOf(context: Context, parentChatId: String): List<Entry> {
        ensureLoaded(context)
        return cache.values.filter { it.parentChatId == parentChatId }.sortedBy { it.createdAt }
    }

    fun countOf(context: Context, parentChatId: String): Int = entriesOf(context, parentChatId).size

    /** 会话被用户删掉后，把登记表里的孤儿记录清掉。 */
    fun prune(context: Context, existingChatIds: Set<String>) {
        ensureLoaded(context)
        synchronized(lock) {
            val kept = cache.values.filter { it.childChatId in existingChatIds }
            if (kept.size == cache.size) return
            val map = LinkedHashMap<String, Entry>()
            kept.forEach { map[it.childChatId] = it }
            cache = map
            persist(context, map)
        }
    }
}
