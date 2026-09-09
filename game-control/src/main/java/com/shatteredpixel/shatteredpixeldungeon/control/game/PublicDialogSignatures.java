package com.shatteredpixel.shatteredpixeldungeon.control.game;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure recognition of complete, currently public dialog trees. Never reads game objects. */
public final class PublicDialogSignatures {
    private PublicDialogSignatures() { }

    /**
     * These flags permit only the original buttons' resource domains to be used by the
     * English projection. They do not translate or grant knowledge about an item body.
     */
    public static Map<String, Boolean> identify(Map<?, ?> publicUi) {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        flags.put("steal_warning", false);
        flags.put("resurrection_warning", false);
        flags.put("reward_confirmation", false);
        flags.put("support_prompt", false);
        if (publicUi == null || !Boolean.TRUE.equals(publicUi.get("modal"))
                || !("GameScene".equals(publicUi.get("scene")) || "game".equals(publicUi.get("scene")))) return flags;
        Dialog dialog = Dialog.read(publicUi.get("controls"));
        if (dialog == null) return flags;

        // WndTradeItem's actual warning uses the armband title and this whole warning.
        flags.put("steal_warning", dialog.matches(
                "神偷袖章", "Master Thieves' Armband",
                "你的袖章充能无法保证稳妥地窃取这件道具。一旦失败，店主就会关店跑路。你确定要尝试继续窃取吗？",
                "Your armband does not have enough charge to guarantee stealing this item. If it fails, the shop will close. Are you sure you want to attempt to steal?",
                "是的，我确定", "Yes, I'm sure", "不，我改主意了", "No, I changed my mind"));
        flags.put("resurrection_warning", dialog.matches(
                "缺少物品", "Missing Items",
                "你还没有选定两件物品。你确定要放弃保留两件物品并重生吗？",
                "You haven't selected two items. Are you sure you want to resurrect without two items?",
                "是的，我确定", "Yes, I'm sure", "不，我改主意了", "No, let me reconsider"));

        // All three original reward previews extend the item-details window and add
        // exactly WndSadGhost.confirm/cancel. The inspected root is already public;
        // its level_known value, item type, quest state and reward source are irrelevant.
        Object inspected = publicUi.get("inspected_item");
        flags.put("reward_confirmation", inspected instanceof Map
                && dialog.root.equals(((Map<?, ?>) inspected).get("control"))
                && dialog.buttonPair("确定", "Confirm", "取消", "Cancel"));
        flags.put("support_prompt", new DisplayedTextEnglish().matchesSupportPrompt(dialog.texts, dialog.buttons));
        return flags;
    }

    private static final class Dialog {
        final String root;
        final List<String> texts = new ArrayList<>();
        final List<String> buttons = new ArrayList<>();
        private Dialog(String root) { this.root = root; }

        static Dialog read(Object controls) {
            if (!(controls instanceof List)) return null;
            Map<String, Map<?, ?>> nodes = new LinkedHashMap<>();
            String root = null;
            for (Object value : (List<?>) controls) {
                if (!(value instanceof Map)) return null;
                Map<?, ?> node = (Map<?, ?>) value;
                if (!(node.get("id") instanceof String) || nodes.put((String) node.get("id"), node) != null) return null;
                if ("window".equals(node.get("role")) && node.get("parent") == null) {
                    if (root != null) return null;
                    root = (String) node.get("id");
                }
            }
            if (root == null) return null;
            Dialog dialog = new Dialog(root);
            for (Map<?, ?> node : nodes.values()) {
                if (Boolean.TRUE.equals(node.get("clipped")) || "partial".equals(node.get("translation_status"))) return null;
                if ("window".equals(node.get("role")) && !root.equals(node.get("id"))) return null;
                boolean buttonAncestor = false;
                Set<Object> visited = new HashSet<>();
                Map<?, ?> ancestor = node;
                while (!root.equals(ancestor.get("id"))) {
                    Object parent = ancestor.get("parent");
                    if (parent == null || !visited.add(parent) || (ancestor = nodes.get(parent)) == null) return null;
                    if ("button".equals(ancestor.get("role"))) buttonAncestor = true;
                }
                String role = String.valueOf(node.get("role"));
                if ("button".equals(role)) {
                    if (buttonAncestor || !Boolean.TRUE.equals(node.get("enabled"))) return null;
                    String text = completeText(node);
                    if (text == null) return null;
                    dialog.buttons.add(text);
                } else if ("text".equals(role) && !buttonAncestor) {
                    String text = completeText(node);
                    if (text == null) return null;
                    dialog.texts.add(text);
                } else if (!("window".equals(role) || "text".equals(role))) {
                    // A note editor, selector, tab, scroll or other interactive shape
                    // cannot borrow the signature of this simple two-button dialog.
                    return null;
                }
            }
            return dialog.texts.size() == 2 && dialog.buttons.size() == 2 ? dialog : null;
        }

        private boolean matches(String titleZh, String titleEn, String bodyZh, String bodyEn,
                                String yesZh, String yesEn, String noZh, String noEn) {
            return one(texts.get(0), titleZh, titleEn) && one(texts.get(1), bodyZh, bodyEn)
                    && buttonPair(yesZh, yesEn, noZh, noEn);
        }

        private boolean buttonPair(String firstZh, String firstEn, String secondZh, String secondEn) {
            return one(buttons.get(0), firstZh, firstEn) && one(buttons.get(1), secondZh, secondEn);
        }

        private static boolean one(String value, String first, String second) {
            return first.equals(value) || second.equals(value);
        }

        private static String completeText(Map<?, ?> node) {
            if (Boolean.TRUE.equals(node.get("clipped")) || "partial".equals(node.get("translation_status"))) return null;
            Object text = node.get("text"), label = node.get("label");
            if (text instanceof String && !((String) text).trim().isEmpty()) {
                // Conflicting labels cannot serve as a second, convenient signature.
                if (label instanceof String && !((String) label).isEmpty() && !text.equals(label)) return null;
                return (String) text;
            }
            // Hover labels cannot stand in for a missing, actually displayed body or button.
            return null;
        }
    }
}
