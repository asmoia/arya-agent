// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.

package io.agents.arya.tool.impl;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import io.agents.arya.agent.hermes.core.HermesDirectOpen;
import io.agents.arya.service.ClawAccessibilityService;
import io.agents.arya.tool.BaseTool;
import io.agents.arya.tool.ToolParameter;
import io.agents.arya.tool.ToolRegistry;
import io.agents.arya.tool.ToolResult;
import io.agents.arya.tool.UiWait;
import io.agents.arya.utils.UiActionMatchUtils;
import io.agents.arya.utils.XLog;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic browser-search primitive. It replaces the old five-or-more
 * model turns (open browser -> inspect -> tap -> type -> enter) with a bounded
 * UI operation. It does not claim to have read or summarized search results;
 * callers that need an answer can use one subsequent screen-reading model turn.
 */
public class SearchBrowserTool extends BaseTool {

    private static final String TAG = "SearchBrowserTool";
    private static final long APP_WAIT_MS = 4_000L;
    private static final long SETTLE_MS = 450L;

    @Override
    public String getName() {
        return "search_browser";
    }

    @Override
    public String getDisplayName() {
        return "Search Browser";
    }

    @Override
    public String getDescriptionEN() {
        return "Open the available browser, type a query into its address/search field and submit it. "
                + "Use for an explicit web or Google search; it does not summarize results.";
    }

    @Override
    public String getDescriptionCN() {
        return getDescriptionEN();
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("query", "string", "The exact web search query", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String query = requireString(params, "query").trim();
        if (query.isEmpty()) return ToolResult.error("Search query is empty");

        ClawAccessibilityService service = requireAccessibilityService(2_000L);
        if (service == null) {
            return ToolResult.error("Accessibility service is not running. Enable it to search in a browser.");
        }

        try {
            ToolResult opened = HermesDirectOpen.INSTANCE.open("chrome");
            if (!opened.isSuccess()) {
                return ToolResult.error(opened.getError() != null ? opened.getError() : "No supported browser could be opened");
            }
            if (!waitForBrowser(service, APP_WAIT_MS)) {
                return ToolResult.error("Browser did not become active within 4 seconds");
            }

            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            AccessibilityNodeInfo field = UiActionMatchUtils.findBestSearchField(root);
            if (field == null) {
                AccessibilityNodeInfo action = UiActionMatchUtils.findBestSearchAction(root);
                if (action != null && service.clickNode(action)) {
                    UiWait.until(1_200L, 80L, () ->
                            UiActionMatchUtils.findBestSearchField(service.getRootInActiveWindow()) != null
                    );
                    root = service.getRootInActiveWindow();
                    field = UiActionMatchUtils.findBestSearchField(root);
                }
            }
            if (field == null) {
                return ToolResult.error("Could not find a browser address or search field");
            }
            boolean focused = service.clickNode(field)
                    || field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    || field.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (!focused) {
                return ToolResult.error("Could not focus the browser address or search field");
            }

            sleep(180L);
            ToolResult typed = ToolRegistry.getInstance().executeTool(
                    "input_text", Collections.<String, Object>singletonMap("text", query)
            );
            if (!typed.isSuccess()) {
                return ToolResult.error("Could not type the search query: " + typed.getError());
            }
            String beforeSubmit = service.getScreenTree();
            ToolResult submitted = ToolRegistry.getInstance().executeTool(
                    "system_key", Collections.<String, Object>singletonMap("key", "enter")
            );
            if (!submitted.isSuccess()) {
                return ToolResult.error("Query was typed, but could not submit search: " + submitted.getError());
            }
            UiWait.until(1_500L, 100L, () -> {
                String current = service.getScreenTree();
                return current != null && !current.equals(beforeSubmit);
            });

            String screen = service.getScreenTree();
            String suffix = screen == null || screen.trim().isEmpty()
                    ? ""
                    : " Current visible result screen is ready.";
            return ToolResult.success("Searched the browser for: " + query + "." + suffix);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Browser search interrupted");
        } catch (Exception e) {
            XLog.w(TAG, "Browser search failed", e);
            return ToolResult.error("Browser search failed: " + e.getMessage());
        }
    }

    private boolean waitForBrowser(ClawAccessibilityService service, long timeoutMs) throws InterruptedException {
        return UiWait.until(timeoutMs, 80L, () -> {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            String pkg = root != null && root.getPackageName() != null
                    ? root.getPackageName().toString().toLowerCase(Locale.ROOT)
                    : "";
            return pkg.contains("chrome") || pkg.contains("browser") || pkg.contains("sbrowser");
        });
    }

    private void sleep(long durationMs) throws InterruptedException {
        Thread.sleep(durationMs);
    }
}
