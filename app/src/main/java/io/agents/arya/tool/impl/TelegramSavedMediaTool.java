// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.

package io.agents.arya.tool.impl;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import io.agents.arya.agent.hermes.core.HermesDirectOpen;
import io.agents.arya.service.ClawAccessibilityService;
import io.agents.arya.tool.BaseTool;
import io.agents.arya.tool.ToolParameter;
import io.agents.arya.tool.ToolResult;
import io.agents.arya.utils.ContactListUiUtils;
import io.agents.arya.utils.ContactMatchUtils;
import io.agents.arya.utils.UiActionMatchUtils;
import io.agents.arya.utils.XLog;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fast, bounded path for the common request "open Telegram Saved Messages and
 * play a random visible song/voice". It deliberately does not use an LLM:
 * opening a known app, opening its own Saved Messages chat and tapping an
 * accessible play control are deterministic enough to avoid several expensive
 * local-model turns.
 *
 * The tool never taps arbitrary message bubbles. If Telegram exposes no
 * playable control to Accessibility it fails quickly and lets the skill route
 * hand off to the normal agent with the screen already in the right place.
 */
public class TelegramSavedMediaTool extends BaseTool {

    private static final String TAG = "TelegramSavedMedia";
    private static final long SHORT_SETTLE_MS = 550L;
    private static final long APP_WAIT_MS = 4_000L;
    private static final int MAX_MEDIA_SCROLLS = 2;

    private static final List<String> SAVED_QUERIES = Arrays.asList(
            "Saved Messages",
            "پیام‌های ذخیره‌شده",
            "پیام های ذخیره شده"
    );

    @Override
    public String getName() {
        return "play_telegram_saved_media";
    }

    @Override
    public String getDisplayName() {
        return "Play Telegram Saved Media";
    }

    @Override
    public String getDescriptionEN() {
        return "Open Telegram Saved Messages and play one visible audio/voice item without an LLM. "
                + "Fails quickly if no accessible media play control is visible.";
    }

    @Override
    public String getDescriptionCN() {
        return getDescriptionEN();
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.emptyList();
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService(2_000L);
        if (service == null) {
            return ToolResult.error("Accessibility service is not running. Enable it to open Telegram Saved Messages.");
        }

        ToolResult opened = HermesDirectOpen.INSTANCE.open("telegram");
        if (!opened.isSuccess()) {
            return ToolResult.error(opened.getError() != null ? opened.getError() : "Telegram could not be opened");
        }
        if (!waitForTelegram(service, APP_WAIT_MS)) {
            return ToolResult.error("Telegram did not become active within 4 seconds");
        }

        try {
            if (!isSavedMessagesOpen(service.getRootInActiveWindow()) && !openSavedMessages(service)) {
                return ToolResult.error(
                        "Telegram opened, but Saved Messages could not be reached quickly. "
                                + "The general agent can continue from the current Telegram screen."
                );
            }

            for (int attempt = 0; attempt <= MAX_MEDIA_SCROLLS; attempt++) {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                AccessibilityNodeInfo playControl = findBestPlayControl(root);
                if (playControl != null) {
                    boolean clicked = service.clickNode(playControl);
                    if (clicked) {
                        XLog.i(TAG, "Tapped visible Telegram media play control on attempt=" + attempt);
                        return ToolResult.success("Telegram Saved Messages opened and a visible media item is playing.");
                    }
                }

                if (attempt == MAX_MEDIA_SCROLLS) break;
                if (!swipeForOlderMedia(service, root)) break;
                sleep(SHORT_SETTLE_MS);
            }

            return ToolResult.error(
                    "Saved Messages is open, but no accessible play control was visible after 2 short scrolls. "
                            + "No random message bubble was tapped."
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Telegram media shortcut interrupted");
        } catch (Exception e) {
            XLog.w(TAG, "Telegram Saved Messages shortcut failed", e);
            return ToolResult.error("Telegram Saved Messages shortcut failed: " + e.getMessage());
        }
    }

    private boolean waitForTelegram(ClawAccessibilityService service, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            CharSequence pkg = root != null ? root.getPackageName() : null;
            if (pkg != null && pkg.toString().toLowerCase(Locale.ROOT).contains("telegram")) {
                return true;
            }
            sleep(160L);
        }
        return false;
    }

