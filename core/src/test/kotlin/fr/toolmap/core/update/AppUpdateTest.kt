package fr.toolmap.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdateTest {
    @Test
    fun `versions compare number by number, not as text`() {
        val v = { s: String -> AppVersion.parse(s)!! }
        assertTrue(v("0.4.131") > v("0.4.126"))
        assertTrue(v("0.4.100") > v("0.4.99"))
        assertTrue(v("0.5.0") > v("0.4.999"))
        assertTrue(v("1.0.0") > v("0.9.9"))
        assertEquals(0, v("0.4").compareTo(v("0.4.0")))
        assertEquals("0.4.126", v(" 0.4.126 ").toString())
    }

    @Test
    fun `a version with a suffix or an empty part is not read`() {
        assertNull(AppVersion.parse("0.4.126-debug"))
        assertNull(AppVersion.parse("0..1"))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("+1.2.3"))
        assertNull(AppVersion.parse("1.2.3.4.5"))
    }

    @Test
    fun `the highest debug APK of the releases is kept`() {
        val apk = GitHubReleases.latestApk(RELEASES)!!
        assertEquals("0.4.131", apk.version.toString())
        assertEquals(
            "https://github.com/JRRocks/ToolMap/releases/download/debug-abc1234/ToolMap-debug-0.4.131.apk",
            apk.url,
        )
        assertEquals(87_205_946L, apk.sizeBytes)
        assertEquals("2026-10-29T10:17:37Z", apk.publishedAt)
    }

    @Test
    fun `drafts, other tags and other files are left out`() {
        val body =
            """
            [
              {"tag_name":"debug-1111111","draft":true,"assets":[{"name":"ToolMap-debug-0.9.0.apk","browser_download_url":"https://x/a.apk","size":1}]},
              {"tag_name":"v0.8.0","draft":false,"assets":[{"name":"ToolMap-debug-0.8.0.apk","browser_download_url":"https://x/b.apk","size":1}]},
              {"tag_name":"debug-2222222","draft":false,"assets":[{"name":"ToolMap-release-0.7.0.apk","browser_download_url":"https://x/c.apk","size":1},{"name":"notes.txt","browser_download_url":"https://x/d","size":1}]}
            ]
            """.trimIndent()
        assertNull(GitHubReleases.latestApk(body))
    }

    @Test
    fun `an answer that is not a list of releases gives nothing`() {
        assertNull(GitHubReleases.latestApk("""{"message":"API rate limit exceeded"}"""))
        assertNull(GitHubReleases.latestApk("<html>"))
        assertNull(GitHubReleases.latestApk("[]"))
    }

    @Test
    fun `only a strictly higher version over https is offered`() {
        val apk = GitHubReleases.latestApk(RELEASES)!!
        assertEquals(apk, UpdateDecision.offer("0.4.126", apk))
        assertNull(UpdateDecision.offer("0.4.131", apk))
        assertNull(UpdateDecision.offer("0.4.140", apk))
        assertNull(UpdateDecision.offer("local", apk))
        assertNull(UpdateDecision.offer("0.4.126", apk.copy(url = "http://example.org/a.apk")))
        assertNull(UpdateDecision.offer("0.4.126", null))
    }

    private companion object {
        val RELEASES =
            """
            [
              {
                "tag_name": "debug-abc1234",
                "name": "ToolMap debug 0.4.131 (abc1234)",
                "draft": false,
                "prerelease": true,
                "published_at": "2026-10-29T10:17:37Z",
                "assets": [
                  {
                    "name": "ToolMap-debug-0.4.131.apk",
                    "size": 87205946,
                    "browser_download_url": "https://github.com/JRRocks/ToolMap/releases/download/debug-abc1234/ToolMap-debug-0.4.131.apk"
                  }
                ]
              },
              {
                "tag_name": "debug-0000000",
                "draft": false,
                "prerelease": true,
                "published_at": "2026-10-28T09:00:00Z",
                "assets": [
                  {"name": "ToolMap-debug-0.4.99.apk", "size": 1, "browser_download_url": "https://x/old.apk"}
                ]
              }
            ]
            """.trimIndent()
    }
}
