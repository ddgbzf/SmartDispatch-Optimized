package com.example.smartdispatch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferences(private val context: Context) {

    companion object {
        private val RECENT_PRODUCTS_KEY = stringPreferencesKey("recent_products")
        private const val MAX_RECENT_PRODUCTS = 20  // 最多保存20个最近使用的产品
        private const val SEPARATOR = "|||"  // 分隔符
    }

    // 获取最近使用的产品列表
    val recentProducts: Flow<List<String>> = context.dataStore.data.map { preferences ->
        val data = preferences[RECENT_PRODUCTS_KEY] ?: ""
        if (data.isBlank()) emptyList()
        else data.split(SEPARATOR).filter { it.isNotBlank() }
    }

    // 添加产品到最近使用列表
    suspend fun addRecentProduct(productName: String) {
        context.dataStore.edit { preferences ->
            val current = (preferences[RECENT_PRODUCTS_KEY] ?: "")
                .split(SEPARATOR)
                .filter { it.isNotBlank() }
                .toMutableList()
            // 如果已存在，先移除
            current.remove(productName)
            // 添加到头部
            current.add(0, productName)
            // 限制数量
            while (current.size > MAX_RECENT_PRODUCTS) {
                current.removeAt(current.size - 1)
            }
            preferences[RECENT_PRODUCTS_KEY] = current.joinToString(SEPARATOR)
        }
    }

    // 清空最近使用列表
    suspend fun clearRecentProducts() {
        context.dataStore.edit { preferences ->
            preferences.remove(RECENT_PRODUCTS_KEY)
        }
    }
}
