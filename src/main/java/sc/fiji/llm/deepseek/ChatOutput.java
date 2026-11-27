/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. See LICENSE.txt and NOTICE.txt. */
package sc.fiji.llm.deepseek;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import com.google.gson.*;

/**
 * Where one chat's files go: Fiji_chat_output_&lt;yyyyMMdd&gt;_&lt;n&gt; next to the input image,
 * one folder per chat and input directory. The folder is assigned by this extension, never
 * reused, and nothing this class writes replaces an existing file.
 *
 * Recording is a convenience for the reader, not part of the analysis: every method called
 * from a tool or display callback is best effort and never throws.
 */
final class ChatOutput {
    static final String PREFIX="Fiji_chat_output_";
    static final String FORMAT="fiji-chat-output-v1";
    private static final DateTimeFormatter CLOCK=DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Used for process records until a folder exists, so nothing is lost if none is ever assigned. */
    private final Path staging;
    private final Path archive;
    volatile boolean enabled=true;
    volatile String label="";
    private final long started=System.currentTimeMillis();
    private final Map<Path,Path> byParent=new LinkedHashMap<>();
    private final Map<Path,JsonObject> inputs=new LinkedHashMap<>();
    private final Map<String,String> scripts=new HashMap<>();
    private final Map<Path,String> extensionFiles=new HashMap<>(); // file written by this extension -> what it is
    private final List<String> warnings=new ArrayList<>();
    private final Set<String> reportedFailures=new HashSet<>();
    private Path folder,processHome;
    private int scriptCount,reportCount;

    ChatOutput(Path archive){this.archive=archive;this.staging=archive.resolve("output-staging");}

    /** Next free number for that day, claimed by creating the directory. An existing folder is never reused, even if empty. */
    static Path allocate(Path parent,LocalDate day)throws IOException{
        if(!Files.isDirectory(parent))throw new NoSuchFileException(parent.toString(),null,"The folder does not exist");
        String stem=PREFIX+day.format(DateTimeFormatter.BASIC_ISO_DATE)+"_";int next=1;
        try(var entries=Files.list(parent)){
            for(var entry:(Iterable<Path>)entries::iterator){
                String name=entry.getFileName().toString();
                if(name.startsWith(stem)&&name.substring(stem.length()).matches("[0-9]{1,9}"))next=Math.max(next,Integer.parseInt(name.substring(stem.length()))+1);
            }
        }
        for(int attempt=0;attempt<1000;attempt++){
            try{return Files.createDirectory(parent.resolve(stem+(next+attempt)));}
            catch(FileAlreadyExistsException takenMeanwhile){/* another chat claimed it: try the next number */}
        }
        throw new IOException("No free output folder name in "+parent);
    }

    /** The directory an image was opened from, or null for an unsaved or remote image. */
    static Path imageDirectory(ij.ImagePlus image){
        try{
            var info=image==null?null:image.getOriginalFileInfo();
            if(info==null||info.directory==null||info.directory.isBlank())return null;
            Path directory=Path.of(info.directory).toAbsolutePath().normalize();
            return Files.isDirectory(directory)?directory:null;
        }catch(RuntimeException notLocal){return null;}
    }
    /** The file an image was opened from, if it is still there and the image has not been edited since. Null for a derived or unsaved image. */
    static Path sourceFile(ij.ImagePlus image){
        try{
            var info=image==null?null:image.getOriginalFileInfo();
            if(info==null||info.directory==null||info.fileName==null||info.fileName.isBlank()||image.changes)return null;
            Path file=Path.of(info.directory,info.fileName).toAbsolutePath().normalize();
            return Files.isRegularFile(file)?file:null;
        }catch(RuntimeException notLocal){return null;}
    }
    static final int NEAR_LIMIT=200;
    /**
     * Files written since this chat began in the input folder and up to three folders below it: a delivered macro
     * usually writes into a folder of its own. Output folders of this and other chats are not looked into.
     */
    private void newFilesNear(Path parent,Set<String> found)throws IOException{
        int[] visited={0};
        Files.walkFileTree(parent,EnumSet.noneOf(FileVisitOption.class),4,new SimpleFileVisitor<Path>(){
            @Override public FileVisitResult preVisitDirectory(Path directory,java.nio.file.attribute.BasicFileAttributes attributes){
                return !directory.equals(parent)&&directory.getFileName().toString().startsWith(PREFIX)?FileVisitResult.SKIP_SUBTREE:FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attributes){
                if(++visited[0]>20000||found.size()>=NEAR_LIMIT)return FileVisitResult.TERMINATE;
                if(attributes.isRegularFile()&&attributes.lastModifiedTime().toMillis()>=started&&!inputs.containsKey(file))found.add(file.toString());
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file,IOException unreadable){return FileVisitResult.CONTINUE;}
        });
    }
    private static Path openImageDirectory(){
        Path active=imageDirectory(ij.WindowManager.getCurrentImage());if(active!=null)return active;
        int[] ids=ij.WindowManager.getIDList();
        if(ids!=null)for(int id:ids){Path directory=imageDirectory(ij.WindowManager.getImage(id));if(directory!=null)return directory;}
        return null;
    }

