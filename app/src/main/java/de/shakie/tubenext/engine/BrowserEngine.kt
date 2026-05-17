package de.shakie.tubenext.engine

interface BrowserEngine {
    val type: EngineType

    fun createTab(
        tabId: String,
        initialUrl: String,
        title: String,
        callbacks: EngineCallbacks
    ): EngineTab

    fun shutdown()
}
