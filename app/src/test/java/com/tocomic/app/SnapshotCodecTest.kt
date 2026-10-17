package com.tocomic.app

import com.tocomic.app.data.SnapshotCodec
import com.tocomic.app.model.Booth
import com.tocomic.app.model.BoothState
import com.tocomic.app.model.CustomBooth
import com.tocomic.app.model.HallMeta
import com.tocomic.app.model.ProjectSpec
import com.tocomic.app.model.TripSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotCodecTest {

    private fun state(planned: Boolean = false, note: String = "", order: Int = Int.MAX_VALUE) =
        BoothState(note = note, planned = planned, planOrder = order, completed = planned)

    // 导出：选了「保留收藏」就必须把 planned 写出去，没选就必须清空；两种都把完成状态重置
    @Test
    fun exportFilterKeepsFavoritesOnlyWhenRequested() {
        val snapshot = TripSnapshot(
            states = mapOf("A" to state(planned = true, order = 3)),
            customBooths = listOf(CustomBooth("c1", "E3", "01", "自定义", planned = true, planOrder = 1))
        )

        val kept = SnapshotCodec.exportFilter(snapshot, keepFavorites = true)
        assertTrue(kept.states.getValue("A").planned)
        assertTrue(kept.customBooths.first().planned)
        assertFalse("导出必须重置完成状态", kept.states.getValue("A").completed)
        assertEquals(3L, kept.states.getValue("A").planOrder.toLong())

        val dropped = SnapshotCodec.exportFilter(snapshot, keepFavorites = false)
        assertFalse(dropped.states.getValue("A").planned)
        assertFalse(dropped.customBooths.first().planned)
    }

    // 「保留已有数据」合并：本地收藏绝不能被导入文件里的 false 抹掉（本次 bug 的核心回归用例）
    @Test
    fun mergeKeepExistingNeverRemovesLocalFavorite() {
        val previous = state(planned = true, order = 1)
        val incoming = state(planned = false)

        val merged = SnapshotCodec.mergeKeepExisting(previous, incoming)
        assertTrue("导入不含收藏的文件不能取消本地收藏", merged.planned)
        assertEquals(1L, merged.planOrder.toLong())
    }

    // 反向：本地没收藏、导入里有收藏 → 应该补上
    @Test
    fun mergeKeepExistingAcceptsIncomingFavorite() {
        val merged = SnapshotCodec.mergeKeepExisting(
            state(planned = false, order = Int.MAX_VALUE),
            state(planned = true, order = 5)
        )
        assertTrue(merged.planned)
        assertEquals(5L, merged.planOrder.toLong())
    }

    @Test
    fun mergeKeepExistingCoversCustomBooths() {
        val previous = CustomBooth("c1", "E3", "01", "自定义", planned = true, planOrder = 2)
        val incoming = CustomBooth("c1", "E3", "01", "自定义", planned = false)
        assertTrue(SnapshotCodec.mergeKeepExisting(previous, incoming).planned)
    }

    // 老版本（v4）文件没有 halls 键，必须照常解析且 hallMeta 为空
    @Test
    fun decodeLegacyV4WithoutHalls() {
        val v4 = JSONObject().apply {
            put("version", 4)
            put(
                "items",
                JSONArray().put(
                    JSONObject().apply {
                        put("id", "A")
                        put("planned", true)
                    }
                )
            )
            put("customBooths", JSONArray())
            put("booths", JSONArray())
        }.toString()

        val decoded = SnapshotCodec.decode(v4)
        assertTrue(decoded.states.getValue("A").planned)
        assertTrue(decoded.hallMeta.isEmpty())
    }

    // v5 往返：收藏与展馆改名都要原样带回来
    @Test
    fun encodeDecodeRoundTripKeepsFavoritesAndHallMeta() {
        val snapshot = TripSnapshot(
            states = mapOf("A" to state(planned = true, note = "备注", order = 2)),
            hallMeta = mapOf("E3" to HallMeta("潮流生活", "主舞台"))
        )

        val encoded = SnapshotCodec.encode(SnapshotCodec.exportFilter(snapshot, keepFavorites = true))
        // 断言引用常量而不是写死数字，导出格式升级时不用改测试
        assertEquals(SnapshotCodec.VERSION.toLong(), encoded.getInt("version").toLong())

        val decoded = SnapshotCodec.decode(encoded.toString())
        assertTrue(decoded.states.getValue("A").planned)
        assertEquals(2L, decoded.states.getValue("A").planOrder.toLong())
        assertEquals(HallMeta("潮流生活", "主舞台"), decoded.hallMeta["E3"])
    }

    // 展馆名带小写 id 时应归一化成大写键，空值不落库
    @Test
    fun hallMetaKeysAreUppercasedAndBlanksDropped() {
        val v5 = JSONObject().apply {
            put("version", 5)
            put("items", JSONArray())
            put("customBooths", JSONArray())
            put("booths", JSONArray())
            put(
                "halls",
                JSONArray()
                    .put(JSONObject().apply { put("id", " e3 "); put("title", "主舞台"); put("subtitle", "") })
                    .put(JSONObject().apply { put("id", "N1"); put("title", ""); put("subtitle", "") })
            )
        }.toString()

        val decoded = SnapshotCodec.decode(v5)
        assertEquals(HallMeta("主舞台", ""), decoded.hallMeta["E3"])
        assertFalse("名称与备注都空的不应保留", decoded.hallMeta.containsKey("N1"))
    }

    // v6 起导出文件带项目信息，主页导入时用它还原项目名
    @Test
    fun projectSpecRoundTripsThroughEncodeDecode() {
        val snapshot = TripSnapshot(
            project = ProjectSpec(id = "custom-1", title = "CP32 上海", subtitle = "国家会展中心", year = "2026", note = "备注")
        )
        val encoded = SnapshotCodec.encode(SnapshotCodec.exportFilter(snapshot, keepFavorites = true))
        assertEquals(SnapshotCodec.VERSION.toLong(), encoded.getInt("version").toLong())

        val decoded = SnapshotCodec.decode(encoded.toString())
        assertEquals("CP32 上海", decoded.project?.title)
        assertEquals("custom-1", decoded.project?.id)
        assertEquals("2026", decoded.project?.year)
    }

    // 老文件没有 project 键时不能造出一个空项目名
    @Test
    fun missingProjectSpecStaysNull() {
        val v5 = """{"version":5,"items":[],"customBooths":[],"booths":[],"halls":[]}"""
        assertNull(SnapshotCodec.decode(v5).project)
    }

    // 主页导入完整项目：内置展台必须转成自定义展台，否则新项目里一个新展台都看不到
    @Test
    fun asCustomProjectConvertsBuiltInBooths() {
        val snapshot = TripSnapshot(
            states = mapOf("NZ-12-6" to state(planned = true, note = "备注")),
            booths = listOf(Booth("NZ-12-6", "NZ", "12-6", "Wayway")),
            project = ProjectSpec(id = "chinajoy-2026", title = "ChinaJoy 2026")
        )

        val converted = SnapshotCodec.asCustomProject(snapshot)
        assertEquals(1, converted.customBooths.size)
        assertEquals("Wayway", converted.customBooths.first().name)
        assertEquals("NZ", converted.customBooths.first().hallId)
        assertTrue("收藏要跟着转过去", converted.customBooths.first().planned)
        assertTrue(converted.booths.isEmpty())
        assertNull("转成自定义项目后不再带内置项目信息", converted.project)
    }

    // 转换后再归一化、再编解码，展台不能丢
    @Test
    fun asCustomProjectSurvivesRoundTrip() {
        val snapshot = TripSnapshot(
            states = mapOf("NZ-12-6" to state(planned = true)),
            booths = listOf(Booth("NZ-12-6", "NZ", "12-6", "Wayway"))
        )
        val roundTripped = SnapshotCodec.decode(
            SnapshotCodec.encode(SnapshotCodec.asCustomProject(snapshot)).toString()
        )
        assertEquals(1, roundTripped.customBooths.size)
        assertTrue(roundTripped.states.getValue("NZ-12-6").planned)
    }
}
