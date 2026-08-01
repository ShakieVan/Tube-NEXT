package de.shakie.tubenext.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkInteractionPolicyTest {
    @Test
    fun `accepts only supported link message types with absolute YouTube http urls`() {
        assertTrue(
            LinkInteractionPolicy.isSupportedLinkMessage(
                LinkInteractionPolicy.SHOW_LINK_MENU_MESSAGE,
                "https://www.youtube.com/watch?v=abc"
            )
        )
        assertTrue(
            LinkInteractionPolicy.isSupportedLinkMessage(
                LinkInteractionPolicy.OPEN_NEW_TAB_MESSAGE,
                "https://youtu.be/abc"
            )
        )
        assertFalse(LinkInteractionPolicy.isSupportedLinkMessage("MODE_NAV", "https://youtube.com/"))
        assertFalse(
            LinkInteractionPolicy.isSupportedLinkMessage(
                LinkInteractionPolicy.SHOW_LINK_MENU_MESSAGE,
                "https://youtube.com.example.org/watch?v=abc"
            )
        )
        assertFalse(
            LinkInteractionPolicy.isSupportedLinkMessage(
                LinkInteractionPolicy.OPEN_NEW_TAB_MESSAGE,
                "javascript://www.youtube.com/"
            )
        )
        assertFalse(
            LinkInteractionPolicy.isSupportedLinkMessage(
                LinkInteractionPolicy.SHOW_LINK_MENU_MESSAGE,
                "https://user@www.youtube.com/watch?v=abc"
            )
        )
    }

    @Test
    fun `dispatches each menu action exactly once`() {
        LinkMenuAction.entries.forEach { expectedAction ->
            val calls = mutableListOf<LinkMenuAction>()
            val callbacks = LinkMenuActionCallbacks(
                openCurrent = { calls += LinkMenuAction.OPEN_CURRENT },
                openNew = { calls += LinkMenuAction.OPEN_NEW },
                openExternal = { calls += LinkMenuAction.OPEN_EXTERNAL },
                copy = { calls += LinkMenuAction.COPY },
                share = { calls += LinkMenuAction.SHARE }
            )

            assertTrue(
                LinkInteractionPolicy.dispatch(
                    expectedAction,
                    "https://www.youtube.com/feed/subscriptions",
                    callbacks
                )
            )
            assertEquals(listOf(expectedAction), calls)
        }
    }

    @Test
    fun `does not dispatch invalid targets`() {
        var calls = 0
        val callbacks = LinkMenuActionCallbacks(
            openCurrent = { calls += 1 },
            openNew = { calls += 1 },
            openExternal = { calls += 1 },
            copy = { calls += 1 },
            share = { calls += 1 }
        )

        assertFalse(LinkInteractionPolicy.dispatch(LinkMenuAction.OPEN_NEW, "https://example.com", callbacks))
        assertEquals(0, calls)
    }
}
