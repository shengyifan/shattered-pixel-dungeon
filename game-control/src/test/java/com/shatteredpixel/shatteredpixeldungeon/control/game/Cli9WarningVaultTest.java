package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.quest.vault.VaultBossElemental;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.quest.vault.VaultBossElemental.ElementalForm;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.quest.vault.VaultBossElemental.FrostCone;
import com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell.PendingWarning;
import com.shatteredpixel.shatteredpixeldungeon.sprites.VaultBossElementalSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.AttackIndicator;
import com.watabou.noosa.NinePatch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

import static com.shatteredpixel.shatteredpixeldungeon.control.game.PendingWarningTest.get;
import static com.shatteredpixel.shatteredpixeldungeon.control.game.PendingWarningTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Real Vault producers in isolated memory fixtures; no saves, profiles or actor scheduler. */
class Cli9WarningVaultTest {
    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @Test void fireballPublishesOnlyNativePublicCellsAndRemovalCancelsTheEpisode() throws Exception {
        try (WarningProducerATest.Harness h = new WarningProducerATest.Harness()) {
            VaultBossElemental boss = h.place(new VaultBossElemental(), 33);
            h.place(Dungeon.hero, 55);
            set(boss, VaultBossElemental.class, "lastEnemyPos", 54);
            h.f.level.solid[67] = true;
            h.f.level.heroFOV[47] = false;

            boss.setupFireBall(Dungeon.hero);
            PendingWarning warning = special(boss);
            assertNotNull(warning);
            assertEquals(56, get(boss, VaultBossElemental.class, "spTargetCell"));
            Set<Integer> expected = Set.of(45, 46, 55, 56, 57, 65, 66);
            assertEquals(expected, h.redCells(), "Solid cells and a never-observed target remain absent");
            h.fadeMarkers();
            h.f.level.heroFOV[47] = true;
            assertEquals(expected, h.redCells(), "Marker expiry cannot reconstruct the hidden target");

            Actor.remove(boss);
            assertFalse(warning.isPending());
            assertTrue(h.redCells().isEmpty());
        }
    }

    @Test void lightningPublishesItsNativeBoltAndAlternatingNeighbourPattern() throws Exception {
        try (WarningProducerATest.Harness h = new WarningProducerATest.Harness()) {
            VaultBossElemental boss = h.place(new VaultBossElemental(), 33);
            h.place(Dungeon.hero, 55);
            boss.setupLightningBolt(Dungeon.hero);
            PendingWarning warning = special(boss);
            Set<Integer> expected = new TreeSet<>(Set.of(33, 44, 55, 66, 77, 88));
            int offset = (Integer) get(boss, VaultBossElemental.class, "lightningOfs");
            expected.addAll(offset == 0 ? Set.of(44, 46, 64, 66) : Set.of(45, 54, 56, 65));
            assertEquals(expected, h.redCells());
            h.fadeMarkers();
            assertEquals(expected, h.redCells());

            boss.setupLightningBolt(Dungeon.hero);
            assertFalse(warning.isPending(), "A new native special replaces the previous preparation");
            assertNotSame(warning, special(boss));
            assertTrue(special(boss).isPending());
        }
    }

    @Test void frostPreparationTransfersToItsBuffAndEachNativeRingReplacesTheWarning() throws Exception {
        try (WarningProducerATest.Harness h = new WarningProducerATest.Harness()) {
            VaultBossElemental boss = h.place(new VaultBossElemental(), 42);
            h.place(Dungeon.hero, 47); // Not scheduled: this fixture validates warning production, not damage.
            boss.setupFrostCone(Dungeon.hero);
            PendingWarning first = special(boss);
            Set<Integer> firstCells = h.redCells();
            assertFalse(firstCells.isEmpty());
            assertTrue(firstCells.stream().allMatch(cell -> Dungeon.level.trueDistance(cell, boss.pos) <= 2));
            h.fadeMarkers();

            boss.doFrostCone(47);
            FrostCone cone = boss.buff(FrostCone.class);
            assertNotNull(cone);
            assertNull(special(boss));
            assertSame(first, get(cone, FrostCone.class, "ringWarning"));
            assertSame(cone, get(first, PendingWarning.class, "owner"));
            assertEquals(firstCells, h.redCells(), "Attaching the cone has not fired its initial ring");

            assertTrue(cone.act());
            PendingWarning second = (PendingWarning) get(cone, FrostCone.class, "ringWarning");
            assertFalse(first.isPending());
            assertNotNull(second);
            assertNotSame(first, second);
            Set<Integer> secondCells = h.redCells();
            assertFalse(secondCells.isEmpty());
            assertTrue(secondCells.stream().allMatch(cell -> {
                float distance = Dungeon.level.trueDistance(cell, boss.pos);
                return distance > 2 && distance <= 4;
            }));
            assertTrue(firstCells.stream().noneMatch(secondCells::contains));
            h.fadeMarkers();
            assertEquals(secondCells, h.redCells());

            assertTrue(cone.act());
            PendingWarning third = (PendingWarning) get(cone, FrostCone.class, "ringWarning");
            assertFalse(second.isPending());
            assertNotNull(third);
            assertNotSame(second, third);
            Set<Integer> thirdCells = h.redCells();
            assertFalse(thirdCells.isEmpty());
            assertTrue(thirdCells.stream().allMatch(cell -> {
                float distance = Dungeon.level.trueDistance(cell, boss.pos);
                return distance > 4 && distance <= 6;
            }));
            cone.detach();
            assertFalse(third.isPending(), "Removing the native cone cancels its remaining ring");
            assertTrue(h.redCells().isEmpty());
        }
    }

