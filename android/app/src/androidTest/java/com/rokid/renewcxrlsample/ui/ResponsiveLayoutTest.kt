package com.rokid.renewcxrlsample.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.renewcxrlsample.script.ScriptPart
import com.rokid.renewcxrlsample.ui.design.DevSampleScaffold
import com.rokid.renewcxrlsample.ui.design.FlowStepIndicator
import com.rokid.renewcxrlsample.ui.feature.PartCard
import com.rokid.renewcxrlsample.ui.theme.RenewCXRLSampleTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponsiveLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private fun narrowScreen(content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                RenewCXRLSampleTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(280.dp).fillMaxHeight()) { content() }
                    }
                }
            }
        }
    }

    @Test
    fun longScriptScrollsToItsEndWithoutCoveringPrimaryAction() {
        val body = "첫 문장입니다.\n" + "긴 대본의 본문과 줄바꿈을 그대로 보여줍니다.\n".repeat(40) + "마지막 문장입니다."
        narrowScreen {
            DevSampleScaffold(
                title = "대본 준비",
                subtitle = "대본과 키워드를 확인하세요.",
                responsive = true,
                bottomBar = {
                    Button(onClick = {}, modifier = Modifier.fillMaxWidth().testTag("primary-action")) {
                        Text("확인하고 발표 진행")
                    }
                },
            ) {
                PartCard(ScriptPart(
                    index = 1,
                    title = "좁은 화면에서도 긴 제목을 모두 보여주는 발표 대본",
                    keywords = listOf("키워드", "아주길게이어진키워드도끝까지보여줍니다"),
                    hint = "첫 문장",
                    body = body,
                ))
                Text("대본 끝", modifier = Modifier.testTag("script-end"))
            }
        }
        compose.onNodeWithTag("primary-action").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(body, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("본문에 줄 수 제한·생략이 없어야 함", layouts.single().hasVisualOverflow)

        compose.onNodeWithTag("script-end").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("primary-action").assertIsDisplayed()
        val last = compose.onNodeWithTag("script-end").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag("primary-action").fetchSemanticsNode().boundsInRoot
        assertTrue("본문 끝과 하단 버튼이 겹치지 않아야 함", last.bottom <= action.top)

        val keywordLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("아주길게이어진키워드도끝까지보여줍니다", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(keywordLayouts) }
        assertFalse("긴 키워드도 생략하지 않아야 함", keywordLayouts.single().hasVisualOverflow)
    }

    @Test
    fun stepLabelsWrapOnNarrowScreenWithLargeFont() {
        val steps = listOf("1. 대본 확인", "2. 안경 연결", "3. 발표 시작")
        narrowScreen { FlowStepIndicator(steps = steps, currentIndex = 1) }
        val bounds = steps.map { label ->
            compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        assertTrue("단계 안내는 너비가 부족하면 다음 줄로 이동해야 함", bounds[1].top > bounds[0].top)
        assertTrue(bounds[2].top > bounds[1].top)
    }

    @Test
    fun largeFontHeaderGrowsWithoutEllipsis() {
        val title = "발표 진행을 준비하는 화면"
        narrowScreen {
            DevSampleScaffold(title = title, showBack = true, responsive = true) {
                Text("발표 상태")
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("제목 높이는 큰 글꼴에 맞게 늘어나야 함", layouts.single().hasVisualOverflow)
    }
}
