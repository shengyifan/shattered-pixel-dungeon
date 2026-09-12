/*
 * Source coverage tool only. Uses javac attribution to distinguish String composition
 * from numeric addition. It parses/analyzes current source and never generates game
 * classes, initializes game objects, opens a profile, or starts a game loop.
 */
import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.element.*;
import javax.tools.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class TextCompositionInventory {
  static Trees trees;
  static CompilationUnitTree unit;
  static String source;
  static Path root;
  static int catalogConstructors;
  static String enc(String value) { return Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
  static long start(Tree node) { return trees.getSourcePositions().getStartPosition(unit, node); }
  static long end(Tree node) { return trees.getSourcePositions().getEndPosition(unit, node); }
  static String type(TreePath path) { TypeMirror value=trees.getTypeMirror(path); return value == null ? "" : value.toString(); }
  static void out(TreePath path, Tree left, Tree right) {
    Tree node=path.getLeaf(); long a=start(node), b=end(node); if(a<0||b<0)return;
    List<String> contexts=new ArrayList<>(); String method="", owner="", category=""; Tree child=node;
    for(TreePath p=path.getParentPath();p!=null;p=p.getParentPath()) {
      Tree t=p.getLeaf();
      if(t instanceof MethodInvocationTree) {
        MethodInvocationTree invocation=(MethodInvocationTree)t;
        int arg=invocation.getArguments().indexOf(child);
        contexts.add(invocation.getMethodSelect().toString()+"@"+arg);
        Element element=trees.getElement(p);
        if(element instanceof ExecutableElement) {
          ExecutableElement executable=(ExecutableElement)element;
          if(executable.getEnclosingElement().toString().equals("com.shatteredpixel.shatteredpixeldungeon.messages.Messages") && executable.getSimpleName().contentEquals("get")) {
            int key=executable.getParameters().get(0).asType().toString().equals("java.lang.String")?0:1;
            if(arg==key)category="resource_key";
          }
        }
      }
      if(t instanceof MethodTree && method.isEmpty())method=((MethodTree)t).getName().toString();
      if(t instanceof ClassTree && owner.isEmpty())owner=((ClassTree)t).getSimpleName().toString();
      child=t;
    }
    String file=root.relativize(Paths.get(unit.getSourceFile().toUri())).toString();
    System.out.println(String.join("\t", file, ""+unit.getLineMap().getLineNumber(a), node.getKind().name(), ""+a,""+b,
      left==null?"-1":""+start(left),left==null?"-1":""+end(left),right==null?"-1":""+start(right),right==null?"-1":""+end(right),
      left==null?"":type(new TreePath(path,left)),right==null?"":type(new TreePath(path,right)),enc(method),enc(owner),enc(String.join(" > ",contexts)),enc(source.substring((int)a,(int)b)),path.getParentPath().getLeaf().getKind().name(),category,left==null?"":left.getKind().name(),right==null?"":right.getKind().name(),enc(node.toString())));
  }

  static boolean catalogText(TreePath path) {
    Tree node = path.getLeaf();
    if (node instanceof LiteralTree) return true;
    Element symbol = trees.getElement(path);
    if (symbol instanceof VariableElement && ((VariableElement) symbol).getConstantValue() instanceof String) return true;
    if (!(node instanceof MethodInvocationTree) || !(symbol instanceof ExecutableElement)) return false;
    MethodInvocationTree call = (MethodInvocationTree) node;
    String owner = symbol.getEnclosingElement().toString(), name = symbol.getSimpleName().toString();
    if (owner.equals("com.shatteredpixel.shatteredpixeldungeon.messages.Messages") && name.equals("get")) {
      // Current catalog resource labels have no dynamic formatting arguments.
      ExecutableElement method = (ExecutableElement) symbol;
      int keyIndex = method.getParameters().get(0).asType().toString().equals("java.lang.String") ? 0 : 1;
      if (call.getArguments().size() != keyIndex + 1) return false;
      Tree key = call.getArguments().get(keyIndex);
      Element keySymbol = trees.getElement(new TreePath(path, key));
      return key instanceof LiteralTree || keySymbol instanceof VariableElement
          && ((VariableElement) keySymbol).getConstantValue() instanceof String;
    }
    if (!(call.getMethodSelect() instanceof MemberSelectTree) || !call.getArguments().isEmpty()) return false;
    ExpressionTree receiver = ((MemberSelectTree) call.getMethodSelect()).getExpression();
    TreePath receiverPath = new TreePath(new TreePath(path, call.getMethodSelect()), receiver);
    if (name.equals("title")) {
      Element constant = trees.getElement(receiverPath);
      return constant != null && constant.getKind() == ElementKind.ENUM_CONSTANT
          && owner.startsWith("com.shatteredpixel.shatteredpixeldungeon.actors.hero.");
    }
    return name.equals("trueName") && owner.startsWith("com.shatteredpixel.shatteredpixeldungeon.items.")
        && receiver instanceof NewClassTree && ((NewClassTree) receiver).getArguments().isEmpty();
  }


  static boolean fixedCatalogValue(TreePath path) {
    if (path.getLeaf() instanceof LiteralTree) return true;
    Element symbol = trees.getElement(path);
    if (symbol instanceof VariableElement && (((VariableElement) symbol).getConstantValue() != null
        || symbol.getKind() == ElementKind.ENUM_CONSTANT)) return true;
    return path.getLeaf() instanceof NewClassTree && catalogItem(path);
  }

  static boolean catalogItem(TreePath path) {
    Tree node = path.getLeaf();
    List<? extends ExpressionTree> arguments;
    if (node instanceof NewClassTree) {
      arguments = ((NewClassTree) node).getArguments();
    } else if (node instanceof MethodInvocationTree) {
      MethodInvocationTree call = (MethodInvocationTree) node;
      if (!(call.getMethodSelect() instanceof MemberSelectTree)) return false;
      MemberSelectTree method = (MemberSelectTree) call.getMethodSelect();
      if (!Arrays.asList("upgrade", "level", "identify", "enchant", "inscribe", "quantity")
          .contains(method.getIdentifier().toString())) return false;
      if (!catalogItem(new TreePath(new TreePath(path, method), method.getExpression()))) return false;
      arguments = call.getArguments();
    } else return false;
    for (Tree argument : arguments) if (!fixedCatalogValue(new TreePath(path, argument))) return false;
    return true;
  }

  static void verifyCatalogConstructor(TreePath path, NewClassTree node) {
    String target = type(new TreePath(path, node.getIdentifier()));
    if (!target.equals("com.shatteredpixel.shatteredpixeldungeon.ui.changelist.ChangeInfo")
        && !target.equals("com.shatteredpixel.shatteredpixeldungeon.ui.changelist.ChangeButton")) return;
    String file = root.relativize(Paths.get(unit.getSourceFile().toUri())).toString();
    String prefix = "core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/";
    boolean catalogFile = file.startsWith(prefix + "ui/changelist/v") && file.endsWith("_Changes.java");
    if (!catalogFile && !file.equals(prefix + "scenes/ChangesScene.java")) {
      throw new IllegalStateException("Unreviewed caller of the trusted change catalog: " + file);
    }
    catalogConstructors++;
    Element constructor = trees.getElement(path);
    if (constructor instanceof ExecutableElement && !node.getArguments().isEmpty()) {
      List<? extends VariableElement> parameters = ((ExecutableElement) constructor).getParameters();
      if (!parameters.isEmpty() && parameters.get(0).asType().toString()
          .equals("com.shatteredpixel.shatteredpixeldungeon.items.Item")
          && !catalogItem(new TreePath(path, node.getArguments().get(0)))) {
        throw new IllegalStateException("Trusted change catalog item must be constructed locally at " + file + ":"
            + unit.getLineMap().getLineNumber(start(node)));
      }
    }
    for (Tree argument : node.getArguments()) {
      TreePath argumentPath = new TreePath(path, argument);
      String valueType = type(argumentPath);
      if ((valueType.equals("java.lang.String") || valueType.equals("java.lang.String[]"))
          && !catalogText(argumentPath)) {
        throw new IllegalStateException("Unreviewed text input to the trusted change catalog at " + file + ":"
            + unit.getLineMap().getLineNumber(start(argument)) + ": " + argument);
      }
    }
  }

  public static void main(String[] args)throws Exception {
    root=Paths.get(args[0]).toAbsolutePath();
    JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
    try(StandardJavaFileManager files=compiler.getStandardFileManager(diagnostics,null,java.nio.charset.StandardCharsets.UTF_8)) {
      List<Path> sources;
      try(Stream<Path> paths=Files.walk(root.resolve("core/src/main/java"))) {sources=paths.filter(p->p.toString().endsWith(".java")).sorted().collect(Collectors.toList());}
      String sourcepath=String.join(java.io.File.pathSeparator,root.resolve("core/src/main/java").toString(),root.resolve("SPD-classes/src/main/java").toString(),root.resolve("services/src/main/java").toString());
      List<String> options=Arrays.asList("-proc:none","-implicit:none","-encoding","UTF-8","-Xlint:none","-Xprefer:source","-classpath",args[1],"-sourcepath",sourcepath);
      JavacTask task=(JavacTask)compiler.getTask(null,files,diagnostics,options,null,files.getJavaFileObjectsFromPaths(sources));
      List<CompilationUnitTree> units=new ArrayList<>();task.parse().forEach(units::add);task.analyze();trees=Trees.instance(task);
      long errors=diagnostics.getDiagnostics().stream().filter(d->d.getKind()==Diagnostic.Kind.ERROR).count();
      if(errors>0){for(Diagnostic<?> d:diagnostics.getDiagnostics())if(d.getKind()==Diagnostic.Kind.ERROR)System.err.println(d);throw new IllegalStateException("Cannot inventory unresolved source types: "+errors);}
      for(CompilationUnitTree current:units){unit=current;source=Files.readString(Paths.get(unit.getSourceFile().toUri()));
        new TreePathScanner<Void,Void>() {
          @Override public Void visitBinary(BinaryTree node,Void ignored){if(node.getKind()==Tree.Kind.PLUS&&type(getCurrentPath()).equals("java.lang.String"))out(getCurrentPath(),node.getLeftOperand(),node.getRightOperand());return super.visitBinary(node,ignored);}
          @Override public Void visitCompoundAssignment(CompoundAssignmentTree node,Void ignored){if(node.getKind()==Tree.Kind.PLUS_ASSIGNMENT&&type(getCurrentPath()).equals("java.lang.String"))out(getCurrentPath(),node.getVariable(),node.getExpression());return super.visitCompoundAssignment(node,ignored);}
          @Override public Void visitNewClass(NewClassTree node,Void ignored){verifyCatalogConstructor(getCurrentPath(), node);String t=type(getCurrentPath());if(t.equals("java.lang.StringBuilder")||t.equals("java.lang.StringBuffer"))out(getCurrentPath(),null,null);return super.visitNewClass(node,ignored);}
          @Override public Void visitMethodInvocation(MethodInvocationTree node,Void ignored) {
            Element element=trees.getElement(getCurrentPath());
            if(element instanceof ExecutableElement
                && element.getEnclosingElement().toString().equals("com.shatteredpixel.shatteredpixeldungeon.utils.DungeonSeed")
                && element.getSimpleName().contentEquals("convertToCode")) out(getCurrentPath(),null,null);
            if(element instanceof ExecutableElement && element.getEnclosingElement().toString().equals("java.lang.String") && type(getCurrentPath()).equals("java.lang.String")) {
              String name=element.getSimpleName().toString();
              if(Arrays.asList("concat","substring","replace","replaceFirst","replaceAll","toUpperCase","toLowerCase","trim","strip","format","formatted","join").contains(name))out(getCurrentPath(),null,null);
            }
            return super.visitMethodInvocation(node,ignored);
          }
        }.scan(unit,null);
      }
      System.err.println("CATALOG_CONSTRUCTORS\t" + catalogConstructors);
      System.err.println("SOURCE_FILES\t" + units.size());
    }
  }
}
