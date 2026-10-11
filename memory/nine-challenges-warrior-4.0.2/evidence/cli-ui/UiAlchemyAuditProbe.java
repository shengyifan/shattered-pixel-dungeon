package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.ui.*;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.watabou.noosa.*;
import com.watabou.noosa.ui.Component;
import com.watabou.gltextures.*;
import java.util.*;

/** Isolated source-selected native widget states; no game/profile/GL backend. */
public class UiAlchemyAuditProbe {
    static int checks;
    static void check(String name,boolean ok) {if(!ok)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    static <T>T alloc(Class<T> type)throws Exception {return FloatingAppearanceTest.allocate(type);}
    static void field(Object value,Class<?> cls,String key,Object data)throws Exception {FloatingAppearanceTest.field(value,cls,key,data);}
    static <T extends Group>T group(T g)throws Exception {
        field(g,Group.class,"members",new ArrayList<>());g.exists=g.alive=g.active=g.visible=true;g.camera=new Camera(0,0,300,300,1);return g;
    }
    static Image icon(int index)throws Exception {
        Image image=new Image();image.texture=texture;image.frame(ItemSpriteSheet.film.get(index));image.x=image.y=10;image.camera=new Camera(0,0,300,300,1);return image;
    }
    static SmartTexture texture;
    static String wire(UiBridge bridge,boolean src,boolean full) {
        bridge.captureDrawnEvidence();return JsonCodec.encode(CompactProtocol.project(Map.of("ui",bridge.describeUi(),"actions",bridge.describeActions()),src,full));
    }
    static String raw(UiBridge bridge) {bridge.captureDrawnEvidence();return JsonCodec.encode(bridge.describeUi());}
    static ItemSlot slot()throws Exception {
        ItemSlot slot=group(alloc(ItemSlot.class));field(slot,ItemSlot.class,"item",new Food());
        ItemSprite sprite=new ItemSprite(ItemSpriteSheet.RATION);
        // Initialize only ordinary visual fields; native GameplayIcons reads the displayed asset/frame.
        sprite.exists=sprite.alive=sprite.visible=sprite.active=true;sprite.texture=texture;
        sprite.origin=new com.watabou.utils.PointF();sprite.scale=new com.watabou.utils.PointF(1,1);
        sprite.rm=sprite.gm=sprite.bm=sprite.am=1; sprite.frame(ItemSpriteSheet.film.get(ItemSpriteSheet.RATION));sprite.x=sprite.y=10;
        slot.add(sprite);field(slot,ItemSlot.class,"sprite",sprite);
        for(String key:List.of("status","extra","level")) {
            BitmapText text=new BitmapText("",null);text.x=text.y=10;text.width=8;text.height=8;slot.add(text);field(slot,ItemSlot.class,key,text);
        }
        return slot;
    }
    public static void main(String[] args)throws Exception {
        GameSnapshotterTest.resourceOnlyRuntime();
        try(TextObservationFixture ignored=new TextObservationFixture()) {
            @SuppressWarnings("unchecked") Map<Object,SmartTexture> cache=(Map<Object,SmartTexture>)SnapshotFields.read(TextureCache.class,"all");
            texture=alloc(SmartTexture.class);texture.width=256;texture.height=512;cache.put(Assets.Sprites.ITEMS,texture);
            Scene scene=new Scene();
            @SuppressWarnings("unchecked") Class<? extends StyledButton> nativeType=(Class<? extends StyledButton>)Class.forName("com.shatteredpixel.shatteredpixeldungeon.windows.WndJournal$AlchemyTab$1");
            StyledButton page=group(alloc(nativeType));Image first=icon(ItemSpriteSheet.SEED_HOLDER);page.add(first);field(page,StyledButton.class,"icon",first);scene.add(page);
            UiBridge bridge=new UiBridge(()->scene);
            check("Source icon SEED is independently recognized",GameplayIcons.image(first).toString().contains("seed_holder"));
            String before=raw(bridge);String playBefore=wire(bridge,false,false);String srcBefore=wire(bridge,true,true);
            first.frame(ItemSpriteSheet.film.get(ItemSpriteSheet.POTION_HOLDER));
            check("Changed source icon POTION is independently recognized",GameplayIcons.image(first).toString().contains("potion_holder"));
            check("CONFIRMED: native AlchemyTab RedButton icon change is absent from UiBridge",before.equals(raw(bridge)));
            check("CONFIRMED: icon change absent in play",playBefore.equals(wire(bridge,false,false)));
            check("CONFIRMED: icon change absent even in full+src",srcBefore.equals(wire(bridge,true,true)));
            first.color(Window.TITLE_COLOR);
            check("CONFIRMED: native selected-page highlight absent",before.equals(raw(bridge)));
            check("Positive control: active native page has activate operation",bridge.describeActions().stream().anyMatch(a->"ui.activate".equals(a.get("action"))));
            page.active=false;raw(bridge);
            check("Positive control: disabling that same native page removes activate operation",bridge.describeActions().stream().noneMatch(a->"ui.activate".equals(a.get("action"))));
            page.active=true;
            System.out.println("ALCHEMY_PAGE_NODE="+before);
            scene.clear();
            ScrollPane pane=group(alloc(ScrollPane.class));Component content=new Component();content.camera=new Camera(0,0,300,300,1);pane.add(content);field(pane,ScrollPane.class,"content",content);
            ScrollingGridPane.GridItem entry=group(alloc(ScrollingGridPane.GridItem.class));Image entryIcon=icon(ItemSpriteSheet.GOLD);entry.add(entryIcon);field(entry,ScrollingGridPane.GridItem.class,"icon",entryIcon);content.add(entry);scene.add(pane);
            String gridBefore=raw(bridge);entryIcon.frame(ItemSpriteSheet.film.get(ItemSpriteSheet.ENERGY));
            check("CONFIRMED: native journal GridItem icon difference is absent",gridBefore.equals(raw(bridge)));
            check("Journal entry remains selectable",bridge.describeActions().stream().anyMatch(a->"ui.select".equals(a.get("action"))));
            System.out.println("JOURNAL_ENTRY_NODE="+gridBefore);
            scene.clear();ItemSlot ingredient=slot();scene.add(ingredient);
            String enough=raw(bridge);ItemSprite sprite=(ItemSprite)SnapshotFields.read(ingredient,"sprite");sprite.alpha(0.3f);
            check("CONFIRMED: QuickRecipe ingredient shortage alpha does not change ItemSlot semantic observation",enough.equals(raw(bridge)));
            System.out.println("RECIPE_INGREDIENT_NODE="+enough);
            scene.clear();
            IconButton nativeIcon=group(alloc(IconButton.class));Image known=icon(ItemSpriteSheet.SEED_HOLDER);nativeIcon.add(known);field(nativeIcon,IconButton.class,"icon",known);scene.add(nativeIcon);
            check("Positive control: actual IconButton retains icon symbol",raw(bridge).contains("seed_holder"));
            BitmapText price=new BitmapText(ResourceTextFixture.literal("Buy for 120 gold"),null);price.x=price.y=10;price.width=90;price.height=8;price.camera=new Camera(0,0,300,300,1);scene.add(price);
            check("Positive control: displayed price text survives play",wire(bridge,false,false).contains("Buy for 120 gold"));
            System.out.println("PASS TOTAL="+checks+"; native widget capture/compact projection, not live UI gameplay");
        }
    }
}
