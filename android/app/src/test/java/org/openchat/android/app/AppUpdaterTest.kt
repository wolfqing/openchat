package org.openchat.android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun versionComparisonHandlesMultiDigitAndPrereleaseSuffixes() {
        assertEquals(0, AppUpdater.compareVersions("v0.7.3", "0.7.3"))
        assertEquals(0, AppUpdater.compareVersions("0.8", "0.8.0"))
        assertTrue(AppUpdater.compareVersions("0.7.4", "0.7.3") > 0)
        assertTrue(AppUpdater.compareVersions("0.10.0", "0.9.9") > 0)
        assertTrue(AppUpdater.compareVersions("0.8.0", "0.8.0-rc1") > 0)
        assertTrue(AppUpdater.compareVersions("0.8.0-rc1", "0.8.0-beta2") > 0)
        assertTrue(AppUpdater.compareVersions("0.7.2", "0.7.3") < 0)
    }

    @Test
    fun parseReleasesPicksNewestNonDraftReleaseWithBothApkAndChecksums() {
        val json = """
            [
              {
                "tag_name": "v0.7.5",
                "name": "Draft build",
                "draft": true,
                "prerelease": true,
                "body": "Should be ignored because draft=true",
                "assets": [
                  { "name": "openchat-0.7.5.apk", "size": 9300000, "browser_download_url": "https://github.com/wolfqing/openchat/releases/download/v0.7.5/openchat-0.7.5.apk" },
                  { "name": "SHA256SUMS", "size": 85, "browser_download_url": "https://github.com/wolfqing/openchat/releases/download/v0.7.5/SHA256SUMS" }
                ]
              },
              {
                "tag_name": "v0.7.4",
                "name": "Android v0.7.4（预览版）",
                "draft": false,
                "prerelease": true,
                "body": "## 更新\n\n- **新增应用内更新**：支持校验 [SECURITY.md](https://example.com) 指纹。\n\n## 校验\n```\nabc\n```",
                "assets": [
                  { "name": "openchat-0.7.4.apk", "size": 9280000, "browser_download_url": "https://github.com/wolfqing/openchat/releases/download/v0.7.4/openchat-0.7.4.apk" },
                  { "name": "SHA256SUMS", "size": 85, "browser_download_url": "https://github.com/wolfqing/openchat/releases/download/v0.7.4/SHA256SUMS" }
                ]
              },
              {
                "tag_name": "v0.7.3",
                "name": "Android v0.7.3",
                "draft": false,
                "prerelease": true,
                "body": "Older",
                "assets": []
              }
            ]
        """.trimIndent()

        val found = AppUpdater.parseReleasesJson(json, currentVersion = "0.7.3")
        assertNotNull(found)
        assertEquals("v0.7.4", found!!.tagName)
        assertEquals("0.7.4", found.versionName)
        assertEquals("openchat-0.7.4.apk", found.apkName)
        assertEquals(9280000L, found.apkSizeBytes)
        assertEquals("- 新增应用内更新：支持校验 SECURITY.md 指纹。", found.summary)

        assertNull("already on 0.7.4 means up to date", AppUpdater.parseReleasesJson(json, currentVersion = "0.7.4"))
    }

    @Test
    fun parseReleasesRejectsMissingChecksumsOrUntrustedHosts() {
        val noSums = """
            [
              {
                "tag_name": "v0.8.0",
                "draft": false,
                "prerelease": false,
                "assets": [
                  { "name": "openchat-0.8.0.apk", "size": 9000000, "browser_download_url": "https://github.com/wolfqing/openchat/releases/download/v0.8.0/openchat-0.8.0.apk" }
                ]
              }
            ]
        """.trimIndent()
        assertNull(AppUpdater.parseReleasesJson(noSums, currentVersion = "0.7.3"))

        val untrustedHost = """
            [
              {
                "tag_name": "v0.8.0",
                "draft": false,
                "prerelease": false,
                "assets": [
                  { "name": "openchat-0.8.0.apk", "size": 9000000, "browser_download_url": "https://evil.example/openchat-0.8.0.apk" },
                  { "name": "SHA256SUMS", "size": 85, "browser_download_url": "https://github.com/wolfqing/openchat/releases/download/v0.8.0/SHA256SUMS" }
                ]
              }
            ]
        """.trimIndent()
        assertNull(AppUpdater.parseReleasesJson(untrustedHost, currentVersion = "0.7.3"))

        assertTrue(AppUpdater.isAllowedDownloadUrl("https://github.com/wolfqing/openchat/releases/download/v0.7.3/openchat-0.7.3.apk"))
        assertTrue(AppUpdater.isAllowedDownloadUrl("https://objects.githubusercontent.com/github-production-release-asset/123"))
        assertFalse(AppUpdater.isAllowedDownloadUrl("http://github.com/wolfqing/openchat/releases/download/v0.7.3/openchat-0.7.3.apk"))
        assertFalse(AppUpdater.isAllowedDownloadUrl("https://notgithub.com/openchat.apk"))
    }

    @Test
    fun parseSha256SumsExtractsExactMatchingApkDigest() {
        val sha = "c234e62b8c43d8abcebbc2715818685b6545021158bb8aa2ea2c0767cf348069"
        val other = "98c485b481def07351b5872da8ff27445b0b7d7f022e401fa499531fa75d1b2f"
        val sums = """
            # Release hashes
            $other  other-artifact.zip
            ${sha.uppercase()}  *openchat-0.7.4.apk
        """.trimIndent()

        assertEquals(sha, AppUpdater.parseSha256Sums(sums, "openchat-0.7.4.apk"))
        assertNull(AppUpdater.parseSha256Sums(sums, "missing.apk"))
        assertNull(AppUpdater.parseSha256Sums("not-a-valid-hex  openchat-0.7.4.apk", "openchat-0.7.4.apk"))
    }
}
