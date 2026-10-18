package com.tocomic.app

import com.tocomic.app.data.CppEvent
import com.tocomic.app.data.CppExhibit
import com.tocomic.app.data.CppImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CppImportTest {

    private fun event(
        id: String = "7040",
        position: String,
        eventName: String = "CP32-一期",
        enterTime: String = "2026-05-01",
        isEnded: Boolean = true
    ) = CppEvent(id, position, eventName, "百川社", enterTime, isEnded)

    @Test
    fun hallPrefixComesFromLeadingCjkOfPosition() {
        // 报告里的真实摊位号样本
        assertEquals("叁", CppImport.hallIdFromPosition("叁A11叁A12"))
        assertEquals("叁", CppImport.hallIdFromPosition("叁I48"))
        assertEquals("叁", CppImport.hallIdFromPosition("叁L01叁L02"))
        // 纯 ASCII 摊位号推不出展馆
        assertNull(CppImport.hallIdFromPosition("A11"))
        assertNull(CppImport.hallIdFromPosition(""))
    }

    @Test
    fun unannouncedPositionCountsAsNoBoothNumber() {
        // 报告里第二个样本的真实取值
        assertFalse(CppImport.positionIsAnnounced("摊位未公布"))
        assertFalse(CppImport.positionIsAnnounced("未公布"))
        assertFalse(CppImport.positionIsAnnounced("待定"))
        assertFalse(CppImport.positionIsAnnounced(""))
        assertTrue(CppImport.positionIsAnnounced("叁A11叁A12"))
        assertNull(CppImport.hallIdFromPosition("摊位未公布"))
    }

    @Test
    fun picksOneParticipationRecordSoExhibitBelongsToExactlyOneHall() {
        // 对照样本 281927 的真实形态：跨 5 条记录
        val events = listOf(
            event("7040", "叁A11叁A12", "CP32-一期", "2026-05-01", isEnded = true),
            event("7042", "叁A11叁A12", "CP32-二期", "2026-05-04", isEnded = true),
            event("7030", "叁I48", "CP32Pre", "2025-12-27", isEnded = true),
            event("6300", "叁L01叁L02", "CP30 D1", "2024-10-03", isEnded = true)
        )
        val chosen = CppImport.chooseEvent(events)
        assertEquals("CP32-二期", chosen?.eventName)
        val exhibit = CppExhibit("281927", "某展品", events)
        // 无论有几条记录，最终只落一个展馆
        assertEquals("叁", exhibit.hallId)
        assertEquals("叁A11叁A12", exhibit.boothCode)
    }

    @Test
    fun prefersOngoingRecordOverEndedOne() {
        val events = listOf(
            event("1", "叁A11", "已结束的展", "2025-01-01", isEnded = true),
            event("2", "壹B22", "进行中的展", "2027-01-01", isEnded = false)
        )
        val chosen = CppImport.chooseEvent(events)
        assertEquals("进行中的展", chosen?.eventName)
        assertEquals("壹", CppImport.hallIdFromPosition(chosen!!.position))
    }

    // 没有摊位号 -> 默认展馆，且展台号退回展品 ID（否则空展台号会在归一化时被丢弃）
    @Test
    fun exhibitWithoutPositionFallsBackToDefaultHall() {
        val exhibit = CppExhibit("1756967", "图集·欢迎来到海特洛市", emptyList())
        assertEquals(CppImport.DEFAULT_HALL_ID, exhibit.hallId)
        assertEquals("1756967", exhibit.boothCode)

        val booth = CppImport.toCustomBooth(exhibit)
        assertEquals(CppImport.DEFAULT_HALL_ID, booth.hallId)
        assertEquals("1756967", booth.boothCode)
        assertEquals("图集·欢迎来到海特洛市", booth.name)
        assertTrue(booth.note.contains("暂无参展记录"))
    }

    @Test
    fun unannouncedPositionAlsoFallsBackToDefaultHall() {
        val exhibit = CppExhibit(
            "1913360",
            "亚克力·c呆立牌",
            listOf(event("8234", "摊位未公布", "2026广州型月同人ONLY", "2026-11-14", isEnded = false))
        )
        assertEquals(CppImport.DEFAULT_HALL_ID, exhibit.hallId)
        assertEquals("1913360", exhibit.boothCode)
        val booth = CppImport.toCustomBooth(exhibit)
        assertTrue(booth.note.contains("摊位号: 未公布"))
        // 活动名称与入场时间不导入
        assertFalse(booth.note.contains("2026广州型月同人ONLY"))
        assertFalse(booth.note.contains("2026-11-14"))
    }

    @Test
    fun boothNoteKeepsCircleButDropsEventAndTime() {
        val exhibit = CppExhibit("281927", "某展品", listOf(event("7042", "叁A11叁A12", "CP32-二期", "2026-05-04")))
        val booth = CppImport.toCustomBooth(exhibit)
        assertEquals("叁", booth.hallId)
        assertTrue("社团名要保留", booth.note.contains("社团: 百川社"))
        assertFalse("活动名称不导入", booth.note.contains("活动:"))
        assertFalse("入场时间不导入", booth.note.contains("入场:"))
        assertFalse(booth.note.contains("CP32-二期"))
        assertFalse(booth.note.contains("2026-05-04"))
        assertTrue(booth.note.contains("来源: https://www.allcpp.cn/d/281927.do"))
    }

    @Test
    fun parsesShortLinkAndExhibitId() {
        assertTrue(CppImport.isShortLink("https://icp.red/qkSBTvItA"))
        assertFalse(CppImport.isShortLink("https://www.allcpp.cn/d/1756967.do"))
        assertEquals("1756967", CppImport.exhibitIdFromUrl("https://www.allcpp.cn/d/1756967.do"))
        assertNull(CppImport.exhibitIdFromUrl("https://icp.red/qkSBTvItA"))
    }

    @Test
    fun parsesDetailTitleFromEnvelope() {
        val body = """{"result":{"doujinshiId":1756967,"name":"欢迎来到海特洛市"},"message":"","isSuccess":true}"""
        assertEquals("欢迎来到海特洛市", CppImport.parseDetailTitle(body))
        assertNull(CppImport.parseDetailTitle("""{"errorCode":403,"isSuccess":false}"""))
        assertNull(CppImport.parseDetailTitle("not json"))
    }

    @Test
    fun dedupesJoinedEventResultsAcrossIsnew() {
        val isnew0 = """{"result":[{"eventId":7040,"position":"叁A11","eventName":"CP32-一期","isend":1}]}"""
        val isnew2 = """{"result":[{"eventId":7040,"position":"叁A11","eventName":"CP32-一期","isend":1},{"eventId":7042,"position":"叁B22","eventName":"CP32-二期","isend":1}]}"""
        val merged = CppImport.dedupeEvents(
            CppImport.parseJoinedEvents(isnew0) + CppImport.parseJoinedEvents(isnew2)
        )
        assertEquals(2, merged.size)
        assertEquals("叁B22", merged[1].position)
    }
}
