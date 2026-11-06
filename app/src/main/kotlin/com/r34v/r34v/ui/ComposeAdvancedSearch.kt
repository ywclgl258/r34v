@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.r34v.r34v.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.*
import androidx.compose.foundation.text.KeyboardOptions

fun ComposeDialogs.advancedSearch(app: R34Application, initial: SearchCondition, sites: List<String>, done: (SearchCondition) -> Unit) {
    content = {
        var condition by remember { mutableStateOf(initial.normalized()) }
        var draft by remember { mutableStateOf("") }
        var exclude by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        val context = LocalContext.current
        val fields = remember(sites) { sites.flatMap(::filterFields).toSet() }
        fun addTag() {
            val tag = draft.trim().removePrefix("-")
            if (tag.isBlank()) return
            condition = if (exclude) condition.copy(excludeTags = (condition.excludeTags + tag).distinct())
            else condition.copy(includeTags = (condition.includeTags + tag).distinct())
            draft = ""
        }
        fun number(value: String, change: (Int) -> Unit) = change(value.toIntOrNull()?.coerceAtLeast(0) ?: 0)
        AlertDialog(::dismiss, title = { Text("标签与高级筛选") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (FilterField.TAGS in fields) {
                    TextField(condition.query, { condition = condition.copy(query = it) }, Modifier.fillMaxWidth(), label = { Text("关键词") }, singleLine = true,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), colors = filledFieldColors())
                    Choices(listOf("包含标签", "排除标签"), if (exclude) 1 else 0) { exclude = it == 1 }
                    TextField(draft, { draft = it }, Modifier.fillMaxWidth(), label = { Text("原始标签或 namespace:tag") }, singleLine = true,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), colors = filledFieldColors(),
                        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { addTag() }))
                    val suggestions = remember(draft) { TagNames.suggestions(context, draft) }
                    if (draft.isNotBlank() && suggestions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { (slug, translated) -> Choice(if (translated == slug) slug else "$slug · $translated") { draft = slug; addTag() } }
                    }
                    TextAction(::addTag, enabled = draft.isNotBlank()) { Text(if (exclude) "加入排除条件" else "加入包含条件") }
                    if (condition.includeTags.isNotEmpty()) Copy("包含：${condition.includeTags.joinToString("、")}")
                    if (condition.excludeTags.isNotEmpty()) Copy("排除：${condition.excludeTags.joinToString("、")}")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (condition.includeTags.map { it to false } + condition.excludeTags.map { it to true }).forEach { (tag, excluded) ->
                            Choice("移除 ${if (excluded) "-" else ""}$tag") {
                                condition = if (excluded) condition.copy(excludeTags = condition.excludeTags - tag) else condition.copy(includeTags = condition.includeTags - tag)
                            }
                        }
                    }
                } else TextField(condition.query, { condition = condition.copy(query = it) }, Modifier.fillMaxWidth(), label = { Text("关键词") }, singleLine = true)
                if (FilterField.YEAR in fields || FilterField.MONTH in fields) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (FilterField.YEAR in fields) TextField(condition.filter.year.takeIf { it > 0 }?.toString().orEmpty(), { number(it) { year -> condition = condition.copy(filter = condition.filter.copy(year = year)) } },
                        Modifier.weight(1f), label = { Text("年份") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    if (FilterField.MONTH in fields) TextField(condition.filter.month.takeIf { it > 0 }?.toString().orEmpty(), { number(it) { month -> condition = condition.copy(filter = condition.filter.copy(month = month)) } },
                        Modifier.weight(1f), label = { Text("月份 1–12") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if (FilterField.DURATION in fields) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField((condition.filter.minDurationSec / 60).takeIf { it > 0 }?.toString().orEmpty(), { number(it) { min -> condition = condition.copy(filter = condition.filter.copy(minDurationSec = min * 60)) } },
                        Modifier.weight(1f), label = { Text("最短分钟") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    TextField((condition.filter.maxDurationSec / 60).takeIf { it > 0 }?.toString().orEmpty(), { number(it) { max -> condition = condition.copy(filter = condition.filter.copy(maxDurationSec = max * 60)) } },
                        Modifier.weight(1f), label = { Text("最长分钟") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if (FilterField.BRAND in fields) TextField(condition.filter.brand, { condition = condition.copy(filter = condition.filter.copy(brand = it)) }, Modifier.fillMaxWidth(), label = { Text("品牌（来源原始名称）") }, singleLine = true)
                if (FilterField.CATEGORY in fields) TextField(condition.filter.category, { condition = condition.copy(filter = condition.filter.copy(category = it)) }, Modifier.fillMaxWidth(), label = { Text("分类标识或名称") }, singleLine = true)
                if (FilterField.RATING in fields) Selector(if (condition.filter.minRating > 0) "最低 ${condition.filter.minRating} 星" else "不限评分") {
                    choose("最低评分", listOf("不限评分") + (1..5).map { "$it 星" }, condition.filter.minRating) { selected -> condition = condition.copy(filter = condition.filter.copy(minRating = selected)) }
                }
                if (FilterField.PAGES in fields) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(condition.filter.minPages.takeIf { it > 0 }?.toString().orEmpty(), { number(it) { min -> condition = condition.copy(filter = condition.filter.copy(minPages = min)) } },
                        Modifier.weight(1f), label = { Text("最少页数") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    TextField(condition.filter.maxPages.takeIf { it > 0 }?.toString().orEmpty(), { number(it) { max -> condition = condition.copy(filter = condition.filter.copy(maxPages = max)) } },
                        Modifier.weight(1f), label = { Text("最多页数") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                sites.forEach { site -> Copy("${SiteRegistry.byIdOrDefault(site).name}：${filterScope(site)}") }
                if (condition.isEmpty) Copy("可只用高级条件搜索；清空全部条件则恢复普通搜索。")
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = {
            Row {
                if (condition != SearchCondition()) TextAction({ condition = SearchCondition(); error = "" }) { Text("清空条件") }
                TextAction({
                    val value = condition.normalized()
                    runCatching { value.filter.validate() }.onSuccess { dismiss(); done(value) }.onFailure { error = it.message.orEmpty() }
                }) { Text("应用条件") }
            }
        }, dismissButton = { TextAction(::dismiss) { Text("取消") } })
    }
}
