package com.oscar.endulink.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdateManagerTest {

    private val sampleReleases = Json.parseToJsonElement(
        """
        [
          { "tag_name": "v0.3.2-test", "prerelease": true, "draft": false },
          { "tag_name": "v0.3.1", "prerelease": false, "draft": false },
          { "tag_name": "v0.3.1-test2", "prerelease": true, "draft": false },
          { "tag_name": "v0.3.1-test", "prerelease": true, "draft": false },
          { "tag_name": "v0.3.0", "prerelease": false, "draft": false },
          { "tag_name": "v0.4.0", "prerelease": false, "draft": true }
        ]
        """.trimIndent()
    ).jsonArray

    @Test
    fun testStableChannelIgnoresPrereleasesAndDrafts() {
        val selected = AppUpdateManager.selectReleaseForChannel(sampleReleases, isTestChannel = false)
        assertNotNull(selected)
        assertEquals("v0.3.1", selected["tag_name"]?.jsonPrimitive?.content)
        assertTrue(AppUpdateManager.isVersionNewer("0.3.1", "0.3.0"))
        assertFalse(AppUpdateManager.isVersionNewer("0.3.1", "0.3.1"))
    }

    @Test
    fun testPrereleaseChannelIgnoresStableReleases() {
        val selected = AppUpdateManager.selectReleaseForChannel(sampleReleases, isTestChannel = true)
        assertNotNull(selected)
        assertEquals("v0.3.2-test", selected["tag_name"]?.jsonPrimitive?.content)
        assertTrue(AppUpdateManager.isVersionNewer("0.3.2-test", "0.3.0-test"))
        assertTrue(AppUpdateManager.isVersionNewer("0.3.1-test2", "0.3.1-test"))
        assertFalse(AppUpdateManager.isVersionNewer("0.3.0-test", "0.3.0-test"))
    }

    @Test
    fun testEmptyChannelReturnsNull() {
        val onlyStable = Json.parseToJsonElement(
            """[{ "tag_name": "v0.3.0", "prerelease": false, "draft": false }]"""
        ).jsonArray
        assertNull(AppUpdateManager.selectReleaseForChannel(onlyStable, isTestChannel = true))
    }
}
