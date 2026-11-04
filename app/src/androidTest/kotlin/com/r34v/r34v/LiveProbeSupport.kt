package com.r34v.r34v

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/** Live checks need deliberate selection, rather than a network-dependent default regression. */
internal fun requireLiveProbe(className: String) {
    val arguments = InstrumentationRegistry.getArguments()
    val selected = arguments.getString("class").orEmpty().split(',').any { it.substringBefore('#') == className }
    assumeTrue("Use an explicit probe class or live=true for live site checks", selected || arguments.getString("live") == "true")
}
