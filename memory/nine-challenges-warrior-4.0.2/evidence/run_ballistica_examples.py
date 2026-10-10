#!/usr/bin/env python3
"""Compile unchanged repository Ballistica.java against minimal synthetic stubs.

No game process, save, audit, network, or gameplay API is used. Stubs supply only
the three public collision booleans, synthetic character occupancy, map bounds,
and exception reporting. This verifies native geometry, not a real wand zap.
Run from any directory; JSON evidence is printed to stdout. Requires java/javac.
"""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / "core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/mechanics/Ballistica.java"
PACKAGE = "com/shatteredpixel/shatteredpixeldungeon"

STUBS = {
    f"{PACKAGE}/Dungeon.java": """
package com.shatteredpixel.shatteredpixeldungeon;
public class Dungeon {
  public static Level level;
  public static class Level {
    private final int width, height;
    public boolean[] passable, avoid, solid;
    public Level(int w, int h) {
      width=w; height=h;
      passable=new boolean[w*h]; avoid=new boolean[w*h]; solid=new boolean[w*h];
      java.util.Arrays.fill(passable, true);
    }
    public int width() { return width; }
    public boolean insideMap(int tile) {
      return !((tile < width || tile >= width*height-width)
          || (tile%width == 0 || tile%width == width-1));
    }
  }
}
""",
    f"{PACKAGE}/ShatteredPixelDungeon.java": """
package com.shatteredpixel.shatteredpixeldungeon;
public class ShatteredPixelDungeon {
  public static void reportException(Exception e) { throw new RuntimeException(e); }
}
""",
    f"{PACKAGE}/actors/Actor.java": """
package com.shatteredpixel.shatteredpixeldungeon.actors;
public class Actor {
  public static java.util.Set<Integer> occupied=new java.util.HashSet<>();
  public static Object findChar(int cell) { return occupied.contains(cell) ? Boolean.TRUE : null; }
}
""",
    "BallisticaExamples.java": """
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.mechanics.Ballistica;
public class BallisticaExamples {
  public static void main(String[] args) {
    int w=Integer.parseInt(args[0]), h=Integer.parseInt(args[1]);
    Dungeon.level=new Dungeon.Level(w,h);
    for(String entry:args[5].split(";")) {
      if(entry.isEmpty()) continue;
      String[] fields=entry.split(","); int cell=Integer.parseInt(fields[0]);
      Dungeon.level.passable[cell]=fields[1].equals("1");
      Dungeon.level.avoid[cell]=fields[2].equals("1");
      Dungeon.level.solid[cell]=fields[3].equals("1");
    }
    for(String cell:args[6].split(",")) if(!cell.isEmpty()) Actor.occupied.add(Integer.parseInt(cell));
    Ballistica ray=new Ballistica(Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4]));
    System.out.println("{\\\"collision\\\":"+ray.collisionPos+",\\\"dist\\\":"+ray.dist+",\\\"path\\\":"+ray.path+"}");
  }
}
""",
}

W, H = 12, 10
def c(x, y):
    return y*W+x

def case(name, source, target, flags, expected, terrain=(), characters=(), prefix=None):
    return dict(name=name, source=c(*source), target=c(*target), flags=flags,
                terrain=[(c(*xy), *bits) for xy, bits in terrain],
                characters=[c(*xy) for xy in characters], expected_collision=c(*expected),
                expected_prefix=None if prefix is None else [c(*xy) for xy in prefix])

