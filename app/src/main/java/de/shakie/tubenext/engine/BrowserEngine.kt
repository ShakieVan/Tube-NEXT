package de.shakie.tubenext.engine

interface BrowserEngine {
    val type: EngineType

    fun createTab(
        tabId: String,
        initialUrl: String,
        title: String,
        loadInitialUrl: Boolean = true,
        callbacks: EngineCallbacks
    ): EngineTab

    fun shutdown()
}