    synchronized Path folder(){return folder;}

    /** The folder for inputs in this directory, created on first use. */
    synchronized Path assign(Path parent,ij.ImagePlus input)throws IOException{
        if(!enabled)throw new IllegalStateException("The standard output folder is switched off in LLM Execution Settings");
        Path key=parent.toAbsolutePath().normalize();Path existing=byParent.get(key);
        if(existing==null){
            existing=allocate(key,LocalDate.now());byParent.put(key,existing);
            if(processHome==null)adoptProcessRecords(existing);
        }
        folder=existing;
        if(input!=null)try{noteInput(input);}catch(Exception unreadable){/* the manifest simply omits it */}
        try{writeManifest(false);}catch(Exception failure){failed("manifest",failure);}
        return existing;
    }
    /** Assigns a folder next to an open image if none exists yet. Returns null when there is nowhere to put one. */
    synchronized Path ensure(){
        if(folder!=null||!enabled)return folder;
        try{Path parent=openImageDirectory();return parent==null?null:assign(parent,ij.WindowManager.getCurrentImage());}
        catch(Exception unavailable){failed("output folder",unavailable);return null;}
    }

    private void noteInput(ij.ImagePlus image)throws Exception{
        var info=image.getOriginalFileInfo();if(info==null||info.directory==null||info.fileName==null)return;
        Path file=Path.of(info.directory,info.fileName).toAbsolutePath().normalize();
        if(inputs.containsKey(file)||!Files.isRegularFile(file))return;
        var row=new JsonObject();row.addProperty("path",file.toString());row.addProperty("bytes",Files.size(file));
        if(Files.size(file)<=1L<<30)row.addProperty("sha256",AnalysisBundle.fileHash(file));
        inputs.put(file,row);
    }

    private Path processDirectory()throws IOException{
        Path home=processHome!=null?processHome:staging;
        return Files.createDirectories(home.resolve("process"));
    }
    /** Records made before the folder existed move into it; later ones are written there directly. */
    private void adoptProcessRecords(Path target){
        processHome=target;
        try{
            Path from=staging.resolve("process");if(!Files.isDirectory(from))return;
            Path to=Files.createDirectories(target.resolve("process"));
            try(var walk=Files.walk(from)){
                for(var source:(Iterable<Path>)walk.sorted()::iterator){
                    Path destination=to.resolve(from.relativize(source).toString());
                    if(Files.isDirectory(source))Files.createDirectories(destination);
                    else if(!Files.exists(destination))Files.copy(source,destination);
                }
            }
        }catch(Exception failure){failed("earlier process records",failure);}
    }

    private static String safeName(String name){
        String cleaned=name==null?"":name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").strip();
        return cleaned.isBlank()||cleaned.equals(".")||cleaned.equals("..")?"script.txt":cleaned;
    }

