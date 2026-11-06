package com.r34v.r34v.data

enum class FilterField(val title: String) { TAGS("标签"), YEAR("年份"), MONTH("月份"), DURATION("时长"), BRAND("品牌"), CATEGORY("分类"), RATING("最低评分"), PAGES("页数") }

fun filterFields(siteId: String): Set<FilterField> = when (siteId) {
    "hanime1" -> setOf(FilterField.TAGS, FilterField.YEAR, FilterField.MONTH, FilterField.DURATION, FilterField.BRAND, FilterField.CATEGORY)
    "ehentai", "exhentai" -> setOf(FilterField.TAGS, FilterField.CATEGORY, FilterField.RATING, FilterField.PAGES)
    "nhentai" -> setOf(FilterField.TAGS, FilterField.CATEGORY, FilterField.PAGES)
    "iwara" -> setOf(FilterField.TAGS, FilterField.DURATION)
    "r34v" -> setOf(FilterField.DURATION, FilterField.RATING)
    else -> emptySet()
}

fun filterScope(siteId: String): String = when (siteId) {
    "hanime1" -> "年月、品牌、分类、包含标签和最短时长由来源全站筛选；最长时长等仅筛选已加载结果。排除标签暂不由本站支持。"
    "ehentai", "exhentai" -> "标签、分类、评分和页数由来源全站筛选；条件会保留到后续分页。"
    "nhentai" -> "包含／排除标签、分类和页数由来源全站筛选；条件会保留到后续分页。"
    "iwara" -> "包含标签由来源查询；时长仅筛选已加载结果。排除标签暂不由本站支持。"
    "r34v" -> "时长与评分仅筛选本次已加载结果，不代表全站过滤。"
    else -> "当前来源没有已验证的高级筛选，关键词搜索保持可用。"
}
