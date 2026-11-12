package org.libretv.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 片库：用户收藏的影片，保存在本机。每条是一张影片卡片（片名、海报、年份和它的各个来源），打开时直接进详情页。 */
internal class TvFavorites(context: Context) {
    private val prefs = context.getSharedPreferences("supertv-favorites", Context.MODE_PRIVATE)
    private var items: MutableList<JSONObject> = try { JSONArray(prefs.getString("items", "[]")).objects().toMutableList() } catch (_: Exception) { mutableListOf() }
    private fun key(card: JSONObject) = "${card.optString("name").trim()}|${card.optString("year")}"
    fun all(): List<JSONObject> = items.toList()
    fun has(card: JSONObject) = items.any { key(it) == key(card) }
    /** 返回切换后是否在片库里 */
    fun toggle(card: JSONObject): Boolean {
        val existing = items.indexOfFirst { key(it) == key(card) }
        if (existing >= 0) items.removeAt(existing) else items.add(0, JSONObject(card.toString()).put("addedAt", System.currentTimeMillis()))
        prefs.edit().putString("items", JSONArray(items).toString()).apply()
        return existing < 0
    }
}
