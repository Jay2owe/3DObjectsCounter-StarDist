package sc.fiji.oc3dn.runtime;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** A Java 11 host keeps its UI; Java 21 owns only the managed Python bridge. */
public final class WorkerRuntime {
    private WorkerRuntime(){}
    private static volatile Path cachedJava;
    public static String hex(byte[] value){StringBuilder text=new StringBuilder(value.length*2);for(byte b:value){text.append(Character.forDigit((b>>>4)&15,16));text.append(Character.forDigit(b&15,16));}return text.toString();}
    public static Path java21()throws IOException{
        if(cachedJava!=null)return cachedJava;
        Set<Path> candidates=new LinkedHashSet<>();
        String explicit=System.getProperty("oc3d.worker.java");if(explicit!=null)candidates.add(Path.of(explicit));
        for(String home:new String[]{System.getProperty("java.home"),System.getenv("JAVA_HOME")})if(home!=null)candidates.add(Path.of(home,"bin",isWindows()?"java.exe":"java"));
        String path=System.getenv("PATH");if(path!=null)for(String folder:path.split(java.io.File.pathSeparator))if(!folder.isBlank())candidates.add(Path.of(folder,isWindows()?"java.exe":"java"));
        if(isWindows()){
            String programs=System.getenv("ProgramFiles");if(programs!=null)for(String vendor:new String[]{"Eclipse Adoptium","Zulu","Microsoft","Java"}){
                Path directory=Path.of(programs,vendor);if(Files.isDirectory(directory))try(var children=Files.list(directory)){children.filter(Files::isDirectory).forEach(p->candidates.add(p.resolve("bin/java.exe")));}
            }
        }
        for(Path candidate:candidates)if(Files.isRegularFile(candidate)){
            try{Process test=new ProcessBuilder(workerLauncher(candidate).toString(),"-version").redirectErrorStream(true).start();String version=new String(test.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);test.waitFor();var match=java.util.regex.Pattern.compile("version \"(\\d+)").matcher(version);if(match.find()&&Integer.parseInt(match.group(1))>=21){cachedJava=candidate.toAbsolutePath();return cachedJava;}}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("Java discovery cancelled");}catch(IOException ignored){}
        }
        throw new IOException("A Java 21 worker runtime is required; select its executable with oc3d.worker.java. Fiji can remain on Java 11.");
    }
    private static boolean isWindows(){return System.getProperty("os.name").toLowerCase().contains("win");}
    /** GUI hosts must use the Windows GUI-subsystem launcher, retaining redirected pipes. */
    public static Path workerLauncher(Path java)throws IOException{
        if(!isWindows())return java;
        Path windowless=java.resolveSibling("javaw.exe");
        if(!Files.isRegularFile(windowless))throw new IOException("The Java worker installation is missing javaw.exe; choose a complete Java 21 runtime.");
        return windowless;
    }
    public static Path artifact(Class<?> type)throws Exception{
        // Fiji patches ImageJ with a CodeSource whose location is null; use its class resource instead.
        var domain=type.getProtectionDomain();var source=domain==null?null:domain.getCodeSource();
        java.net.URL location=source==null?null:source.getLocation();if(location!=null)return Path.of(location.toURI());
        java.net.URL resource=type.getResource("/"+type.getName().replace('.','/')+".class");
        if(resource!=null&&resource.getProtocol().equals("jar"))return Path.of(((java.net.JarURLConnection)resource.openConnection()).getJarFileURL().toURI());
        throw new IOException("Cannot locate the runtime artifact for "+type.getName());
    }
    public static Thread watchCancellation(Path flag){
        Thread owner=Thread.currentThread();Thread watcher=new Thread(()->{try{while(owner.isAlive()){if(Files.exists(flag)){owner.interrupt();return;}Thread.sleep(100);}}catch(InterruptedException stopped){}},"counter-worker-cancellation");watcher.setDaemon(true);watcher.start();return watcher;
    }
    public static String run(Class<?> owner,Class<?> imagej,String main,List<String> args,Path cancellation,Consumer<String> progress)throws Exception{
        Path jar=artifact(owner),ij=artifact(imagej);
        List<String> command=new ArrayList<>();command.add(workerLauncher(java21()).toString());command.add("-Xmx4g");command.add("-Djava.awt.headless=true");command.add("-cp");command.add(jar+File.pathSeparator+ij);command.add(main);command.addAll(args);command.add(cancellation.toString());
        progress.accept("Starting the managed Python worker; Fiji stays on Java "+Runtime.version().feature());
        Process process=new ProcessBuilder(command).start();StringBuilder stdout=new StringBuilder();List<IOException> errors=Collections.synchronizedList(new ArrayList<>());
        Thread output=reader(process.getInputStream(),line->{synchronized(stdout){stdout.append(line).append('\n');}},errors);
        Thread diagnostic=reader(process.getErrorStream(),progress,errors);
        boolean interrupted=false;
        try{try{process.waitFor();}catch(InterruptedException e){interrupted=true;Files.writeString(cancellation,"cancel");progress.accept("Cancellation requested; preserving completed checkpoints.");process.waitFor();}
            output.join();diagnostic.join();
            if(interrupted)throw new InterruptedException("Managed worker cancelled");
            if(!errors.isEmpty())throw errors.get(0);
            if(process.exitValue()!=0)throw new IOException("Managed worker exited with code "+process.exitValue()+"; see the progress log.");
            synchronized(stdout){return stdout.toString();}
        }finally{if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroy);process.destroy();}if(interrupted)Thread.currentThread().interrupt();}
    }
    private static Thread reader(InputStream stream,Consumer<String> consumer,List<IOException> errors){Thread t=new Thread(()->{try(var input=new BufferedReader(new InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8))){String line;while((line=input.readLine())!=null)consumer.accept(line);}catch(IOException e){errors.add(e);}},"counter-worker-output");t.setDaemon(true);t.start();return t;}
}
