/* Source-only combat-visual inventory. javac attribution does not initialize game classes. */
import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.tools.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.*;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;

public final class CombatVisualInventory {
    private static final Set<String> OBSERVATION_TYPES=new HashSet<>(Arrays.asList(
            "com.shatteredpixel.shatteredpixeldungeon.effects.VisualCueCollector",
            "com.shatteredpixel.shatteredpixeldungeon.effects.VisualCueProjection",
            "com.shatteredpixel.shatteredpixeldungeon.effects.GameplayVisualKinds",
            "com.shatteredpixel.shatteredpixeldungeon.effects.GameplayBurst",
            "com.shatteredpixel.shatteredpixeldungeon.effects.SpectralWallCue",
            "com.shatteredpixel.shatteredpixeldungeon.effects.particles.SpectralWallParticle",
            "com.shatteredpixel.shatteredpixeldungeon.effects.ScreenEffectCollector",
            "com.shatteredpixel.shatteredpixeldungeon.effects.CellParticleCue",
            "com.shatteredpixel.shatteredpixeldungeon.effects.ItemStatusEmitter",
            "com.shatteredpixel.shatteredpixeldungeon.effects.TerrainVisualCue",
            "com.shatteredpixel.shatteredpixeldungeon.effects.ParticleMotionCue",
            "com.shatteredpixel.shatteredpixeldungeon.effects.RadialVisualCue",
            "com.shatteredpixel.shatteredpixeldungeon.ui.RenderedAppearance",
            "com.shatteredpixel.shatteredpixeldungeon.ui.RenderedStatus",
            "com.shatteredpixel.shatteredpixeldungeon.ui.GameplayStatus",
            "com.shatteredpixel.shatteredpixeldungeon.ui.GameplayIcons",
            "com.shatteredpixel.shatteredpixeldungeon.ui.GameplayGlow",
            "com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock",
            "com.shatteredpixel.shatteredpixeldungeon.control.game.UiBridge",
            "com.shatteredpixel.shatteredpixeldungeon.control.game.GameController",
            "com.shatteredpixel.shatteredpixeldungeon.control.game.GameSnapshotter",
            "com.shatteredpixel.shatteredpixeldungeon.control.game.PlayerObservation",
            "com.shatteredpixel.shatteredpixeldungeon.control.game.GameplayObservation",
            "com.shatteredpixel.shatteredpixeldungeon.control.game.GameplayEvidence",
            "com.shatteredpixel.shatteredpixeldungeon.control.game.PublicEnglishProjection",
            "com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene.Fader",
            "com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene.PixelCamera",
            "com.watabou.noosa.VisualCue", "com.watabou.noosa.VisualMetric",
            "com.watabou.noosa.ScreenEffect", "com.watabou.noosa.RuntimeObserver",
            "com.watabou.noosa.Game", "com.watabou.noosa.Gizmo", "com.watabou.noosa.Group", "com.watabou.noosa.Camera",
            "com.watabou.noosa.NoosaScript", "com.watabou.noosa.NoosaScriptNoLighting"));
    private static final Set<String> SEMANTIC_CATALOGS=new HashSet<>(Arrays.asList(
            "com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator",
            "com.shatteredpixel.shatteredpixeldungeon.ui.HeroIcon",
            "com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet",
            "com.shatteredpixel.shatteredpixeldungeon.Assets.Sprites",
            "com.shatteredpixel.shatteredpixeldungeon.effects.GameplayVisualKinds"));
    /* Include field initializers/constants used by source adapters, not only method bodies. */
    private static final Set<String> SEMANTIC_BOUNDARIES=new HashSet<>(Arrays.asList(
            "com.shatteredpixel.shatteredpixeldungeon.control.game.GameController",
            "com.shatteredpixel.shatteredpixeldungeon.control.game.UiBridge",
            "com.shatteredpixel.shatteredpixeldungeon.control.game.GameplayObservation",
            "com.shatteredpixel.shatteredpixeldungeon.control.game.GameplayEvidence",
            "com.shatteredpixel.shatteredpixeldungeon.control.game.PlayerObservation",
            "com.shatteredpixel.shatteredpixeldungeon.control.game.GameSnapshotter",
            "com.shatteredpixel.shatteredpixeldungeon.control.game.PublicEnglishProjection",
            "com.shatteredpixel.shatteredpixeldungeon.effects.VisualCueCollector",
            "com.shatteredpixel.shatteredpixeldungeon.effects.GameplayBurst",
            "com.shatteredpixel.shatteredpixeldungeon.effects.SpectralWallCue",
            "com.shatteredpixel.shatteredpixeldungeon.effects.particles.SpectralWallParticle",
            "com.shatteredpixel.shatteredpixeldungeon.effects.Flare",
            "com.watabou.noosa.RuntimeObserver",
            "com.shatteredpixel.shatteredpixeldungeon.effects.CellParticleCue",
            "com.shatteredpixel.shatteredpixeldungeon.effects.ParticleMotionCue",
            "com.shatteredpixel.shatteredpixeldungeon.effects.TerrainVisualCue",
            "com.shatteredpixel.shatteredpixeldungeon.effects.ItemStatusEmitter",
            "com.shatteredpixel.shatteredpixeldungeon.ui.GameplayIcons",
            "com.shatteredpixel.shatteredpixeldungeon.ui.GameplayGlow",
            "com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock",
            "com.shatteredpixel.shatteredpixeldungeon.effects.FloatingText",
            "com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite",
            "com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff",
            "com.shatteredpixel.shatteredpixeldungeon.items.BrokenSeal.WarriorShield",
            "com.shatteredpixel.shatteredpixeldungeon.items.Item",
            "com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.MissileWeapon",
            "com.shatteredpixel.shatteredpixeldungeon.ui.ItemSlot"));
    private static final Set<String> STATE_FIELDS=new HashSet<>(Arrays.asList(
            "visible","on","scale","angle","curAnim","flipHorizontal","flipVertical",
            "rm","gm","bm","am","ra","ga","ba","aa","frame","x","y","width","height"));
    private static final Set<String> PRODUCERS=new HashSet<>(Arrays.asList(
            "effect","effectOverFog","addSprite","addToBack","status","ripple","emitter","centerEmitter",
            "bottomEmitter","cellEmitter","burst","pour","start","play","frame","texture","turnTo",
            "addState","removeState","hardlight","tint","color","alpha","showStatus","showStatusWithIcon",
            "showOnCell","markVisual","resetObservation","observeCellVisualDraw","observeLinkedVisualDraw","observeTerrainVisual",
            "shake","flash","fadeIn","animatedTextColor","animatedColor"));
    private static final Set<String> GAMEPLAY_BURST_PRODUCERS=new HashSet<>(Arrays.asList(
            "burst","start","burstForCharacter","startForCharacter","spriteBurst","splash"));
    private static final Set<String> VISUAL_MUTATIONS=new HashSet<>(Arrays.asList(
            "add","addToBack","front","back","bringToFront","sendToBack","erase","remove","clear",
            "draw","update","reset","revive","kill","killAndErase","destroy","fx","frame","texture",
            "play","turnTo","addState","removeState","hardlight","tint","color","alpha","brightness",
            "lightness","invert","resetColor","pos","point","setPos","setRect","setSize","scale",
            "text","zoom","flipHorizontal","place","move","jump","attack","zap","charge","crumple",
            "showStatus","showStatusWithIcon","show","showOnCell","burst","pour","start","emit","pourExplode"));
    private static final Set<String> CALLBACKS=new HashSet<>(Arrays.asList(
            "draw","update","fx","emit","reset","revive","kill","destroy","link","createChildren","layout",
            "icon","tintIcon","iconFadePercent","iconTextDisplay","tileDesc","tileName","image","renderedStateCue",
            "observeGameplayVisuals","gameplayStatus","gameplayIntentStatus","gameplaySubject",
            "auraFact","lootFact","iconTextDisplayInfo","quantityStatus"));
    private static Trees trees;
    private static Types types;
    private static Elements elements;
    private static CompilationUnitTree unit;
    private static String source;
    private static Path root;
    private static final List<Map<String,Object>> records=new ArrayList<>();
    private static final Map<String,Integer> occurrences=new HashMap<>();

