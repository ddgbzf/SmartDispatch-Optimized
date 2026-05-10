package com.example.smartdispatch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferences(private val context: Context) {

    companion object {
        private val RECENT_PRODUCTS_KEY = stringSetPreferencesKey("recent_products")
        private const val MAX_RECENT_PRODUCTS = 20  // 最多保存20个最近使用的产品
    }

    // 获取最近使用的产品列表
    val recentProducts: Flow<List<String>> = context.dataStore.data.map { preferences ->
        (preferences[RECENT_PRODUCTS_KEY] ?: emptySet()).toList()
    }

    // 添加产品到最近使用列表
    suspend fun addRecentProduct(productName: String) {
        context.dataStore.edit { preferences ->
            val current = (preferences[RECENT_PRODUCTS_KEY] ?: emptySet()).toMutableList()
            // 如果已存在，先移除
            current.remove(productName)
            // 添加到头部
            current.add(0, productName)
            // 限制数量
            while (current.size > MAX_RECENT_PRODUCTS) {
                current.removeAt(current.size - 1)
            }
            preferences[RECENT_PRODUCTS_KEY] = current.toSet()
        }
    }

    // 清空最近使用列表
    suspend fun clearRecentProducts() {
        context.dataStore.edit { preferences ->
            preferences.remove(RECENT_PRODUCTS_KEY)
        }
    }
}
