package com.shatteredpixel.shatteredpixeldungeon.scenes;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.shatteredpixel.shatteredpixeldungeon.desktop.ProfileLock;
import com.watabou.utils.FileUtils;
import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class ProfileCleanupTest {
    @Test public void welcomeCleanupCannotUnlinkHeldLockOrTouchOtherApplicationData() throws Exception {
        Path profile=Files.createTempDirectory("spd-cleanup-");
        com.badlogic.gdx.Files previous=Gdx.files;
        Field type=FileUtils.class.getDeclaredField("defaultFileType"),path=FileUtils.class.getDeclaredField("defaultPath");
        type.setAccessible(true);path.setAccessible(true);
        Object oldType=type.get(null),oldPath=path.get(null);
        try {
            Gdx.files=(com.badlogic.gdx.Files)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{com.badlogic.gdx.Files.class},(proxy,method,args)->new FileHandle((String)args[0]));
            FileUtils.setDefaultFileProperties(com.badlogic.gdx.Files.FileType.Absolute,profile+"/");
            Path audit=Files.createDirectories(profile.resolve("audit"));
            Path journal=Files.createFile(audit.resolve("public.sqlite3-journal"));
            Path unrelated=Files.write(audit.resolve("not-a-save.spdtmp"),new byte[]{1,2,3});
            Path loose=Files.createFile(profile.resolve("unrelated.empty"));
            Path game=Files.createDirectories(profile.resolve("game1"));
            Path invalidSave=Files.createFile(game.resolve("depth1.dat"));
            try(ProfileLock lock=new ProfileLock(profile)) {
                Path lockPath=profile.resolve(".instance.lock");
                Object inode=Files.getAttribute(lockPath,"unix:ino");
                assertTrue(Files.size(lockPath)>0);
                assertFalse(WelcomeScene.cleanGameTempFiles());
                assertEquals(inode,Files.getAttribute(lockPath,"unix:ino"));
                assertTrue(Files.exists(journal));assertTrue(Files.exists(loose));
                assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(unrelated));
                assertFalse(Files.exists(invalidSave));
                try(ProfileLock ignored=new ProfileLock(profile)) { fail("Must preserve the held inode and reject a second holder"); }
                catch(java.nio.channels.OverlappingFileLockException expected) { }
            }
            try(ProfileLock ignored=new ProfileLock(profile)) { assertTrue(Files.size(profile.resolve(".instance.lock"))>0); }
        } finally {
            Gdx.files=previous;type.set(null,oldType);path.set(null,oldPath);
            try(java.util.stream.Stream<Path> files=Files.walk(profile)) {
                for(Path file:(Iterable<Path>)files.sorted(java.util.Comparator.reverseOrder())::iterator)Files.delete(file);
            }
        }
    }
}
