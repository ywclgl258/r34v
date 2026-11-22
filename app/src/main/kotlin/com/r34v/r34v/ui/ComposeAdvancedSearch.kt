@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.r34v.r34v.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.r34v.r34v.R
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.*

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
        fun updateFilter(filter: AdvancedFilter) { condition = condition.copy(filter = filter) }
        AlertDialog(::dismiss, title = { Title("标签与高级筛选", large = true) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SearchFilterField("关键词", condition.query, { condition = condition.copy(query = it) }, Modifier.fillMaxWidth())
                if (FilterField.TAGS in fields) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Title("标签")
                        Choices(listOf("包含标签", "排除标签"), if (exclude) 1 else 0) { exclude = it == 1 }
                        SearchFilterField("原始标签或 namespace:tag", draft, { draft = it }, Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { addTag() }))
                        val suggestions = remember(draft) { TagNames.suggestions(context, draft) }
                        if (draft.isNotBlank() && suggestions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            suggestions.forEach { (slug, translated) ->
                                Choice(if (translated == slug) slug else "$slug · $translated") {
                                    draft = slug
                                    addTag()
                                }
                            }
                        }
                        TextAction(::addTag, enabled = draft.isNotBlank()) { Text(if (exclude) "加入排除条件" else "加入包含条件") }
                        if (condition.includeTags.isNotEmpty() || condition.excludeTags.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                (condition.includeTags.map { it to false } + condition.excludeTags.map { it to true }).forEach { (tag, excluded) ->
                                    Choice("移除 ${if (excluded) "-" else ""}$tag") {
                                        condition = if (excluded) condition.copy(excludeTags = condition.excludeTags - tag)
                                        else condition.copy(includeTags = condition.includeTags - tag)
                                    }
                                }
                            }
                        }
                    }
                }
                val filter = condition.filter
                if (FilterField.YEAR in fields || FilterField.MONTH in fields || FilterField.DURATION in fields) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Title("时间与时长")
                        if (FilterField.YEAR in fields || FilterField.MONTH in fields) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (FilterField.YEAR in fields) SearchNumberField("年份", filter.year, Modifier.weight(1f)) { updateFilter(filter.copy(year = it)) }
                            if (FilterField.MONTH in fields) SearchNumberField("月份 1–12", filter.month, Modifier.weight(1f)) { updateFilter(filter.copy(month = it)) }
                        }
                        if (FilterField.DURATION in fields) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SearchNumberField("最短分钟", filter.minDurationSec / 60, Modifier.weight(1f)) { updateFilter(filter.copy(minDurationSec = it * 60)) }
                            SearchNumberField("最长分钟", filter.maxDurationSec / 60, Modifier.weight(1f)) { updateFilter(filter.copy(maxDurationSec = it * 60)) }
                        }
                    }
                }
                if (fields.any { it in contentFilterFields }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Title("内容条件")
                        if (FilterField.BRAND in fields) SearchFilterField("品牌（来源原始名称）", filter.brand,
                            { updateFilter(filter.copy(brand = it)) }, Modifier.fillMaxWidth())
                        if (FilterField.CATEGORY in fields) SearchFilterField("分类标识或名称", filter.category,
                            { updateFilter(filter.copy(category = it)) }, Modifier.fillMaxWidth())
                        if (FilterField.PAGES in fields) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SearchNumberField("最少页数", filter.minPages, Modifier.weight(1f)) { updateFilter(filter.copy(minPages = it)) }
                            SearchNumberField("最多页数", filter.maxPages, Modifier.weight(1f)) { updateFilter(filter.copy(maxPages = it)) }
                        }
                        if (FilterField.RATING in fields) RatingFilter(filter.minRating) { updateFilter(filter.copy(minRating = it)) }
                    }
                }
                if (sites.size == 1) Copy(filterScope(sites.first()))
                if (sites.size > 1) Expandable("各来源支持的筛选") {
                    sites.forEach { site ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(SiteRegistry.byIdOrDefault(site).name, style = MaterialTheme.typography.titleMedium)
                            Copy(filterScope(site))
                        }
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }, shape = RoundedCornerShape(6.dp), containerColor = RentalInk, titleContentColor = RentalPaper,
            textContentColor = RentalMuted, tonalElevation = 0.dp, confirmButton = {
            Row {
                if (condition != SearchCondition()) TextAction({ condition = SearchCondition(); error = "" }) { Text("清空条件") }
                Action("应用条件", primary = true) {
                    val value = condition.normalized()
                    runCatching { value.filter.validate() }.onSuccess { dismiss(); done(value) }.onFailure { error = it.message.orEmpty() }
                }
            }
        }, dismissButton = { TextAction(::dismiss) { Text("取消") } })
    }
}

private val contentFilterFields = setOf(FilterField.BRAND, FilterField.CATEGORY, FilterField.PAGES, FilterField.RATING)

@Composable
internal fun rentalSearchFieldColors(paper: Boolean = false): TextFieldColors {
    val stock = if (paper) RentalPaper else RentalSurface
    val ink = if (paper) RentalInk else RentalPaper
    val label = if (paper) RentalInk.copy(alpha = .76f) else RentalMuted
    return TextFieldDefaults.colors(
        focusedContainerColor = stock, unfocusedContainerColor = stock, errorContainerColor = stock,
        focusedTextColor = ink, unfocusedTextColor = ink, errorTextColor = ink,
        focusedLabelColor = ink, unfocusedLabelColor = label, errorLabelColor = ink,
        focusedLeadingIconColor = ink, unfocusedLeadingIconColor = ink, errorLeadingIconColor = ink,
        focusedTrailingIconColor = ink, unfocusedTrailingIconColor = ink, errorTrailingIconColor = ink,
        focusedIndicatorColor = RentalRed, unfocusedIndicatorColor = RentalEdge, errorIndicatorColor = RentalRed,
        errorSupportingTextColor = RentalRed, cursorColor = ink, errorCursorColor = ink,
        selectionColors = TextSelectionColors(ink, ink.copy(alpha = .23f)),
    )
}

@Composable
private fun SearchFilterField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default) {
    TextField(value, onValueChange, modifier, label = { Text(label) }, singleLine = true,
        shape = RoundedCornerShape(4.dp), colors = rentalSearchFieldColors(), keyboardOptions = keyboardOptions, keyboardActions = keyboardActions)
}

@Composable
private fun SearchNumberField(label: String, value: Int, modifier: Modifier = Modifier, onValueChange: (Int) -> Unit) {
    SearchFilterField(label, value.takeIf { it > 0 }?.toString().orEmpty(), { onValueChange(it.toIntOrNull()?.coerceAtLeast(0) ?: 0) }, modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
private fun RatingFilter(rating: Int, select: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Selector(if (rating > 0) "最低 $rating 星" else "不限评分") { open = true }
        DropdownMenu(open, { open = false }) {
            (0..5).forEach { score ->
                DropdownMenuItem(
                    text = { Text(if (score == 0) "不限评分" else "$score 星") },
                    onClick = { open = false; select(score) },
                    trailingIcon = if (rating == score) ({ Glyph(R.drawable.ic_check, null) }) else null,
                )
            }
        }
    }
}
