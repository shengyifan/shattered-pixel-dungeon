package com.shatteredpixel.shatteredpixeldungeon.ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Names the finite native item-glow palette without serializing pulse phases or shader values. */
public final class GameplayGlow {
    private GameplayGlow() {}

    public static Map<String,Object> selected(int color,String hint) {
        String variant;
        switch(color) {
            case 0x000000: variant="black";break;
            case 0x0000FF: variant="blue";break;
            case 0x008800: variant="dark_green";break;
            case 0x00FF00: variant="green";break;
            case 0x00FFFF: variant="cyan";break;
            case 0x195D80: variant="storm_blue";break;
            case 0x1B5F79: variant="deep_teal";break;
            case 0x222222: variant="charcoal";break;
            case 0x2EE62E: variant="leaf_green";break;
            case 0x404040: variant="dark_gray";break;
            case 0x440066: variant="dark_purple";break;
            case 0x448822: variant="olive_green";break;
            case 0x660000: variant="dark_red";break;
            case 0x660022: variant="burgundy";break;
            case 0x663300: variant="brown";break;
            case 0x66B3FF: variant="light_blue";break;
            case 0x67583D: variant="earth_brown";break;
            case 0x8000FF: variant="violet";break;
            case 0x808080: variant="gray";break;
            case 0x8844CC: variant="purple";break;
            case 0x888888: variant="ash_gray";break;
            case 0x88EEFF: variant="pale_cyan";break;
            case 0x8EE3FF: variant="ice_blue";break;
            case 0x919999: variant="blue_gray";break;
            case 0x999999: variant="light_gray";break;
            case 0xA15CE5: variant="lavender";break;
            case 0xC152AA: variant="mauve";break;
            case 0xCC0022: variant="crimson";break;
            case 0xCCBB00: variant="gold";break;
            case 0xD9D9D9: variant="silver";break;
            case 0xE3E3E3: variant="pale_gray";break;
            case 0xFF0000: variant="red";break;
            case 0xFF00FF: variant="magenta";break;
            case 0xFF4400: variant="fiery_orange";break;
            case 0xFF4488: variant="rose";break;
            case 0xFF4CD2: variant="pink";break;
            case 0xFF7F00: case 0xFF8000: variant="orange";break;
            case 0xFFBB33: variant="amber";break;
            case 0xFFFF00: variant="yellow";break;
            case 0xFFFF85: variant="pale_yellow";break;
            case 0xFFFFCC: variant="cream";break;
            case 0xFFFFFF: variant="white";break;
            default: return GameplayIcons.unmapped();
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("variant",variant);
        if("explosive_cool".equals(hint)||"explosive_warm".equals(hint)||"explosive_hot".equals(hint)) {
            result.put("kind","explosive_heat");result.put("stage",hint.substring("explosive_".length()));
        } else if("resin_fortified".equals(hint)) result.put("kind",hint);
        return Collections.unmodifiableMap(result);
    }
}
