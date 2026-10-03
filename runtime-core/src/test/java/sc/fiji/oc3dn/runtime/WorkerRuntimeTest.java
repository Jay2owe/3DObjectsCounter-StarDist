package sc.fiji.oc3dn.runtime;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.jar.*;

public class WorkerRuntimeTest {
    public static final class Fixture {}

    @Test public void windowsWorkerRequiresWindowlessLauncher() throws Exception {
        if(!System.getProperty("os.name").toLowerCase().contains("win"))return;
        Path folder=Files.createTempDirectory("worker-launcher-");
        try {
            Path java=folder.resolve("java.exe");Files.write(java,new byte[0]);
            try { WorkerRuntime.workerLauncher(java);fail("Must not silently use a console launcher"); }
            catch(java.io.IOException expected) { assertTrue(expected.getMessage().contains("javaw.exe")); }
            Path windowless=folder.resolve("javaw.exe");Files.write(windowless,new byte[0]);
            assertEquals(windowless,WorkerRuntime.workerLauncher(java));
        } finally { Files.deleteIfExists(folder.resolve("javaw.exe"));Files.deleteIfExists(folder.resolve("java.exe"));Files.deleteIfExists(folder); }
    }

    @Test public void locatesFijiJarWhenCodeSourceLocationIsNull() throws Exception {
        String name=Fixture.class.getName(),resource=name.replace('.','/')+".class";
        byte[] bytes;try(var input=Fixture.class.getResourceAsStream("/"+resource)){bytes=input.readAllBytes();}
        Path jar=Files.createTempFile("fiji-null-code-source-",".jar");
        try {
            try(var output=new JarOutputStream(Files.newOutputStream(jar))){output.putNextEntry(new JarEntry(resource));output.write(bytes);output.closeEntry();}
            ClassLoader loader=new ClassLoader(null) {
                @Override protected Class<?> findClass(String requested) throws ClassNotFoundException {
                    if(!requested.equals(name))throw new ClassNotFoundException(requested);
                    // Fiji's patched ImageJ has a CodeSource but no location URL.
                    return defineClass(name,bytes,0,bytes.length,new ProtectionDomain(new CodeSource((URL)null,(Certificate[])null),null));
                }
                @Override public URL getResource(String requested) {
                    if(!requested.equals(resource))return null;
                    try{return new URL("jar:"+jar.toUri()+"!/"+resource);}catch(MalformedURLException e){throw new AssertionError(e);}
                }
            };
            Class<?> patched=loader.loadClass(name);
            assertNotNull(patched.getProtectionDomain().getCodeSource());
            assertNull(patched.getProtectionDomain().getCodeSource().getLocation());
            assertEquals(jar.toAbsolutePath(),WorkerRuntime.artifact(patched));
        } finally { Files.deleteIfExists(jar); }
    }
}