    /* Keep javac attribution and source-position snippets on the same immutable bytes. */
    private static final class SnapshotSourceFile extends SimpleJavaFileObject {
        private final String contents;
        SnapshotSourceFile(Path path)throws java.io.IOException {
            super(path.toUri(),JavaFileObject.Kind.SOURCE);
            contents=Files.readString(path,StandardCharsets.UTF_8);
        }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors){return contents;}
    }

    private static boolean subtype(TypeMirror mirror,String target) {
        TypeElement parent=elements.getTypeElement(target);
        if(mirror==null||parent==null||mirror.getKind()==TypeKind.ERROR||mirror.getKind()==TypeKind.NONE)return false;
        try{return types.isSubtype(types.erasure(mirror),types.erasure(parent.asType()));}
        catch(IllegalArgumentException ignored){return false;}
    }
    private static boolean visual(TypeMirror type) {return subtype(type,"com.watabou.noosa.Gizmo")||subtype(type,"com.watabou.noosa.Camera");}
    private static String digest(String value)throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b&255));return out.toString();
    }
    private static String snippet(Tree node) {
        long start=trees.getSourcePositions().getStartPosition(unit,node),end=trees.getSourcePositions().getEndPosition(unit,node);
        if(start<0||end<start)return node.toString();
        if(end>source.length())throw new IllegalStateException("Source positions exceed the parsed snapshot for "+unit.getSourceFile().getName());
        return source.substring((int)start,(int)end);
    }
    private static void record(TreePath path,String kind,String sink) {
        Tree node=path.getLeaf();
        long start=trees.getSourcePositions().getStartPosition(unit,node);
        if(start<0)return;
        String method="<initializer>",owner="",body="";
        for(TreePath p=path;p!=null;p=p.getParentPath()) {
            if(p.getLeaf() instanceof MethodTree&&method.equals("<initializer>")){
                method=((MethodTree)p.getLeaf()).getName().toString();body=snippet(p.getLeaf());
            }
            if(owner.isEmpty()&&p.getLeaf() instanceof ClassTree){
                Element e=trees.getElement(p);owner=e==null?((ClassTree)p.getLeaf()).getSimpleName().toString():e.toString();
            }
        }
        String file=root.relativize(Paths.get(unit.getSourceFile().toUri())).toString();
        String expression=snippet(node).replaceAll("\\s+"," ").trim();
        if(node instanceof ClassTree)expression=((ClassTree)node).getKind()+" "+owner;
        if(kind.equals("semantic_catalog")||kind.equals("source_boundary"))body=snippet(node);
        if(node instanceof MethodTree){MethodTree m=(MethodTree)node;expression=m.getReturnType()+" "+m.getName()+m.getParameters();}
        try{
            String identity=digest(file+"\n"+owner+"\n"+method+"\n"+kind+"\n"+sink+"\n"+expression);
            int occurrence=occurrences.merge(identity,1,Integer::sum);
            Map<String,Object> value=new LinkedHashMap<>();
            value.put("id",identity+":"+occurrence);value.put("file",file);
            value.put("line",unit.getLineMap().getLineNumber(start));value.put("owner",owner);value.put("method",method);
            value.put("kind",kind);value.put("sink",sink);value.put("expression",expression);
            value.put("body_digest",digest(body.replaceAll("\\s+"," ").trim()));
            records.add(value);
        }catch(Exception error){throw new IllegalStateException(error);}
    }
    public static void main(String[] args)throws Exception {
        root=Paths.get(args[0]).toAbsolutePath();
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        try(StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,null,StandardCharsets.UTF_8)){
            List<Path> paths=new ArrayList<>();
            for(String tree:Arrays.asList("core","SPD-classes","game-control"))try(Stream<Path> stream=Files.walk(root.resolve(tree+"/src/main/java"))){
                paths.addAll(stream.filter(p->p.toString().endsWith(".java")).sorted().collect(Collectors.toList()));
            }
            for(int i=2;i<args.length;i++)paths.add(Paths.get(args[i]));
            String sourcepath=Stream.of("core","SPD-classes","services","game-control").map(p->root.resolve(p+"/src/main/java").toString()).collect(Collectors.joining(java.io.File.pathSeparator));
            List<String> options=Arrays.asList("-proc:none","-implicit:none","-encoding","UTF-8","-Xlint:none","-Xprefer:source","-classpath",args[1],"-sourcepath",sourcepath);
            List<JavaFileObject> snapshots=new ArrayList<>(paths.size());
            for(Path path:paths)snapshots.add(new SnapshotSourceFile(path));
            JavacTask task=(JavacTask)compiler.getTask(null,manager,diagnostics,options,null,snapshots);
            List<CompilationUnitTree> units=new ArrayList<>();task.parse().forEach(units::add);task.analyze();
            trees=Trees.instance(task);types=task.getTypes();elements=task.getElements();
            long errors=diagnostics.getDiagnostics().stream().filter(d->d.getKind()==Diagnostic.Kind.ERROR).count();
            if(errors>0){for(Diagnostic<?> d:diagnostics.getDiagnostics())if(d.getKind()==Diagnostic.Kind.ERROR)System.err.println(d);throw new IllegalStateException("Unresolved visual source types: "+errors);}
            for(CompilationUnitTree current:units){unit=current;source=unit.getSourceFile().getCharContent(true).toString();
                new TreePathScanner<Void,Void>(){
                    @Override public Void visitClass(ClassTree node,Void ignored){
                        TypeMirror type=trees.getTypeMirror(getCurrentPath());
                        if(visual(type))record(getCurrentPath(),"visual_type",type.toString());
                        Element symbol=trees.getElement(getCurrentPath());
                        if(symbol!=null&&SEMANTIC_CATALOGS.contains(symbol.toString()))
                            record(getCurrentPath(),"semantic_catalog",symbol.toString());
                        if(symbol!=null&&SEMANTIC_BOUNDARIES.contains(symbol.toString()))
                            record(getCurrentPath(),"source_boundary",symbol.toString());
                        return super.visitClass(node,ignored);
                    }
                    @Override public Void visitNewClass(NewClassTree node,Void ignored){
                        TypeMirror type=trees.getTypeMirror(getCurrentPath());
                        if(visual(type)||subtype(type,"com.watabou.noosa.particles.Emitter.Factory"))record(getCurrentPath(),"construct",type.toString());
                        return super.visitNewClass(node,ignored);
                    }
                    @Override public Void visitMethod(MethodTree node,Void ignored){
                        Element symbol=trees.getElement(getCurrentPath());
                        if(symbol!=null){
                            String owner=symbol.getEnclosingElement().toString();
                            String method=node.getName().toString();
                            boolean displayAccessor=visual(symbol.getEnclosingElement().asType())
                                    &&(method.startsWith("rendered")||method.startsWith("displayed")||method.equals("intentStatus"));
                            if(displayAccessor||OBSERVATION_TYPES.stream().anyMatch(type->owner.equals(type)||owner.startsWith(type+"."))
                                    ||owner.equals("com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene")
                                            &&method.equals("observeSpectralWall")
                                    ||(owner.equals("com.shatteredpixel.shatteredpixeldungeon.items.Item")
                                            ||owner.equals("com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.MissileWeapon"))
                                            &&method.equals("status"))
                                record(getCurrentPath(),"observation_boundary",owner+"."+node.getName());
                        }
                        if(CALLBACKS.contains(node.getName().toString())&&symbol!=null){
                            TypeMirror owner=symbol.getEnclosingElement().asType();
                            if(visual(owner)||subtype(owner,"com.watabou.noosa.particles.Emitter.Factory")
                                    ||subtype(owner,"com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff")
                                    ||subtype(owner,"com.shatteredpixel.shatteredpixeldungeon.tiles.CustomTilemap")
                                    ||subtype(owner,"com.shatteredpixel.shatteredpixeldungeon.ui.GameplayStatus"))
                                record(getCurrentPath(),"callback",symbol.getEnclosingElement()+"."+node.getName());
                        }
                        return super.visitMethod(node,ignored);
                    }
                    @Override public Void visitMethodInvocation(MethodInvocationTree node,Void ignored){
                        Element symbol=trees.getElement(getCurrentPath());
                        if(symbol instanceof ExecutableElement){
                            String name=symbol.getSimpleName().toString(),owner=symbol.getEnclosingElement().toString();
                            boolean rendered=visual(symbol.getEnclosingElement().asType());
                            if(rendered&&VISUAL_MUTATIONS.contains(name)
                                    ||PRODUCERS.contains(name)&&(owner.startsWith("com.shatteredpixel.")||owner.startsWith("com.watabou."))
                                    ||owner.equals("com.shatteredpixel.shatteredpixeldungeon.effects.GameplayBurst")
                                            &&GAMEPLAY_BURST_PRODUCERS.contains(name))
                                record(getCurrentPath(),"call",owner+"."+name);
                            if(node.getMethodSelect() instanceof MemberSelectTree){
                                Tree receiver=((MemberSelectTree)node.getMethodSelect()).getExpression();
                                Element field=trees.getElement(new TreePath(new TreePath(getCurrentPath(),node.getMethodSelect()),receiver));
                                if(field instanceof VariableElement&&field.getKind()==ElementKind.FIELD
                                        &&STATE_FIELDS.contains(field.getSimpleName().toString())&&visual(field.getEnclosingElement().asType()))
                                    record(getCurrentPath(),"appearance_mutator",field.getEnclosingElement()+"."+field.getSimpleName()+"."+name);
                            }
                        }
                        return super.visitMethodInvocation(node,ignored);
                    }
                    private void assignment(TreePath path,Tree variable){
                        Element symbol=trees.getElement(new TreePath(path,variable));
                        if(symbol instanceof VariableElement&&symbol.getKind()==ElementKind.FIELD
                                &&STATE_FIELDS.contains(symbol.getSimpleName().toString())&&visual(symbol.getEnclosingElement().asType()))
                            record(path,"appearance_write",symbol.getEnclosingElement()+"."+symbol.getSimpleName());
                    }
                    @Override public Void visitAssignment(AssignmentTree node,Void ignored){assignment(getCurrentPath(),node.getVariable());return super.visitAssignment(node,ignored);}
                    @Override public Void visitCompoundAssignment(CompoundAssignmentTree node,Void ignored){assignment(getCurrentPath(),node.getVariable());return super.visitCompoundAssignment(node,ignored);}
                }.scan(unit,null);
            }
            Map<String,Object> out=new LinkedHashMap<>();out.put("format","combat_visual_source_inventory_v1");
            out.put("source_files",paths.size());out.put("runtime_verified_by_inventory",false);out.put("entries",records);
            System.out.println(JsonCodec.encode(out));
        }
    }
}
