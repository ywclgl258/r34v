package com.r34v.r34v.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.ComponentActivity

fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
fun ComponentActivity.showError(error: Throwable) = toast(error.message ?: "操作失败，请重试")