    /** Keeps the bytes of a script that was just started, numbered in run order. Identical content is stored once. */
    synchronized void script(String name,String content,String outcome){
        if(!enabled||content==null)return;
        try{
            String key=ScriptValidation.hash(safeName(name)+"\u0000"+content);String relative=scripts.get(key);
            if(relative==null){
                Path directory=Files.createDirectories(processDirectory().resolve("scripts"));
                Path file=directory.resolve(String.format(Locale.ROOT,"%02d_%s",++scriptCount,safeName(name)));
                Files.write(file,content.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                relative="process/scripts/"+file.getFileName();scripts.put(key,relative);
            }
            step("ran `"+relative.substring(relative.lastIndexOf('/')+1)+"`"+(outcome==null||outcome.isBlank()?"":" - "+outcome));
        }catch(Exception failure){failed("process script",failure);}
    }

    /** One line in process/steps.md, appended at once so that it survives a crash. */
    synchronized void step(String text){
        if(!enabled||text==null||text.isBlank())return;
        try{
            Path file=processDirectory().resolve("steps.md");
            String line=(Files.exists(file)?"":"# Steps of this chat\n\nTimes are local. Tool arguments and results are in the full record named in manifest.json.\n\n")+
                "- "+LocalDateTime.now().format(CLOCK)+" "+text.replace('\r',' ').replace('\n',' ').strip()+"\n";
            Files.writeString(file,line,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }catch(Exception failure){failed("step log",failure);}
    }

    /** Remembers a file this extension wrote inside the folder, for the manifest. */
    synchronized void wrote(Path file,String role){
        try{extensionFiles.put(file.toAbsolutePath().normalize(),role);writeManifest(false);}
        catch(Exception failure){failed("manifest",failure);}
    }

    /** The final answer exactly as shown in the chat. A later answer in the same chat gets the next number. */
    synchronized void report(String text){
        if(!enabled||text==null||text.isBlank())return;
        try{
            if(folder==null)return; // a chat that ran no script and saved nothing gets no folder
            Path file;
            do{file=folder.resolve(++reportCount==1?"report.md":"report_"+reportCount+".md");}while(Files.exists(file));
            Files.write(file,text.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            extensionFiles.put(file,"final report as shown in the chat");
            step("final report saved as `"+file.getFileName()+"`");
            writeManifest(true);
        }catch(Exception failure){failed("report",failure);}
    }

    /** A script that names this chat's folder literally writes there again when it is reused on another day or image. */
    synchronized String literalFolderIn(String script){
        if(script==null)return null;
        String text=script.toLowerCase(Locale.ROOT);
        for(Path home:byParent.values()){
            String path=home.toString().toLowerCase(Locale.ROOT);
            for(String form:List.of(path,path.replace("\\","/"),path.replace("\\","\\\\")))if(text.contains(form))return home.toString();
        }
        return null;
    }
    synchronized void warn(String warning){if(!warnings.contains(warning))warnings.add(warning);try{writeManifest(false);}catch(Exception failure){failed("manifest",failure);}}

    /** Free directory name inside the folder: results, results_2, ... */
    synchronized Path fresh(String name)throws IOException{
        if(folder==null)throw new IllegalStateException("No output folder");
        for(int n=1;n<1000;n++){
            try{return Files.createDirectory(folder.resolve(n==1?name:name+"_"+n));}
            catch(FileAlreadyExistsException used){/* keep the earlier one untouched */}
        }
        throw new IOException("No free name for "+name+" in "+folder);
    }

    private String roleOf(Path file,String relative){
        String role=extensionFiles.get(file.toAbsolutePath().normalize());if(role!=null)return role;
        if(relative.startsWith("process/scripts/"))return "script that was run";
        return relative.equals("process/steps.md")?"step log":null;
    }

    /** Lists what is in the folder. The last one of a chat also hashes every file and lists new files next to the input. */
    private void writeManifest(boolean scan)throws Exception{
        if(folder==null)return;
        var manifest=new JsonObject();manifest.addProperty("format",FORMAT);manifest.addProperty("folder",folder.toString());
        manifest.addProperty("written",LocalDateTime.now().withNano(0).toString());
        String version=ChatOutput.class.getPackage()==null?null:ChatOutput.class.getPackage().getImplementationVersion();
        if(version!=null)manifest.addProperty("extension_version",version);
        if(!label.isBlank())manifest.addProperty("assistant",label.split("\n")[0]);
        manifest.addProperty("full_record",archive.toString());
        var inputRows=new JsonArray();inputs.values().forEach(inputRows::add);manifest.add("inputs",inputRows);
        var files=new JsonArray();
        try(var walk=Files.walk(folder)){
            for(var file:(Iterable<Path>)walk.filter(Files::isRegularFile).sorted()::iterator){
                String relative=folder.relativize(file).toString().replace('\\','/');if(relative.equals("manifest.json"))continue;
                var row=new JsonObject();row.addProperty("path",relative);row.addProperty("bytes",Files.size(file));
                if(scan&&Files.size(file)<=1L<<30)row.addProperty("sha256",AnalysisBundle.fileHash(file));
                String role=roleOf(file,relative);
                row.addProperty("written_by",role!=null?"extension":"script or user");
                if(role!=null)row.addProperty("role",role);
                files.add(row);
            }
        }
        manifest.add("files",files);manifest.addProperty("final",scan);
        if(scan){
            var found=new TreeSet<String>();
            for(Path parent:byParent.keySet())newFilesNear(parent,found);
            var outside=new JsonArray();found.forEach(outside::add);
            manifest.add("new_files_next_to_the_input",outside);
            if(found.size()>=NEAR_LIMIT)manifest.addProperty("new_files_next_to_the_input_truncated",true);
        }
        var notes=new JsonArray();warnings.forEach(notes::add);manifest.add("warnings",notes);
        Files.writeString(folder.resolve("manifest.json"),new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(manifest),StandardCharsets.UTF_8);
    }

    private void failed(String what,Exception failure){
        if(reportedFailures.add(what))System.err.println("[Fiji LLM Provider] Could not save the "+what+" of this chat: "+failure);
        String warning="Could not save the "+what+": "+failure.getClass().getSimpleName();
        if(!warnings.contains(warning))warnings.add(warning);
    }
}