    private boolean openSavedMessages(ClawAccessibilityService service) throws InterruptedException {
        // First honour a visible Saved Messages shortcut. This is the fastest and
        // safest route when Telegram was already showing its navigation drawer.
        if (tapVisibleSavedLabel(service)) {
            sleep(SHORT_SETTLE_MS);
            return true;
        }

        String activePackage = activePackage(service);
        if (activePackage == null) return false;

        // From a currently open chat, go back at most twice to Telegram's chat
        // list. Do not keep pressing Back: that can leave Telegram entirely.
        for (int i = 0; i < 2; i++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (isSavedMessagesOpen(root)) return true;
            if (tapVisibleSavedLabel(service)) {
                sleep(SHORT_SETTLE_MS);
                return true;
            }
            if (ContactListUiUtils.isContactLookupReady(root)) break;
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            sleep(SHORT_SETTLE_MS);
        }

        if (!ContactListUiUtils.prepareForContactLookup(service, activePackage, 1, SHORT_SETTLE_MS)) {
            return tapVisibleSavedLabel(service);
        }

        // Reuse the app-agnostic search implementation used by SendMessageTool.
        // It understands a search icon versus a visible edit field and has bounded
        // recovery; each query has one short fallback scroll only.
        for (String query : SAVED_QUERIES) {
            LinkedHashSet<String> aliases = ContactMatchUtils.buildNormalizedAliases(query);
            LinkedHashSet<String> digitAliases = ContactMatchUtils.buildDigitAliases(query);
            if (ContactListUiUtils.searchOrScrollAndFindAndClick(
                    service, query, aliases, digitAliases, 1, SHORT_SETTLE_MS)) {
                sleep(700L);
                return true;
            }
            // Search failures may leave Telegram's search view open. One Back
            // returns to the chat list before trying the localized alternative.
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            sleep(250L);
        }
        return false;
    }

    private boolean tapVisibleSavedLabel(ClawAccessibilityService service) {
        for (String label : SAVED_QUERIES) {
            List<AccessibilityNodeInfo> nodes = service.findNodesByText(label);
            try {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node != null && node.isVisibleToUser() && service.clickNode(node)) {
                        XLog.i(TAG, "Tapped Saved Messages label: " + label);
                        return true;
                    }
                }
            } finally {
                ClawAccessibilityService.recycleNodes(nodes);
            }
        }
        return false;
    }

    private boolean isSavedMessagesOpen(AccessibilityNodeInfo root) {
        if (root == null) return false;
        String tree = safeTree(root);
        String normalized = tree.toLowerCase(Locale.ROOT);
        return normalized.contains("saved messages")
                || tree.contains("پیام‌های ذخیره")
                || tree.contains("پیام های ذخیره");
    }

    private String activePackage(ClawAccessibilityService service) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        return root != null && root.getPackageName() != null ? root.getPackageName().toString() : null;
    }

    private AccessibilityNodeInfo findBestPlayControl(AccessibilityNodeInfo root) {
        if (root == null) return null;
        Candidate best = new Candidate();
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        collectPlayCandidates(root, screen, best);
        return best.score >= 100 ? best.node : null;
    }

    private void collectPlayCandidates(AccessibilityNodeInfo node, Rect screen, Candidate best) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) return;
        int score = scorePlayCandidate(node, screen);
        if (score > best.score) {
            best.node = node;
            best.score = score;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectPlayCandidates(child, screen, best);
        }
    }

    private int scorePlayCandidate(AccessibilityNodeInfo node, Rect screen) {
        if (!node.isClickable() && !node.isLongClickable()) return Integer.MIN_VALUE;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty() || bounds.width() <= 0 || bounds.height() <= 0) return Integer.MIN_VALUE;

        String text = value(node.getText());
        String desc = value(node.getContentDescription());
        String id = value(node.getViewIdResourceName());
        String className = value(node.getClassName());
        String all = (text + " " + desc + " " + id).toLowerCase(Locale.ROOT);

        boolean explicitPlay = containsAny(all,
                "play", "play audio", "play voice", "پخش", "اجرای", "شروع");
        boolean mediaId = containsAny(id.toLowerCase(Locale.ROOT),
                "play", "audio", "voice", "music", "media");
        if (!explicitPlay && !mediaId) return Integer.MIN_VALUE;

        int score = 0;
        if (explicitPlay) score += 120;
        if (mediaId) score += 80;
        if (className.contains("ImageButton") || className.contains("ImageView")) score += 20;
        if (!screen.isEmpty() && bounds.top > screen.top + screen.height() / 8) score += 10;
        // Avoid a generic toolbar media button unless it has an explicit Play label.
        if (!explicitPlay && !screen.isEmpty() && bounds.top < screen.top + screen.height() / 5) score -= 60;
        return score;
    }

    private boolean swipeForOlderMedia(ClawAccessibilityService service, AccessibilityNodeInfo root) {
        Rect bounds = new Rect();
        if (root != null) root.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) {
            int[] size = getScreenSize();
            bounds.set(0, 0, size[0], size[1]);
        }
        int x = bounds.centerX();
        int fromY = bounds.top + (int) (bounds.height() * 0.70f);
        int toY = bounds.top + (int) (bounds.height() * 0.35f);
        return service.performSwipe(x, fromY, x, toY, 260L);
    }

    private String safeTree(AccessibilityNodeInfo root) {
        StringBuilder out = new StringBuilder();
        collectText(root, out, 0);
        return out.toString();
    }

    private void collectText(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || depth > 12 || out.length() > 4_000) return;
        if (node.getText() != null) out.append(node.getText()).append('\n');
        if (node.getContentDescription() != null) out.append(node.getContentDescription()).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectText(child, out, depth + 1);
        }
    }

    private String value(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) return true;
        }
        return false;
    }

    private void sleep(long durationMs) throws InterruptedException {
        Thread.sleep(durationMs);
    }

    private static final class Candidate {
        AccessibilityNodeInfo node;
        int score = Integer.MIN_VALUE;
    }
}
