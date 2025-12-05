package com.lagradost.quicknovel.providers

import android.util.Log
import com.lagradost.quicknovel.*
import com.lagradost.quicknovel.MainActivity.Companion.app
import org.jsoup.Jsoup

class FreewebnovelProvider : LibReadProvider() {
    override val name = "FreeWebNovel"
    override val mainUrl = "https://freewebnovel.com"
    override val hasMainPage = true
    override val iconId = R.drawable.icon_freewebnovel
    override val iconBackgroundId = R.color.wuxiaWorldOnlineColor
    override val removeHtml = true

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val url =
            if (tag.isNullOrBlank()) "$mainUrl/latest-release-novels/$page" else "$mainUrl/genres/$tag/$page"
        Log.d("FreeWebNovel","$url")
        val document = app.get(url).document
        val headers = document.select("div.ul-list1.ul-list1-2.ss-custom > div.li-row")
        val returnValue = headers.mapNotNull { h ->
            val h3 = h.selectFirst("h3.tit > a") ?: return@mapNotNull null
            val latestChap=h.select("div.item")[2].selectFirst("> div > a")?.text()
            newSearchResponse(
                name = h3.attr("title"),
                url = h3.attr("href") ?: return@mapNotNull null
            ) {
                posterUrl = fixUrlNull(h.selectFirst("div.pic > a > img")?.attr("src"))
                latestChapter = latestChap
                totalChapterCount=latestChap?.let { Regex("""\d+""").find(it)?.value }
            }
        }
        return HeadMainPageResponse(url, returnValue)
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val url ="$mainUrl/search?searchkey=$query"
        val document = app.get(url).document // AJAX, MIGHT ADD QUICK SEARCH

        val headers = document.select("div.ul-list1.ul-list1-2.ss-custom > div.li-row")
        val returnValue = headers.mapNotNull { h ->
            val h3 = h.selectFirst("h3.tit > a") ?: return@mapNotNull null
            val latestChap=h.select("div.item")[2].selectFirst("> div > a")?.text()
            newSearchResponse(
                name = h3.attr("title"),
                url = h3.attr("href") ?: return@mapNotNull null
            ) {
                posterUrl = fixUrlNull(h.selectFirst("div.pic > a > img")?.attr("src"))
                latestChapter = latestChap
                totalChapterCount=latestChap?.let { Regex("""\d+""").find(it)?.value }
            }
        }
        return  returnValue
    }

    override suspend fun loadHtml(url: String): String? {
        val response = app.get(url)

        val document = Jsoup.parse(
            response.text
                .replace("New novel chapters are published on Freewebnovel.com.", "")
                .replace("The source of this content is Freewebnᴏvel.com.", "").replace(
                    "☞ We are moving Freewebnovel.com to Libread.com, Please visit libread.com for more chapters! ☜",
                    ""
                )
        )
        document.selectFirst("p")?.remove() // .m-read .txt sub, .m-read .txt p:nth-child(1)
        /*for (e in document.select("p")) {
            if (e.text().contains("The source of this ") || e.selectFirst("a")?.hasAttr("href") == true) {
                e.remove()
            }
        }*/
        document.selectFirst("div.txt>.notice-text")?.remove()
        return document.selectFirst("div.txt")?.html()
    }
}

