package com.looplooptech.audioscript

import com.looplooptech.audioscript.asr.LatinCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用例与桌面端 `latin_case.rs` 的测试一一对应，保证两端输出一致。 */
class LatinCaseTest {

    @Test
    fun restoresAllCapsEnglish() {
        assertEquals(
            "So the key insight here is that we need to handle it.",
            LatinCase.restoreSentenceCase("SO THE KEY INSIGHT HERE IS THAT WE NEED TO HANDLE IT."),
        )
    }

    @Test
    fun capitalizesEachSentence() {
        assertEquals(
            "The weather was nice. We decided to go for a walk.",
            LatinCase.restoreSentenceCase("THE WEATHER WAS NICE. WE DECIDED TO GO FOR A WALK."),
        )
    }

    @Test
    fun handlesQuestionAndExclamation() {
        assertEquals(
            "Hello there! How are you? I am fine.",
            LatinCase.restoreSentenceCase("HELLO THERE! HOW ARE YOU? I AM FINE."),
        )
    }

    @Test
    fun leavesMixedCaseAlone() {
        val samples = listOf(
            "So the key insight here is that we need to handle it.",
            "The USA is big and the AI boom is real.",
            "I met McDonald in Paris.",
        )
        samples.forEach { assertEquals(it, LatinCase.restoreSentenceCase(it)) }
    }

    @Test
    fun leavesChineseAlone() {
        val samples = listOf("这个方案最大的问题在于延迟。", "AI 很重要。", "他说 GDP 增长了。")
        samples.forEach { assertEquals(it, LatinCase.restoreSentenceCase(it)) }
    }

    @Test
    fun leavesShortSnippetsAlone() {
        assertEquals("OK", LatinCase.restoreSentenceCase("OK"))
        assertEquals("AI", LatinCase.restoreSentenceCase("AI"))
    }

    @Test
    fun keepsDigitsAndPunctuation() {
        assertEquals(
            "In 2026 we had 3 goals: win, grow, and ship.",
            LatinCase.restoreSentenceCase("IN 2026 WE HAD 3 GOALS: WIN, GROW, AND SHIP."),
        )
    }

    @Test
    fun abbreviationDotsDoNotStartNewSentence() {
        val out = LatinCase.restoreSentenceCase("MR. SMITH WENT HOME AND SAW HIS FRIEND.")
        assertTrue(out, out.startsWith("Mr. Smith"))
    }
}
