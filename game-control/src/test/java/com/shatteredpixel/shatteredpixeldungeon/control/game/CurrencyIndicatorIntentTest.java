package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.CurrencyIndicator;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.Scene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Native currency timers and real UI observation, without GL, Actor turns, or profile files. */
class CurrencyIndicatorIntentTest {
    private int previousGold, previousEnergy;
    private float previousElapsed;
    private boolean previousShowGold;
    private TextObservationFixture textObservation;
    private Scene scene;
    private Choice inventory;
    private NativeIndicator indicator;
    private UiBridge bridge;

    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @BeforeEach void setup() {
        previousGold = Dungeon.gold;
        previousEnergy = Dungeon.energy;
        previousElapsed = Game.elapsed;
        previousShowGold = CurrencyIndicator.showGold;
        Dungeon.gold = Dungeon.energy = 0;
        CurrencyIndicator.showGold = false;
        textObservation = new TextObservationFixture();
        scene = new Scene();
        inventory = new Choice();
        scene.add(inventory);
        indicator = new NativeIndicator();
        inventory.add(indicator);
        bridge = new UiBridge(() -> scene);
    }

    @AfterEach void restore() {
        Dungeon.gold = previousGold;
        Dungeon.energy = previousEnergy;
        Game.elapsed = previousElapsed;
        CurrencyIndicator.showGold = previousShowGold;
        textObservation.close();
    }

    @Test void floorEntryCurrencyAppearsAndExpiresWithoutInvalidatingIntent() {
        String hiddenIntent = bridge.intentSignature();
        String hiddenPresentation = bridge.contextSignature();
        List<Map<String, Object>> actions = bridge.describeActions();
        Dungeon.gold = 478;
        Dungeon.energy = 3;
        advance(0f);
        assertTextVisible("478");
        assertTextVisible("3");
        assertNotEquals(hiddenPresentation, bridge.contextSignature());
        assertEquals(hiddenIntent, bridge.intentSignature(), "Only the native decorative indicator appeared");
        assertEquals(actions, bridge.describeActions());
        String shownPresentation = bridge.contextSignature();

        advance(1.5f);
        assertTrue(indicator.gold.am > 0f && indicator.gold.am < 1f);
        assertTextVisible("478");
        assertEquals(hiddenIntent, bridge.intentSignature());
        advance(0.5f);
        assertFalse(indicator.gold.visible);
        assertFalse(indicator.energy.visible);
        assertNoText("478");
        assertNoText("3");
        assertNotEquals(shownPresentation, bridge.contextSignature());
        assertEquals(hiddenIntent, bridge.intentSignature());
        assertEquals(actions, bridge.describeActions());
        assertEquals(0, inventory.clicks);
    }

    @Test void tradeWindowCurrencyPinAndNativeReleaseKeepTheSameIntent() {
        Dungeon.gold = 806;
        // WndTradeItem sets this flag on opening and clears it in hide().
        CurrencyIndicator.showGold = true;
        advance(0f);
        for (int i = 0; i < 4; i++) advance(0.5f);
        assertTextVisible("806");
        String intentAfterBack = bridge.intentSignature();
        String presentationAfterBack = bridge.contextSignature();
        List<Map<String, Object>> actions = bridge.describeActions();
        CurrencyIndicator.showGold = false;

        advance(0.75f);
        assertTextVisible("806");
        assertEquals(intentAfterBack, bridge.intentSignature());
        advance(0.25f);
        assertNoText("806");
        assertNotEquals(presentationAfterBack, bridge.contextSignature());
        assertEquals(intentAfterBack, bridge.intentSignature(), "The one-second post-trade fade must not stale the next input");
        assertEquals(actions, bridge.describeActions());
        assertEquals(806, Dungeon.gold);
    }

    @Test void ordinaryNumericTextAndControlsRemainPartOfIntent() {
        BitmapText ordinary = new MeasuredText();
        ordinary.text(ResourceTextFixture.literal("478"));
        scene.add(ordinary);
        String before = bridge.intentSignature();
        ordinary.text(ResourceTextFixture.literal("479"));
        assertNotEquals(before, bridge.intentSignature(), "Numeric text outside CurrencyIndicator is not exempt");
        before = bridge.intentSignature();
        ordinary.visible = false;
        assertNotEquals(before, bridge.intentSignature());

        before = bridge.intentSignature();
        inventory.active = false;
        assertNotEquals(before, bridge.intentSignature());
        inventory.active = true;
        before = bridge.intentSignature();
        inventory.label = "A different choice";
        assertNotEquals(before, bridge.intentSignature());

        // Even a future control under the indicator must keep its own direct label in intent.
        Choice nested = new Choice();
        BitmapText nestedLabel = new MeasuredText();
        nestedLabel.text(ResourceTextFixture.literal("20"));
        nested.add(nestedLabel);
        indicator.add(nested);
        before = bridge.intentSignature();
        nestedLabel.text(ResourceTextFixture.literal("30"));
        assertNotEquals(before, bridge.intentSignature());
        before = bridge.intentSignature();
        nested.active = false;
        assertNotEquals(before, bridge.intentSignature());
        assertEquals(0, inventory.clicks + nested.clicks);
    }

    private void advance(float elapsed) { Game.elapsed = elapsed; indicator.update(); }
    private void assertTextVisible(String value) { assertTrue(nodes().stream().anyMatch(node -> value.equals(node.get("text")))); }
    private void assertNoText(String value) { assertTrue(nodes().stream().noneMatch(node -> value.equals(node.get("text")))); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> nodes() {
        return (List<Map<String, Object>>) UiDrawFixture.capture(bridge).describeUi().get("controls");
    }

    /** Only font measurement is stubbed; CurrencyIndicator.update() and its timers are unchanged. */
    static final class NativeIndicator extends CurrencyIndicator {
        BitmapText gold, energy;
        @Override protected void createChildren() {
            gold = new MeasuredText();
            energy = new MeasuredText();
            gold.visible = energy.visible = false;
            add(gold);
            add(energy);
            try {
                Field nativeGold = CurrencyIndicator.class.getDeclaredField("gold");
                nativeGold.setAccessible(true);
                nativeGold.set(this, gold);
                Field nativeEnergy = CurrencyIndicator.class.getDeclaredField("energy");
                nativeEnergy.setAccessible(true);
                nativeEnergy.set(this, energy);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }
    }

    private static final class MeasuredText extends BitmapText {
        MeasuredText() { camera = new Camera(0, 0, 100, 100, 1); measure(); }
        @Override public synchronized void measure() { width = 24; height = 8; }
    }

    private static final class Choice extends Button {
        String label = "Inventory";
        int clicks;
        @Override protected void createChildren() { }
        @Override protected String hoverText() { return ResourceTextFixture.literal(label); }
        @Override protected void onClick() { clicks++; }
    }
}
