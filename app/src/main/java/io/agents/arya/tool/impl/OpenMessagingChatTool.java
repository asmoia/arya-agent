// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.

package io.agents.arya.tool.impl;

import android.view.accessibility.AccessibilityNodeInfo;

import io.agents.arya.agent.hermes.core.HermesDirectOpen;
import io.agents.arya.service.ClawAccessibilityService;
import io.agents.arya.tool.BaseTool;
import io.agents.arya.tool.ToolParameter;
import io.agents.arya.tool.ToolResult;
import io.agents.arya.utils.ContactListUiUtils;
import io.agents.arya.utils.ContactMatchUtils;
import io.agents.arya.utils.XLog;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Opens a named chat, group or channel through the messaging app's own search
 * UI. This is intentionally read/navigation-only: it is safe to use before a
 * single LLM summary turn and saves the model from repeatedly rediscovering a
 * contact list.
 */
public class OpenMessagingChatTool extends BaseTool {

    private static final String TAG = "OpenMessagingChat";
    private static final long SETTLE_MS = 650L;

    @Override
    public String getName() { return "open_messaging_chat"; }

    @Override
    public String getDisplayName() { return "Open Messaging Chat"; }

    @Override
    public String getDescriptionEN() {
        return "Open a specific person, group or channel in Telegram, Telegram X, WhatsApp or another supported messaging app. "
                + "Returns a compact current-screen snapshot; it never sends a message.";
    }

    @Override
    public String getDescriptionCN() { return getDescriptionEN(); }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("contact", "string", "Exact or visible name of the person, group or channel", true),
                new ToolParameter("app", "string", "Messaging app, default Telegram", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String contact = requireString(params, "contact").trim();
        String app = optionalString(params, "app", "Telegram").trim();
        if (contact.isEmpty()) return ToolResult.error("Chat name is empty");

        ClawAccessibilityService service = requireAccessibilityService(2_000L);
        if (service == null) return ToolResult.error("Accessibility service is not running");

        try {
            String packageName = OpenAppTool.resolveAppNameStatic(app);
            if (packageName == null) packageName = app;
            ToolResult opened = HermesDirectOpen.INSTANCE.open(app);
            if (!opened.isSuccess() && !service.openApp(packageName)) {
                return ToolResult.error("Could not open " + app + ". Is it installed?");
            }
            if (!waitForActivePackage(service, packageName, 4_000L)) {
                return ToolResult.error(app + " did not become active");
            }

            if (!ContactListUiUtils.prepareForContactLookup(service, packageName, 3, SETTLE_MS)) {
                return ToolResult.error("Could not reach " + app + " chat list");
            }
            LinkedHashSet<String> aliases = ContactMatchUtils.buildNormalizedAliases(contact);
            LinkedHashSet<String> digitAliases = ContactMatchUtils.buildDigitAliases(contact);
            if (!ContactListUiUtils.searchOrScrollAndFindAndClick(
                    service, contact, aliases, digitAliases, 3, SETTLE_MS)) {
                return ToolResult.error("Could not find '" + contact + "' in " + app);
            }
            Thread.sleep(SETTLE_MS);

            String screen = service.getScreenTree();
            String compact = screen == null ? "" : screen.trim().replaceAll("\\s+", " ");
            if (compact.length() > 1_600) compact = compact.substring(0, 1_600) + "…";
            return ToolResult.success(
                    "Opened " + contact + " in " + app + "."
                            + (compact.isEmpty() ? "" : " Current screen: " + compact)
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Opening messaging chat interrupted");
        } catch (Exception e) {
            XLog.w(TAG, "Open chat failed", e);
            return ToolResult.error("Open chat failed: " + e.getMessage());
        }
    }

    private boolean waitForActivePackage(ClawAccessibilityService service, String expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String expectedLower = expected.toLowerCase(Locale.ROOT);
        while (System.currentTimeMillis() < deadline) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            String current = root != null && root.getPackageName() != null
                    ? root.getPackageName().toString().toLowerCase(Locale.ROOT)
                    : "";
            if (current.equals(expectedLower)
                    || (expectedLower.contains("telegram") && current.contains("telegram"))
                    || (expectedLower.contains("telegram") && current.contains("challegram"))) {
                return true;
            }
            Thread.sleep(180L);
        }
        return false;
    }

}
