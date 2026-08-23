package io.agents.arya.engine;

oneway interface IEngineCallback {
    void onDelta(int requestId, String textDelta);
    void onDone(int requestId, String statsJson);
    void onError(int requestId, int code, String message);
    void onLoadProgress(int pct, String phase);
    void onLoadResult(int requestId, String infoJson);
}