WALL=(False,False,True)
DOOR=(True,False,True)
PIT=(False,True,False)
CASES=[
    case("direct_aim_blocked", (2,2),(5,3),6,(3,2), [((4,3),WALL)],[(5,3)],[(2,2),(3,2),(4,3),(5,3)]),
    case("farther_aim_bypasses_wall", (2,2),(7,3),6,(5,3), [((4,3),WALL)],[(5,3)],[(2,2),(3,2),(4,2),(5,3)]),
    case("forward_tie_clear", (2,2),(6,4),6,(6,4), [((5,3),WALL)],[(6,4)],[(2,2),(3,3),(4,3),(5,4),(6,4)]),
    case("reverse_tie_blocked", (6,4),(2,2),6,(6,4), [((5,3),WALL)],[(2,2)],[(6,4),(5,3),(4,3),(3,2),(2,2)]),
    case("magic_bolt_continues_beyond_click", (2,2),(3,2),6,(5,2), characters=[(5,2)]),
    case("projectile_stops_at_clicked_cell", (2,2),(3,2),7,(3,2), characters=[(5,2)]),
    case("first_character_blocks", (2,2),(8,2),6,(4,2), characters=[(4,2),(6,2)]),
    case("solid_beam_ignores_characters", (2,2),(8,2),4,(7,2), [((8,2),WALL)],[(4,2),(6,2)]),
    case("hard_wall_stops_at_previous_cell", (2,2),(8,2),6,(4,2), [((5,2),WALL)]),
    case("closed_door_stops_on_door", (2,2),(8,2),6,(5,2), [((5,2),DOOR)]),
    case("ignore_soft_solid_passes_door", (2,2),(8,2),14,(7,2), [((5,2),DOOR)],[(7,2)]),
    case("ignore_soft_solid_keeps_hard_wall", (2,2),(8,2),14,(4,2), [((5,2),WALL)],[(7,2)]),
    case("solid_cell_with_character_stops_on_cell", (2,2),(8,2),6,(5,2), [((5,2),WALL)],[(5,2)]),
    case("pit_avoid_is_not_blocking", (2,2),(8,2),6,(7,2), [((5,2),PIT)],[(7,2)]),
    case("wont_stop_ignores_wall_and_characters", (2,2),(8,2),0,(10,2), [((5,2),WALL)],[(7,2)]),
    case("inside_map_boundary_fallback", (2,2),(8,2),6,(10,2)),
    case("same_source_magic_geometry_not_legal_wand_aim", (2,2),(2,2),6,(1,1), prefix=[(2,2),(1,1)]),
    case("same_source_projectile_geometry", (2,2),(2,2),7,(2,2), prefix=[(2,2),(1,1)]),
    case("diagonal_corner_does_not_probe_side_cells", (2,2),(3,3),6,(3,3), [((3,2),WALL),((2,3),WALL)],[(3,3)]),
    case("blast_direct_aim_next_cell_southeast", (2,2),(3,3),7,(3,3), characters=[(3,3)],prefix=[(2,2),(3,3),(4,4)]),
    case("blast_offset_aim_next_cell_east", (2,2),(4,3),7,(3,3), characters=[(3,3)],prefix=[(2,2),(3,3),(4,3)]),
]

def main():
    outcomes=[]
    with tempfile.TemporaryDirectory(prefix="spd-ballistica-synthetic-") as temp:
        root=Path(temp)
        for rel, content in STUBS.items():
            p=root/rel; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(content)
        native=root/PACKAGE/"mechanics/Ballistica.java"
        native.parent.mkdir(parents=True); shutil.copyfile(SOURCE,native)
        subprocess.run(["javac","-d",str(root/"classes"),*[str(p) for p in sorted(root.rglob("*.java"))]],check=True,capture_output=True,text=True)
        for test in CASES:
            terrain=";".join(",".join(str(int(v)) for v in row) for row in test["terrain"])
            args=[str(W),str(H),str(test["source"]),str(test["target"]),str(test["flags"]),terrain,",".join(map(str,test["characters"]))]
            result=subprocess.run(["java","-cp",str(root/"classes"),"BallisticaExamples",*args],check=True,capture_output=True,text=True)
            actual=json.loads(result.stdout)
            assert actual["collision"]==test["expected_collision"],(test,actual)
            prefix=test["expected_prefix"]
            assert prefix is None or actual["path"][:len(prefix)]==prefix,(test,actual)
            actual["travel_to_collision"]=actual["path"][:actual["dist"]+1]
            actual["path_xy"]=[[q%W,q//W] for q in actual["path"]]
            outcomes.append({**test,"actual":actual,"passed":True})
    print(json.dumps({
        "evidence_kind":"isolated_native_Ballistica_synthetic_geometry",
        "scope":"Native Ballistica only; synthetic collision/occupancy stubs, no full game or live play proof.",
        "source":str(SOURCE.relative_to(ROOT)),
        "source_sha256":hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
        "repository_head":subprocess.run(["git","rev-parse","HEAD"],cwd=ROOT,capture_output=True,text=True,check=True).stdout.strip(),
        "runner_sha256":hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "inside_map_source":"core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/Level.java:1564",
        "java_version":subprocess.run(["java","-version"],capture_output=True,text=True,check=True).stderr.splitlines()[0],
        "map":{"width":W,"height":H,"default_passable":True,"default_avoid":False,"default_solid":False},
        "case_count":len(outcomes),"passed":len(outcomes),"cases":outcomes,
    },ensure_ascii=False,indent=2))

if __name__=="__main__":
    main()
