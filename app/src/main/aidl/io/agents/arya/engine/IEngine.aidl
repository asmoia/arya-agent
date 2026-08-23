package io.agents.arya.engine;

import io.agents.arya.engine.IEngineCallback;

interface IEngine {
    void registerCallback(IEngineCallback cb);
    /** Fast-path only: returns stats if this model is already loaded. Never mmaps. */
    String ensureLoaded(String modelPath, int ctxSize, int nThreads);
    /**
     * Async load + optional first-run bench. Completes via
     * IEngineCallback.onLoadResult / onError / onLoadProgress.
     * Must not run on the binder thread.
     */
    oneway void requestLoad(String modelPath, int ctxSize, int nThreads, int requestId);
    int generate(String requestJson);
    void cancel(int requestId);
    String stats();
    void unload();
    boolean savePrefixState(String key);
    boolean loadPrefixState(String key);
    int countTokens(String text);
}
