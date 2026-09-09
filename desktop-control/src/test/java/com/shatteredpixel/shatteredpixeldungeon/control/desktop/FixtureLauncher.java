package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.badlogic.gdx.Gdx;
import com.shatteredpixel.shatteredpixeldungeon.*;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.MobSpawner;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.*;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.*;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.*;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.warrior.*;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.mage.*;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.rogue.*;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.huntress.*;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.duelist.*;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.*;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.*;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Gnoll;
import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.game.GameController;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopLauncher;
import com.shatteredpixel.shatteredpixeldungeon.desktop.ProfileLock;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.Artifact;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClassArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfAccuracy;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfFireblast;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Blazing;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sword;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfIdentify;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfUpgrade;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTransmutation;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfRage;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sai;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.ActionIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.watabou.noosa.Game;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.utils.PathFinder;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** TEST SOURCE SET ONLY. Never package this launcher or treat its runs as win evidence. */
public final class FixtureLauncher {
    private static final Map<String, Supplier<ArmorAbility>> ABILITIES = new LinkedHashMap<>();
    private static final Map<String, HeroClass> ABILITY_CLASSES = new LinkedHashMap<>();
    static {
        add(HeroClass.WARRIOR, HeroicLeap::new, Shockwave::new, Endure::new);
        add(HeroClass.MAGE, ElementalBlast::new, WildMagic::new, WarpBeacon::new);
        add(HeroClass.ROGUE, SmokeBomb::new, DeathMark::new, ShadowClone::new);
        add(HeroClass.HUNTRESS, SpectralBlades::new, NaturesPower::new, SpiritHawk::new);
        add(HeroClass.DUELIST, Challenge::new, ElementalStrike::new, Feint::new);
        add(HeroClass.CLERIC, AscendedForm::new, Trinity::new, PowerOfMany::new);
        add(HeroClass.WARRIOR, Ratmogrify::new);
    }

    @SafeVarargs private static void add(HeroClass cls, Supplier<ArmorAbility>... suppliers) {
        for (Supplier<ArmorAbility> supplier : suppliers) {
            String name = supplier.get().getClass().getSimpleName();
            ABILITIES.put(name, supplier); ABILITY_CLASSES.put(name, cls);
        }
    }

    static final class Fixture {
        final String id, kind, name;
        final boolean empty;
        final HeroClass heroClass;
        final HeroSubClass subclass;
        Fixture(String id) {
            this.id = id;
            String[] pieces = id.split(":");
            if (pieces.length < 2 || pieces.length > 3 || pieces.length == 3 && !pieces[2].equals("empty")) throw new IllegalArgumentException("Invalid fixture id");
            kind = pieces[0]; name = pieces[1]; empty = pieces.length == 3;
            if (kind.equals("class")) { heroClass = HeroClass.valueOf(name); subclass = HeroSubClass.NONE; }
            else if (kind.equals("subclass")) {
                subclass = HeroSubClass.valueOf(name);
                heroClass = Arrays.stream(HeroClass.values()).filter(c -> Arrays.asList(c.subClasses()).contains(subclass)).findFirst().orElseThrow();
            } else if (kind.equals("armor") && ABILITIES.containsKey(name)) {
                heroClass = ABILITY_CLASSES.get(name); subclass = HeroSubClass.NONE;
            } else if (kind.equals("ui") && Arrays.asList("identify", "upgrade", "cancel-confirm", "alchemy", "travel").contains(name)) {
                heroClass = HeroClass.WARRIOR; subclass = HeroSubClass.NONE;
            } else if (kind.equals("lowfreq") && LowFrequencyFixtures.supports(name)) {
                heroClass = HeroClass.WARRIOR; subclass = HeroSubClass.NONE;
            } else if (kind.equals("menu") && MenuScenarioFixtures.supports(name)) {
                heroClass = HeroClass.WARRIOR; subclass = HeroSubClass.NONE;
            } else if (kind.equals("scenario") && TransitionScenarioFixtures.supports(name)) {
                heroClass = HeroClass.WARRIOR; subclass = HeroSubClass.NONE;
            } else if (kind.equals("notes") && NoteScenarioFixtures.supports(name)) {
                heroClass = HeroClass.WARRIOR; subclass = HeroSubClass.NONE;
            } else if (kind.equals("container") && ContainerScenarioFixtures.supports(name)) {
                heroClass = HeroClass.WARRIOR; subclass = HeroSubClass.NONE;
            } else if (kind.equals("itemui") && ItemWindowFixtures.supports(name)) {
                heroClass = HeroClass.WARRIOR; subclass = HeroSubClass.NONE;
            } else if (kind.equals("ending") && EndingScenarioFixtures.supports(name)) {
                heroClass = HeroClass.WARRIOR; subclass = HeroSubClass.NONE;
            } else if (kind.equals("inspect") && InspectedItemFixtures.supports(name)) {
                heroClass = HeroClass.DUELIST; subclass = HeroSubClass.NONE;
            } else if (kind.equals("spell") && name.matches("[A-Za-z]+")) {
                heroClass = HeroClass.CLERIC;
                subclass = Arrays.asList("Smite", "LayOnHands", "AuraOfProtection", "WallOfLight").contains(name) ? HeroSubClass.PALADIN
                        : name.equals("GuidingLight") ? HeroSubClass.NONE : HeroSubClass.PRIEST;
            } else if (kind.equals("weapon") && name.matches("[A-Za-z]+") && !name.equals("MeleeWeapon") && !name.equals("MagesStaff")) {
                heroClass = HeroClass.DUELIST; subclass = HeroSubClass.NONE;
            } else if (kind.equals("monk") && Arrays.asList("Flurry", "Focus", "Dash", "DragonKick", "Meditate").contains(name)) {
                heroClass = HeroClass.DUELIST; subclass = HeroSubClass.MONK;
            } else throw new IllegalArgumentException("Unknown fixture id");
        }
    }