    @Test void paralysisDelaysPreparedSpecialWithoutCancellingItsPublicWarning() throws Exception {
        try (WarningProducerATest.Harness h = new WarningProducerATest.Harness()) {
            VaultBossElemental boss = h.place(new VaultBossElemental(), 33);
            h.place(Dungeon.hero, 55);
            set(boss, Mob.class, "enemy", Dungeon.hero);
            boss.state = boss.HUNTING;
            boss.setupLightningBolt(Dungeon.hero);
            PendingWarning warning = special(boss);
            Set<Integer> cells = h.redCells();
            h.fadeMarkers();
            boss.paralysed = 1;

            assertTrue(act(boss));
            assertTrue(warning.isPending());
            assertSame(warning, special(boss));
            assertEquals(55, get(boss, VaultBossElemental.class, "spTargetCell"));
            assertEquals(cells, h.redCells());
        }
    }

    @Test void actualFormSwitchCancelsPendingSpecialAndRetainsNativeCooldownChanges() throws Exception {
        try (WarningProducerATest.Harness h = new WarningProducerATest.Harness()) {
            h.preserve(AttackIndicator.class.getDeclaredField("instance"));
            h.f.install(Assets.Sprites.VAULT_BOSS_ELEMENTAL, 496, 120);
            new HeadlessAttackIndicator();
            VaultBossElemental boss = h.place(new VaultBossElemental(), 33);
            boss.sprite.killAndErase();
            boss.sprite = new VaultBossElementalSprite();
            boss.sprite.ch = boss;
            boss.sprite.place(boss.pos);
            h.f.scene.add(boss.sprite);
            boss.alignment = Char.Alignment.ALLY; // Avoid unrelated quickslot/target-health UI in this fixture.
            set(boss, VaultBossElemental.class, "form", ElementalForm.FIRE);
            set(boss, VaultBossElemental.class, "envAttackCooldown", 20);
            h.place(Dungeon.hero, 55);
            boss.setupLightningBolt(Dungeon.hero);
            PendingWarning warning = special(boss);
            assertFalse(h.redCells().isEmpty());
            h.fadeMarkers();

            boss.setElementalForm(ElementalForm.FROST);
            assertEquals(ElementalForm.FROST, boss.curForm());
            assertFalse(warning.isPending());
            assertNull(special(boss));
            assertEquals(-1, get(boss, VaultBossElemental.class, "spTargetCell"));
            assertEquals(0, get(boss, VaultBossElemental.class, "spAttackCooldown"));
            assertEquals(10, get(boss, VaultBossElemental.class, "envAttackCooldown"));
            assertTrue(h.redCells().isEmpty());
        }
    }

    @Test void nativeDeathCancelsPreparedBossWarnings() throws Exception {
        try (WarningProducerATest.Harness h = new WarningProducerATest.Harness()) {
            VaultBossElemental boss = h.place(new VaultBossElemental(), 33);
            h.place(Dungeon.hero, 55);
            boss.setupLightningBolt(Dungeon.hero);
            PendingWarning warning = special(boss);
            assertFalse(h.redCells().isEmpty());
            boss.alignment = Char.Alignment.ALLY;
            Dungeon.hero.HP = 0; // Suppress reward, badge and victory-banner paths unrelated to owner death.

            boss.die(null);
            assertFalse(boss.isAlive());
            assertFalse(warning.isPending());
            assertTrue(h.redCells().isEmpty());
        }
    }

    private static PendingWarning special(VaultBossElemental boss) throws Exception {
        return (PendingWarning) get(boss, VaultBossElemental.class, "specialWarning");
    }

    private static boolean act(VaultBossElemental boss) throws Exception {
        Method method = VaultBossElemental.class.getDeclaredMethod("act");
        method.setAccessible(true);
        try { return (Boolean) method.invoke(boss); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            if (error.getCause() instanceof Error) throw (Error) error.getCause();
            throw error;
        }
    }

    /** Only suppresses unrelated input/layout plumbing; target selection runs its native method. */
    private static final class HeadlessAttackIndicator extends AttackIndicator {
        @Override protected void createChildren() { bg = new NinePatch(Assets.Interfaces.ICONS, 1); add(bg); }
        @Override protected synchronized void layout() { }
    }
}
