package io.agents.arya.engine;

import io.agents.arya.engine.IEngineCallback;

interface IEngine {
    void registerCallback(IEngineCallback cb);
    String ensureLoaded(String modelPath, int ctxSize, int nThreads);
    int generate(String requestJson);
    void cancel(int requestId);
    String stats();
    void unload();
    boolean savePrefixState(String key);
    boolean loadPrefixState(String key);
    int countTokens(String text);
}