    public static void main(String[] args) throws Exception {
        String id = null; Path profile = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--fixture") && i + 1 < args.length) id = args[++i];
            else if (args[i].equals("--data-dir") && i + 1 < args.length) profile = Path.of(args[++i]);
            else if (!args[i].equals("run") && !args[i].equals("--machine")) throw new IllegalArgumentException("Unknown test argument");
        }
        if (id == null || profile == null) throw new IllegalArgumentException("--fixture and --data-dir are required");
        Fixture fixture = new Fixture(id);
        Path allowed = Path.of("desktop-control/build/fixtures").toAbsolutePath().normalize();
        Files.createDirectories(allowed);
        allowed = allowed.toRealPath();
        profile = profile.toAbsolutePath().normalize();
        if (!profile.startsWith(allowed) || profile.equals(allowed)) throw new IllegalArgumentException("Fixture profile must be below desktop-control/build/fixtures");
        Files.createDirectories(profile);
        profile = profile.toRealPath();
        if (!profile.startsWith(allowed)) throw new IllegalArgumentException("Fixture profile resolves outside its test root");
        write(profile.resolve("test_fixture.json"), map("test_fixture", true, "counts_as_win", false, "fixture", fixture.id,
                "hero_class", fixture.heroClass.name(), "subclass", fixture.subclass.name(), "injected", false));

        PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setProperty("Specification-Title", "Shattered Pixel Dungeon");
        System.setProperty("Implementation-Title", "com.shatteredpixel.shatteredpixeldungeon");
        System.setProperty("Specification-Version", "3.3.8");
        System.setProperty("Implementation-Version", "896");
        try (ProfileLock lock = new ProfileLock(profile); AuditStore store = new AuditStore(profile.resolve("audit"))) {
            configureTestUi(profile);
            AtomicReference<MachineSession> reference = new AtomicReference<>();
            GameController game = new GameController(profile, store.menuScope(), error -> { if (reference.get() != null) reference.get().recordException(error); });
            try (MachineSession session = new MachineSession(store, game, protocol)) {
                reference.set(session);
                Game.observer = new FixtureObserver(game, fixture, profile).forwardingObserver();
                System.setOut(new PrintStream(new LogOutput(session, "fixture_stdout"), true, StandardCharsets.UTF_8));
                System.setErr(new PrintStream(new LogOutput(session, "fixture_stderr"), true, StandardCharsets.UTF_8));
                Thread reader = new Thread(() -> session.read(System.in), "Fixture Machine Input");
                reader.setDaemon(true); reader.start();
                try { DesktopLauncher.launch(new String[0], profile, (thread, error) -> { session.recordException(error); game.exitNow(); }, true); }
                finally { Game.observer = RuntimeObserver.NONE; }
            }
        }
    }

    private static void configureTestUi(Path profile)throws IOException {
        // Native startup reads these before the first frame. All paths were already restricted
        // to the isolated fixture root and the profile lock is held before touching preferences.
        Path file=profile.resolve("settings.xml");
        if(Files.isSymbolicLink(file))throw new IOException("Fixture settings must not be a symbolic link");
        Properties settings=new Properties();
        if(Files.isRegularFile(file))try(InputStream input=Files.newInputStream(file)){settings.loadFromXML(input);}
        if(Languages.CHI_SMPL.code().equals(settings.getProperty(SPDSettings.KEY_LANG))
                &&"false".equals(settings.getProperty(SPDSettings.KEY_FULLSCREEN)))return;
        settings.setProperty(SPDSettings.KEY_LANG,Languages.CHI_SMPL.code());
        settings.setProperty(SPDSettings.KEY_FULLSCREEN,"false");
        try(OutputStream output=Files.newOutputStream(file)){settings.storeToXML(output,"Isolated fixture UI preferences","UTF-8");}
    }

    private static final class FixtureObserver implements RuntimeObserver {
        final GameController game; final Fixture fixture; final Path profile;
        boolean menuPrepared, injected; String lastVersion, lastUiVersion;
        Boolean lastWindowFocus;
        Mob fixtureTarget;
        FixtureObserver(GameController game, Fixture fixture, Path profile) { this.game=game; this.fixture=fixture; this.profile=profile; }
        RuntimeObserver forwardingObserver() {
            // Forward current and future scheduler/lease hooks, including sprite handoffs,
            // instead of accidentally inheriting permissive interface defaults in tests.
            return (RuntimeObserver) java.lang.reflect.Proxy.newProxyInstance(RuntimeObserver.class.getClassLoader(),
                    new Class<?>[]{RuntimeObserver.class}, (proxy, method, args) -> {
                        if (method.getName().equals("afterFrame") && method.getParameterCount() == 0) { afterFrame(); return null; }
                        try { return method.invoke(game, args); }
                        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
        @Override public void afterFrame() {
            try {
                if (!menuPrepared && Game.scene() != null && !Game.switchingScene()) {
                    Badges.loadGlobal();
                    for (Badges.Badge badge : Badges.Badge.values()) if (badge.name().startsWith("UNLOCK_")) Badges.unlock(badge);
                    if(fixture.kind.equals("menu"))MenuScenarioFixtures.prepare(fixture.name);
                    SPDSettings.language(Languages.CHI_SMPL); Messages.setup(Languages.CHI_SMPL);
                    SPDSettings.fullscreen(false);
                    Gdx.graphics.setTitle("CLI 场景测试 · " + fixture.id);
                    SPDSettings.intro(false);
                    menuPrepared = true;
                }
                if (!injected && Game.scene() instanceof GameScene && Dungeon.hero != null && Dungeon.hero.ready
                        && Actor.isYielded() && !GameScene.interfaceBlockingHero()) {
                    if(fixture.kind.equals("scenario"))TransitionScenarioFixtures.diagnostics(profile);
                    fixtureTarget = prepare(fixture);
                    injected = true;
                    Map<String, Object> meta = map("test_fixture", true, "counts_as_win", false, "fixture", fixture.id,
                            "hero_class", fixture.heroClass.name(), "subclass", fixture.subclass.name(), "injected", true,
                            "ability_label", Dungeon.hero.armorAbility == null ? null : Dungeon.hero.armorAbility.name(),
                            "language",Messages.lang().name(),"fullscreen",Gdx.graphics.isFullscreen());
                    write(profile.resolve("test_fixture.json"), meta);
                }
                game.afterFrame();
                GameController.State state = game.latest();
                if(fixture.kind.equals("inspect"))InspectedItemFixtures.observed(state);
                if(fixture.kind.equals("scenario"))TransitionScenarioFixtures.observed(state);
                if (state != null && !state.version.equals(lastUiVersion)) {
                    UiSceneAssertions.record(profile, state);
                    if(fixture.kind.equals("menu"))Files.writeString(profile.resolve("menu-assertions.jsonl"),
                            JsonCodec.encode(map("test_fixture",true,"internal_assertion_only",true,
                                    "state_version",state.version,"menu",MenuScenarioFixtures.assertions()))+"\n",
                            StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    lastUiVersion = state.version;
                }
                Boolean focused=windowFocused();
                if(!java.util.Objects.equals(lastWindowFocus,focused)){
                    lastWindowFocus=focused;
                    Files.writeString(profile.resolve("fixture-focus.jsonl"),JsonCodec.encode(map("test_fixture",true,
                            "focused",focused,"occurred_at",java.time.Instant.now().toString(),"state_version",state==null?null:state.version))+"\n",
                            StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                }
                if (injected && Dungeon.hero != null && state != null && !state.version.equals(lastVersion)) {
                    lastVersion = state.version;
                    Map<String, Object> checkpoint = map("test_fixture", true, "internal_assertion_only", true,
                            "state_version", state.version, "subclass", Dungeon.hero.subClass.name(),"window_focused",focused,
                            "armor_charge", Dungeon.hero.belongings.armor instanceof ClassArmor ? ((ClassArmor) Dungeon.hero.belongings.armor).charge : null,
                            "target_hp", fixtureTarget == null ? null : fixtureTarget.HP,
                            "monk_energy", Dungeon.hero.buff(MonkEnergy.class) == null ? null : Dungeon.hero.buff(MonkEnergy.class).energy,
                            "tome_charge", Dungeon.hero.belongings.getItem(HolyTome.class) == null ? null : get(Dungeon.hero.belongings.getItem(HolyTome.class), "charge"),
                            "tome_partial", Dungeon.hero.belongings.getItem(HolyTome.class) == null ? null : get(Dungeon.hero.belongings.getItem(HolyTome.class), "partialCharge"),
                            "weapon_charge", Dungeon.hero.buff(MeleeWeapon.Charger.class) == null ? null : Dungeon.hero.buff(MeleeWeapon.Charger.class).charges + Dungeon.hero.buff(MeleeWeapon.Charger.class).partialCharge,
                            "low_frequency", fixture.kind.equals("lowfreq") ? LowFrequencyFixtures.assertions() : null,
                            "transition_scenario", fixture.kind.equals("scenario") ? TransitionScenarioFixtures.assertions() : null,
                            "notes", fixture.kind.equals("notes") ? NoteScenarioFixtures.assertions() : null,
                            "containers", fixture.kind.equals("container") ? ContainerScenarioFixtures.assertions() : null,
                            "item_window", fixture.kind.equals("itemui") ? ItemWindowFixtures.assertions() : null,
                            "ending", fixture.kind.equals("ending") ? EndingScenarioFixtures.assertions() : null,
                            "inspection", fixture.kind.equals("inspect") ? InspectedItemFixtures.assertions() : null,
                            "effect_buffs", buffNames(Dungeon.hero), "target_buffs", fixtureTarget == null ? null : buffNames(fixtureTarget),
                            "trinity_form", Dungeon.hero.armorAbility instanceof Trinity && fixture.kind.equals("spell") ? get(Dungeon.hero.armorAbility, fixture.name.equals("BodyForm") ? "bodyForm" : fixture.name.equals("MindForm") ? "mindForm" : "spiritForm") != null : null);
                    Files.writeString(profile.resolve("fixture-assertions.jsonl"), JsonCodec.encode(checkpoint) + "\n",
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            } catch (Throwable failure) {
                game.onException(failure);
                try { write(profile.resolve("fixture-failure.json"), map("test_fixture", true, "failure", failure.toString())); } catch (IOException ignored) { }
                game.exitNow();
            }
        }
        @Override public void onException(Throwable error) { game.onException(error); }
        @Override public boolean beforeActorResume() { return game.beforeActorResume(); }
        @Override public void onDispose() { game.onDispose(); }
        @Override public void onSave(String id,int slot,Throwable error) { game.onSave(id,slot,error); }
        @Override public boolean exitRequested() { return game.exitRequested(); }
    }

    private static Boolean windowFocused(){
        if(Gdx.graphics==null)return null;
        try {
            Object window=Gdx.graphics.getClass().getMethod("getWindow").invoke(Gdx.graphics);
            return (Boolean)window.getClass().getMethod("isFocused").invoke(window);
        } catch(ReflectiveOperationException error){throw new IllegalStateException("Test window focus probe unavailable",error);}
    }

    private static Mob prepare(Fixture fixture) throws Exception {
        Hero hero = Dungeon.hero;
        if (hero.heroClass != fixture.heroClass) throw new IllegalStateException("Start the fixture's required class through the public menu");
        if (fixture.kind.equals("class") || fixture.kind.equals("menu") || fixture.kind.equals("notes")) return null;
        if (fixture.kind.equals("container")) {
            ContainerScenarioFixtures.prepare(fixture.name, hero);
            return null;
        }
        if (fixture.kind.equals("itemui")) {
            ItemWindowFixtures.prepare(fixture.name,hero);return null;
        }
        if(fixture.kind.equals("ending")){EndingScenarioFixtures.prepare(fixture.name,hero);return null;}
        hero.lvl = 30; hero.STR = 100; hero.HT = hero.HP = 1000;
        hero.subClass = fixture.subclass;
        Talent.initSubclassTalents(hero);
        if(fixture.kind.equals("inspect")) {
            makeArena(hero);InspectedItemFixtures.prepare(fixture.name,hero);
            Item.updateQuickslot();Dungeon.observe();hero.checkVisibleMobs();return null;
        }
        if (fixture.kind.equals("lowfreq")) {
            LowFrequencyFixtures.prepare(fixture.name, hero);
            return null;
        }
        if (fixture.kind.equals("scenario")) {
            TransitionScenarioFixtures.prepare(fixture.name, hero);
            return null;
        }
        if (fixture.kind.equals("ui")) {
            prepareUi(fixture.name, hero);
            Item.updateQuickslot(); Dungeon.observe(); hero.checkVisibleMobs();
            return null;
        }
        if (fixture.kind.equals("spell") || fixture.kind.equals("weapon") || fixture.kind.equals("monk")) {
            makeArena(hero);
            Mob target = addTarget(hero, false);
            if (fixture.kind.equals("spell")) prepareSpell(fixture, hero, target);
            else if(fixture.kind.equals("weapon")) prepareWeapon(fixture, hero, target);
            else prepareMonk(fixture, hero);
            Item.updateQuickslot(); BuffIndicator.refreshHero(); Dungeon.observe(); hero.checkVisibleMobs();
            return target;
        }
        for (Map<Talent, Integer> tier : hero.talents) for (Talent talent : new ArrayList<>(tier.keySet())) tier.put(talent, talent.maxPoints());
        Mob target = addTarget(hero, fixture.name.equals("Ratmogrify"));
        if (fixture.kind.equals("armor")) {
            ArmorAbility ability = ABILITIES.get(fixture.name).get();
            ClassArmor armor = ClassArmor.upgrade(hero, hero.belongings.armor);
            hero.belongings.armor = armor;
            hero.armorAbility = ability;
            Talent.initArmorTalents(hero);
            for (Map<Talent, Integer> tier : hero.talents) for (Talent talent : new ArrayList<>(tier.keySet())) tier.put(talent, talent.maxPoints());
            armor.activate(hero); armor.charge = fixture.empty ? 0 : 100;
            armor.identify(false);
            if (hero.heroClass == HeroClass.MAGE) { new WandOfMagicMissile().identify(false).collect(); new WandOfFireblast().identify(false).collect(); }
            if (ability instanceof Trinity) {
                set(ability, "bodyForm", new Blazing());
                set(ability, "mindForm", new WandOfMagicMissile().identify(false));
                set(ability, "spiritForm", new RingOfAccuracy().identify(false));
            }
            Dungeon.quickslot.setSlot(0, armor);
        } else {
            ActionIndicator.clearAction();
            switch (fixture.subclass) {
                case BERSERKER: {
                    hero.belongings.armor.activate(hero);
                    Berserk buff=Buff.affect(hero,Berserk.class); set(buff,"power",fixture.empty?0f:1f); ActionIndicator.setAction(buff); break;
                }
                case GLADIATOR: {
                    Combo buff=Buff.affect(hero,Combo.class); set(buff,"count",fixture.empty?0:10); set(buff,"comboTime",100f); set(buff,"initialComboTime",100f); ActionIndicator.setAction(buff); break;
                }
                case ASSASSIN: {
                    Buff.prolong(hero,Invisibility.class,100f);
                    Preparation buff=Buff.affect(hero,Preparation.class); set(buff,"turnsInvis",12); ActionIndicator.setAction(buff); break;
                }
                case FREERUNNER: {
                    Momentum buff=Buff.affect(hero,Momentum.class); if(!fixture.empty)for(int i=0;i<10;i++)buff.gainStack(); ActionIndicator.setAction(buff); break;
                }
                case SNIPER: {
                    SnipersMark buff=Buff.prolong(hero,SnipersMark.class,100f); buff.set(target.id(),0); ActionIndicator.setAction(buff); break;
                }
                case CHAMPION: {
                    Sword sword=new Sword(); sword.identify(false); hero.belongings.secondWep=sword;
                    ActionIndicator.setAction(Buff.affect(hero,MeleeWeapon.Charger.class)); break;
                }
                case MONK: {
                    MonkEnergy buff=Buff.affect(hero,MonkEnergy.class); buff.energy=fixture.empty?0:buff.energyCap(); ActionIndicator.setAction(buff); break;
                }
                case PRIEST: case PALADIN: {
                    HolyTome tome=hero.belongings.getItem(HolyTome.class); tome.upgrade(10); tome.charge(hero,1000);
                    if(fixture.empty)set(tome,"charge",0);
                    tome.setQuickSpell(fixture.subclass==HeroSubClass.PRIEST?Radiance.INSTANCE:Smite.INSTANCE); break;
                }
                default: break; // Battlemage, Warlock and Warden have no invented active indicator.
            }
        }
        Item.updateQuickslot(); BuffIndicator.refreshHero(); Dungeon.observe();
        // The fixture target is already visible before the tested command. Match that ready
        // state so the next normal HeroAction is not interrupted as a newly sighted enemy.
        hero.checkVisibleMobs();
        return target;
    }

    private static Mob addTarget(Hero hero, boolean nonRat) {
        for (int offset : PathFinder.NEIGHBOURS8) {
            int cell=hero.pos+offset;
            if(cell>=0 && cell<Dungeon.level.length() && Dungeon.level.passable[cell] && Actor.findChar(cell)==null) {
                Mob rat=nonRat ? new Gnoll() : new Rat(); rat.pos=cell; rat.HT=rat.HP=10000;
                GameScene.add(rat); Buff.prolong(rat,Paralysis.class,1000f); Dungeon.observe(); return rat;
            }
        }
        throw new IllegalStateException("No adjacent fixture target cell");
    }
    private static void prepareUi(String name, Hero hero) {
        if (name.equals("travel")) {
            int width=Dungeon.level.width(), height=Dungeon.level.height(), row=height/2;
            if(width-3<12)throw new IllegalStateException("Fixture level cannot fit a 12-step corridor");
            for(Mob mob:new ArrayList<>(Dungeon.level.mobs)) {
                for(Buff buff:mob.buffs())Actor.remove(buff);
                Actor.remove(mob);
                if(mob.sprite!=null)mob.sprite.killAndErase();
            }
            Dungeon.level.mobs.clear();
            for(Actor actor:Actor.all())if(actor instanceof MobSpawner)Actor.remove(actor);
            for(com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob blob:Dungeon.level.blobs.values())Actor.remove(blob);
            Dungeon.level.blobs.clear();
            for(com.shatteredpixel.shatteredpixeldungeon.items.Heap heap:Dungeon.level.heaps.valueList())if(heap.sprite!=null)heap.sprite.killAndErase();
            Dungeon.level.heaps.clear(); Dungeon.level.plants.clear(); Dungeon.level.traps.clear(); Dungeon.level.transitions.clear();
            for(int y=row-1;y<=row+1;y++)for(int x=0;x<width;x++) {
                int cell=y*width+x;
                Level.set(cell,y==row && x>0 && x<width-1 ? Terrain.EMPTY : Terrain.WALL);
                Dungeon.level.mapped[cell]=true;
                GameScene.updateMap(cell);
            }
            hero.pos=row*width+1; hero.sprite.place(hero.pos); hero.curAction=hero.lastAction=null; hero.resting=false;
        } else if (name.equals("identify")) {
            Sword sword = new Sword(); sword.level(2); sword.collect();
            new ScrollOfIdentify().identify(false).collect();
        } else if (name.equals("upgrade") || name.equals("cancel-confirm")) {
            Sword sword = new Sword(); sword.identify(false); hero.belongings.weapon = sword; sword.activate(hero);
            if (name.equals("upgrade")) new ScrollOfUpgrade().identify(false).collect();
            else new ScrollOfTransmutation().collect(); // intentionally unknown: exercise real cancellation confirmation
        } else if (name.equals("alchemy")) {
            Dungeon.energy = 100;
            new Sungrass.Seed().quantity(3).collect();
            for (int offset : PathFinder.NEIGHBOURS8) {
                int cell = hero.pos + offset;
                if (cell >= 0 && cell < Dungeon.level.length() && Dungeon.level.passable[cell] && Actor.findChar(cell) == null) {
                    Level.set(cell, Terrain.ALCHEMY); GameScene.updateMap(cell); return;
                }
            }
            throw new IllegalStateException("No fixture alchemy station cell");
        }
    }
    private static void makeArena(Hero hero) {
        int width=Dungeon.level.width(), height=Dungeon.level.height();
        int x=Math.max(4, Math.min(width-5, hero.pos%width)), y=Math.max(4, Math.min(height-5, hero.pos/width));
        for(int dy=-3;dy<=3;dy++)for(int dx=-3;dx<=3;dx++) {
            int cell=(y+dy)*width+x+dx;
            Level.set(cell,Terrain.EMPTY); Dungeon.level.plants.remove(cell); GameScene.updateMap(cell);
        }
        hero.pos=y*width+x; hero.sprite.place(hero.pos); Dungeon.observe();
    }
    private static void prepareWeapon(Fixture fixture, Hero hero, Mob target) throws Exception {
        String packageName=fixture.name.equals("Pickaxe") ? "com.shatteredpixel.shatteredpixeldungeon.items.quest."
                : "com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.";
        Class<?> type=Class.forName(packageName+fixture.name);
        if(!MeleeWeapon.class.isAssignableFrom(type))throw new IllegalArgumentException("Not a melee weapon fixture");
        type.getDeclaredMethod("duelistAbility",Hero.class,Integer.class);
        MeleeWeapon weapon=(MeleeWeapon)type.getDeclaredConstructor().newInstance();
        weapon.identify(false); hero.belongings.weapon=weapon; weapon.activate(hero);
        MeleeWeapon.Charger charge=Buff.affect(hero,MeleeWeapon.Charger.class);
        charge.charges=fixture.empty?0:charge.chargeCap(); charge.partialCharge=0;
        if(Arrays.asList("Rapier","Katana","Spear","Glaive").contains(fixture.name)) {
            target.pos=hero.pos+2; target.sprite.place(target.pos);
        }
        if(fixture.name.equals("Greataxe"))hero.HP=hero.HT/3;
        if(Arrays.asList("Gloves","Gauntlet","Sai").contains(fixture.name))Buff.affect(hero,Sai.ComboStrikeTracker.class).hits=3;
    }
    private static void prepareSpell(Fixture fixture, Hero hero, Mob target) throws Exception {
        Class<?> type=Class.forName("com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells."+fixture.name);
        if(!ClericSpell.class.isAssignableFrom(type))throw new IllegalArgumentException("Not a cleric spell fixture");
        ClericSpell spell=(ClericSpell)type.getField("INSTANCE").get(null);
        ArmorAbility ability=null;
        if(Arrays.asList("DivineIntervention","Judgement","Flash").contains(fixture.name))ability=new AscendedForm();
        if(Arrays.asList("BodyForm","MindForm","SpiritForm").contains(fixture.name))ability=new Trinity();
        if(Arrays.asList("BeamingRay","LifeLinkSpell","Stasis").contains(fixture.name))ability=new PowerOfMany();
        if(ability!=null) {
            hero.armorAbility=ability; Talent.initArmorTalents(hero);
            ClassArmor armor=ClassArmor.upgrade(hero,hero.belongings.armor); hero.belongings.armor=armor; armor.activate(hero); armor.charge=100;
        }
        for(Map<Talent,Integer> tier:hero.talents)for(Talent talent:new ArrayList<>(tier.keySet()))tier.put(talent,talent.maxPoints());
        HolyTome tome=hero.belongings.getItem(HolyTome.class); tome.upgrade(10); tome.charge(hero,1000);
        if(fixture.empty){set(tome,"charge",0);set(tome,"partialCharge",0f);}
        if(ability instanceof AscendedForm) { AscendedForm.AscendBuff ascend=Buff.affect(hero,AscendedForm.AscendBuff.class); ascend.reset(); ascend.left=1000; }
        if(ability instanceof PowerOfMany) {
            PowerOfMany.LightAlly ally=new PowerOfMany.LightAlly(hero.lvl); ally.pos=hero.pos+1;
            GameScene.add(ally); Buff.prolong(ally,PowerOfMany.PowerBuff.class,1000f); Buff.prolong(ally,Paralysis.class,1000f);
        }
        if(ability instanceof Trinity) {
            new RingOfAccuracy().identify(false); // discovery of a ring requires its per-run identity mapping too
            Statistics.itemTypesDiscovered.add(Blazing.class);
            Statistics.itemTypesDiscovered.add(WandOfMagicMissile.class);
            Statistics.itemTypesDiscovered.add(RingOfAccuracy.class);
        }
        if(fixture.name.equals("HolyIntuition"))new Sword().collect();
        if(fixture.name.equals("RecallInscription"))Buff.prolong(hero,RecallInscription.UsedItemTracker.class,1000f).item=ScrollOfRage.class;
        if(fixture.name.equals("Cleanse"))Buff.affect(hero,Poison.class).set(100f);
        if(fixture.name.equals("MnemonicPrayer"))Buff.prolong(hero,Haste.class,10f);
        if(!fixture.empty && !tome.canCast(hero,spell))throw new IllegalStateException("Fixture does not meet real canCast preconditions for "+fixture.name);
    }
    private static void prepareMonk(Fixture fixture, Hero hero) {
        for(Map<Talent,Integer> tier:hero.talents)for(Talent talent:new ArrayList<>(tier.keySet()))tier.put(talent,talent.maxPoints());
        MonkEnergy.MonkAbility ability=Arrays.stream(MonkEnergy.MonkAbility.abilities).filter(a->a.getClass().getSimpleName().equals(fixture.name)).findFirst().orElseThrow();
        MonkEnergy energy=Buff.affect(hero,MonkEnergy.class);
        energy.energy=fixture.empty?ability.energyCost()-1:energy.energyCap();
        if(energy.energy>=1)ActionIndicator.setAction(energy);else ActionIndicator.clearAction();
        if(fixture.name.equals("Meditate")){hero.HP=hero.HT/2;Buff.affect(hero,Poison.class).set(30f);}
    }
    private static List<String> buffNames(com.shatteredpixel.shatteredpixeldungeon.actors.Char character) {
        List<String> result=new ArrayList<>();for(Buff buff:character.buffs())result.add(buff.getClass().getName());return result;
    }
    private static void set(Object object,String field,Object value) throws Exception {
        for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass()) {
            try { Field f=type.getDeclaredField(field); f.setAccessible(true); f.set(object,value); return; }
            catch(NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(field);
    }
    private static Object get(Object object,String field) throws Exception {
        for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass()) {
            try { Field f=type.getDeclaredField(field); f.setAccessible(true); return f.get(object); }
            catch(NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(field);
    }
    private static void write(Path path,Map<String,Object> value)throws IOException {
        Files.writeString(path,JsonCodec.encode(value)+"\n",StandardCharsets.UTF_8);
    }
    private static Map<String,Object> map(Object... pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    private static final class LogOutput extends OutputStream {
        final MachineSession session; final String channel; final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        LogOutput(MachineSession session,String channel){this.session=session;this.channel=channel;}
        @Override public synchronized void write(int value){bytes.write(value);if(value=='\n')flush();}
        @Override public synchronized void flush(){if(bytes.size()>0){session.recordLog(channel,bytes.toString(StandardCharsets.UTF_8));bytes.reset();}}
    }
}
