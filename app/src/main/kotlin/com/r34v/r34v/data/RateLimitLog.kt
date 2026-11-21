package com.r34v.r34v.data

/**
 * 近期限流事件的可观测记录。
 *
 * 被限流时用户只看到“请求失败”，没有说服力也没法自诊断：这里把 429 退避、重试结果、
 * CDN 令牌重解析记成带时间戳的短事件，网络诊断直接读它回答“是不是被限流了、
 * 站点在拒什么”。节流等待（HostGate）是设计内的常态节奏，不记，避免刷屏。
 */
internal object RateLimitLog {
    private const val KEEP = 12
    private val events = ArrayDeque<String>(KEEP)
    private val lock = Any()
    private val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)

    fun record(host: String, message: String) = synchronized(lock) {
        if (events.size >= KEEP) events.removeFirst()
        events.addLast("${time.format(java.util.Date())} · $host · $message")
    }

    /** 诊断面板用的多行摘要；没有事件时返回空串，调用方自行决定是否展示。 */
    fun report(): String = synchronized(lock) {
        if (events.isEmpty()) return ""
        "近期限流事件（最新在下）：\n" + events.joinToString("\n")
    }

    internal fun clear() = synchronized(lock) { events.clear() }
}
