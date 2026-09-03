package me.rerere.fawntavern.ui.settings

import me.rerere.fawntavern.data.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WebSearchConfigControllerTest {
    @Test
    fun loadClampsResultSizeAndReportsRecovery() {
        val service = SearchServiceOptions.BingLocalOptions("bing")
        val source = FakeWebSearchConfigDataSource(listOf(service), initialResultSize = 99, recovered = true)

        val state = WebSearchConfigController(source).load()

        assertEquals(10, state.resultSize)
        assertEquals(true, state.recovered)
        assertSame(service, state.services.single())
    }

    @Test
    fun removeKeepsAtLeastOneServiceAndPersistsLaterChanges() {
        val first = SearchServiceOptions.BingLocalOptions("first")
        val second = SearchServiceOptions.TavilyOptions(id = "second")
        val source = FakeWebSearchConfigDataSource(listOf(first), initialResultSize = 5, recovered = false)
        val controller = WebSearchConfigController(source)

        val unchanged = controller.remove(controller.load(), first.id)
        val expanded = controller.add(unchanged, second)
        val reduced = controller.remove(expanded, first.id)
        val resized = controller.setResultSize(reduced, 1)

        assertEquals(listOf(second), reduced.services)
        assertEquals(listOf(second), source.savedServices)
        assertEquals(3, resized.resultSize)
        assertEquals(3, source.savedResultSize)
    }

    private class FakeWebSearchConfigDataSource(
        private val initialServices: List<SearchServiceOptions>,
        private val initialResultSize: Int,
        private val recovered: Boolean,
    ) : WebSearchConfigDataSource {
        var savedServices: List<SearchServiceOptions>? = null
        var savedResultSize: Int? = null

        override fun services(): List<SearchServiceOptions> = initialServices
        override fun saveServices(services: List<SearchServiceOptions>) { savedServices = services }
        override fun resultSize(): Int = initialResultSize
        override fun saveResultSize(size: Int) { savedResultSize = size }
        override fun consumeRecoveryNotice(): Boolean = recovered
    }
}
