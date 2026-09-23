package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.Artifact;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ThrowingStone;
import com.shatteredpixel.shatteredpixeldungeon.ui.ItemSlot;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.Camera;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** The native update selects the meaning; later capture neither invokes status nor infers it from digits. */
class ItemStatusMeaningTest {
    @BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}

    @Test void exactInheritedQuantityMethodsAreTaggedButNumericOverridesAndArtifactChargesAreNot()throws Exception {
        Item ordinary=new Item().quantity(2);
        assertEquals("quantity",shown(slot(ordinary)).get("status_kind"));
        assertEquals("quantity",shown(slot(new InheritedItem().quantity(2))).get("status_kind"));
        assertEquals("quantity",shown(slot(new ThrowingStone().quantity(1))).get("status_kind"));
        assertFalse(shown(slot(new OverrideStone())).containsKey("status_kind"));
        SpecialItem special=new SpecialItem();MeaningSlot specialSlot=slot(special);
        assertEquals(1,special.calls);assertEquals("1",shown(specialSlot).get("status"));
        assertFalse(shown(specialSlot).containsKey("status_kind"));assertEquals(1,special.calls,"Capture must not call the displayed item's status getter again");
        Map<?,?> artifact=shown(slot(new ChargedArtifact()));assertEquals("1",artifact.get("status"));assertFalse(artifact.containsKey("status_kind"));
    }

    @Test void changedComponentCannotReuseAnOldQuantityRole()throws Exception {
        MeaningSlot slot=slot(new Item().quantity(2));assertEquals("quantity",shown(slot).get("status_kind"));
        slot.changeStatus("1");Map<?,?> changed=shown(slot);assertEquals("1",changed.get("status"));assertFalse(changed.containsKey("status_kind"));
    }

    private static MeaningSlot slot(Item item)throws Exception {
        MeaningSlot slot=FloatingAppearanceTest.allocate(MeaningSlot.class);
        slot.exists=slot.visible=slot.active=slot.alive=true;slot.camera=new Camera(0,0,100,100,1);
        slot.prepare(item);slot.updateText();return slot;
    }
    private static Map<?,?> shown(ItemSlot slot){return (Map<?,?>)slot.gameplayStatus().get("shown");}
    private static class InheritedItem extends Item { }
    private static final class SpecialItem extends Item {
        int calls;
        @Override public String status(){calls++;return "1";}
    }
    private static final class OverrideStone extends ThrowingStone {
        @Override public String status(){return "1";}
    }
    private static final class ChargedArtifact extends Artifact {
        ChargedArtifact(){levelKnown=cursedKnown=true;charge=1;chargeCap=0;levelCap=10;}
    }
    private static final class Text extends BitmapText {
        Text(){super("",null);x=y=10;width=16;height=8;}
        @Override public synchronized void measure(){width=16;height=8;}
    }
    private static final class MeaningSlot extends ItemSlot {
        void prepare(Item value){item=value;status=new Text();extra=new Text();level=new Text();status.parent=extra.parent=level.parent=this;}
        void changeStatus(String value){status.text(value);}
        @Override protected void layout(){ }
    }
}
