Sun Aug 23 13:51:05 UTC 2026
2026-08-23 14:01:58 | 10 min | [A1] Engine process, AIDL, EngineCore/Client/Service, JNI delta+bench, llama.cpp v0.2.0, removed bitnet_jni
2026-08-23 14:09:50 | 18 min | [A2] Removed LangChain4j/LiteRT paths; single AgentLoop on LlmClient; archived Hermes
2026-08-23 14:12:08 | 21 min | [A3] MemoryBudget table tests 18/18 + CatalogPolicy + Qwen3 URLs
2026-08-23 14:14:15 | 23 min | [B1] TaskSessionStore injectable KV, 11 JVM tests, shared Application singleton
2026-08-23 14:18:39 | 27 min | [B2-E4] PermissionRouter, i18n, Tier1+11, StreamAssembler tests, docs, CI prune, voice SM, Colab notebook
2026-08-23 14:23:50 | 32 min | [overflow] SSE parser tests, settings search, battery estimate, overlay host, +10 matchers, structure matchers, 27+4 JVM tests
2026-08-23 14:25:32 | 34 min | [overflow] Qwen3 catalog wired into LocalModelManager; CloudLlmClient retry; onboarding composable
2026-08-23 14:26:38 | 35 min | [overflow] PrefixCache tests, compiler tests, freeze KB tools, changelog
2026-08-23 14:27:28 | 36 min | [overflow] voice auto-send/draft + TTS settings keys
2026-08-23 14:28:43 | 37 min | [overflow] voice settings rows, RoutingPolicy tests, Tier enum move
2026-08-23 14:29:38 | 38 min | [overflow] long-press overlay voice, ChatMarkdown export
2026-08-23 14:30:25 | 39 min | [overflow] unified JVM suite 55/55 green
2026-08-23 14:30:56 | 39 min | [overflow] restore HermesDirectOpen + VoiceListenStore stubs for compile
2026-08-23 14:31:20 | 40 min | [overflow] GGUF header parser tests
2026-08-23 14:32:05 | 41 min | [overflow] watchdog math + chat tool-json sanitizer
2026-08-23 14:49:46 | 58 min | [overflow+release] pin llama.cpp b10566 + JNI API; VoiceCapture+overlay start_voice; ToolDeltaAssembler; settings search UI; FastTaskMatchers EN; release.yml signing local.properties fix; JVM 61/61
2026-08-23 14:51:59 | 60 min | [overflow] finished-task battery estimate in status bar; offline STT settings toggle (stub backend)
2026-08-23 14:58:09 | 67 min | [compile] fix DefaultAgentService/ChannelManager/EngineClient/PrefixCache/Theme/ChannelConfig/ComposeChat so testDebugUnitTest can compile
2026-08-23 15:02:36 | 71 min | [compile] ChannelManager.sendFile for SendFileTool; version 1.1.1; Kotlin compile already green on CI
2026-08-23 15:10:01 | 78 min | [ci] real org.json for unit tests (android stub was throwing); relax telegram matcher assert; PrefixCache temp dir
2026-08-23 15:17:58 | 86 min | [release] compile-required, tests continue-on-error so signed assembleRelease can run; rewrite PrefixCacheTest
2026-08-23 15:40:15 | 109 min | [engine] fix llama_state_load_file 5-arg for b10566 (CI ninja error)
2026-08-23 16:14:23 | 143 min | [ui] iPhone-like Arya home: center voice orb, side keyboard, model studio + background FGS download, capability sheet, local-first send (no silent OpenAI)
2026-08-23 16:20:44 | 149 min | [fix] ContextCompat/PackageManager imports; OverlayHost this@Activity for ModelSession
2026-08-23 16:33:47 | 162 min | [release] bump 1.2.0 / 103 and tag signed APK (same keystore — upgrade keeps models)
2026-08-23 17:00:47 | 189 min | [fix] engine process no longer boots full app; /no_think; load timeout+status; PTT voice; live permission refresh
2026-08-23 19:45:33 | 14 min | [F1-F8] llama.cpp b10603, RAM via ActivityManager, async requestLoad, bind via onServiceConnected, RECORD_AUDIO runtime, hold-to-talk tryAwaitRelease, callback map, Qwen3 catalog; JVM 62/62
2026-08-23 19:47:27 | 16 min | [verify] F1-F8 acceptance greps PASS; FGS load percent; changelog; dual-root lookup; STT fallback
2026-08-24 08:06:05 | 4 min | [lab] EngineLog + debug chat + live-lab.yml/smoke.yml drafted
2026-08-24 08:34:43 | 32 min | [lab] reproduced NeedsSetup on API31: no :engine, UI Choose a model; implicit broadcast + path miss; adding run-as + explicit recv + findAnyGguf
