package net.minecraftforge.gradle.user;

import static net.minecraftforge.gradle.user.UserConstants.*;
import static net.minecraftforge.gradle.common.Constants.*;
import groovy.lang.Closure;
import groovy.util.Node;
import groovy.util.XmlParser;
import groovy.xml.XmlUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import net.minecraftforge.gradle.common.BasePlugin;
import net.minecraftforge.gradle.delayed.DelayedBase;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.json.JsonFactory;
import net.minecraftforge.gradle.tasks.CopyAssetsTask;
import net.minecraftforge.gradle.tasks.DecompileTask;
import net.minecraftforge.gradle.tasks.GenSrgTask;
import net.minecraftforge.gradle.tasks.MergeJarsTask;
import net.minecraftforge.gradle.tasks.ProcessJarTask;
import net.minecraftforge.gradle.tasks.abstractutil.ExtractTask;
import net.minecraftforge.gradle.tasks.user.ApplyBinPatchesTask;
import net.minecraftforge.gradle.tasks.user.SourceCopyTask;
import net.minecraftforge.gradle.tasks.user.reobf.ArtifactSpec;
import net.minecraftforge.gradle.tasks.user.reobf.ReobfTask;

import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.XmlProvider;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Configuration.State;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.internal.plugins.DslObject;
import org.gradle.api.logging.Logger;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.GroovySourceSet;
import org.gradle.api.tasks.ScalaSourceSet;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.compile.GroovyCompile;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.api.tasks.scala.ScalaCompile;
import org.gradle.listener.ActionBroadcast;
import org.gradle.plugins.ide.eclipse.model.Classpath;
import org.gradle.plugins.ide.eclipse.model.ClasspathEntry;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;
import org.gradle.plugins.ide.eclipse.model.Library;
import org.gradle.plugins.ide.eclipse.model.internal.FileReferenceFactory;
import org.gradle.plugins.ide.idea.model.Dependency;
import org.gradle.plugins.ide.idea.model.IdeaModel;
import org.gradle.plugins.ide.idea.model.Module;
import org.gradle.plugins.ide.idea.model.PathFactory;
import org.gradle.plugins.ide.idea.model.SingleEntryModuleLibrary;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.google.common.base.Throwables;
import com.google.common.io.Files;

public abstract class UserBasePlugin extends BasePlugin<UserExtension>
{
    private boolean hasApplied = false;

    @Override
    public void applyPlugin()
    {
        this.applyExternalPlugin("java");
        this.applyExternalPlugin("maven");
        this.applyExternalPlugin("eclipse");
        this.applyExternalPlugin("idea");

        configureDeps();
        configureCompilation();
        configureEclipse();
        configureIntellij();

        tasks();

        Task task = makeTask("setupCIWorkspace", DefaultTask.class);
        task.dependsOn("genSrgs", "deobfBinJar");
        task.setGroup("ForgeGradle");

        task = makeTask("setupDevWorkspace", DefaultTask.class);
        task.dependsOn("genSrgs", "deobfBinJar", "copyAssets", "extractNatives");
        task.setGroup("ForgeGradle");

        task = makeTask("setupDecompWorkspace", DefaultTask.class);
        task.dependsOn("setupDevWorkspace");
        task.setGroup("ForgeGradle");

        project.getTasks().getByName("eclipseClasspath").dependsOn("setupDevWorkspace");
        project.getTasks().getByName("reobf").dependsOn("genSrgs");
        project.getTasks().getByName("compileJava").dependsOn("deobfBinJar");
        project.getTasks().getByName("compileApiJava").dependsOn("deobfBinJar");
    }

    protected Class<UserExtension> getExtensionClass()
    {
        return UserExtension.class;
    }

    @Override
    protected String getDevJson()
    {
        return DelayedBase.resolve(JSON, project);
    }

    private void tasks()
    {
        {
            MergeJarsTask task = makeTask("mergeJars", MergeJarsTask.class);
            task.setClient  (df(JAR_CLIENT_FRESH));
            task.setServer  (df(JAR_SERVER_FRESH));
            task.setOutJar  (df(JAR_MERGED));
            task.setMergeCfg(df(MERGE_CFG));
            task.dependsOn("extractUserDev", "downloadClient", "downloadServer");
        }
        
        {
            GenSrgTask task = makeTask("genSrgs", GenSrgTask.class);
            task.setInSrg     (df(PACKAGED_SRG));
            task.setNotchToMcp(df(DEOBF_MCP_SRG));
            task.setMcpToSrg  (df(REOBF_SRG));
            task.setMcpToNotch(df(REOBF_NOTCH_SRG));
            task.setMethodsCsv(df(METHOD_CSV));
            task.setFieldsCsv (df(FIELD_CSV));
            task.dependsOn("extractUserDev");
        }

        {
            ApplyBinPatchesTask task = makeTask("applyBinPatches", ApplyBinPatchesTask.class);
            task.setInJar     (df(JAR_MERGED));
            task.setOutJar    (getBinPatchOut());
            task.setPatches   (df(BINPATCHES));
            task.setClassesJar(df(BINARIES_JAR));
            task.setResources (delayedFileTree(RES_DIR));
            task.dependsOn("mergeJars");
        }

        {
            ProcessJarTask task = makeTask("deobfBinJar", ProcessJarTask.class);
            task.setSrg         (df(DEOBF_MCP_SRG));
            task.setOutDirtyJar (df(DEOBF_BIN_JAR));
            task.setExceptorJson(df(EXC_JSON));
            task.setExceptorCfg (df(PACKAGED_EXC));
            task.setFieldCsv    (df(FIELD_CSV));
            task.setMethodCsv   (df(METHOD_CSV));
            task.setApplyMarkers(false);
            addATs(task);
            task.dependsOn("downloadMcpTools", "mergeJars", "genSrgs", "applyBinPatches");
        }

        {
            ProcessJarTask task = makeTask("deobfuscateJar", ProcessJarTask.class);
            task.setSrg         (df(PACKAGED_SRG));
            task.setInJar       (df(JAR_MERGED));
            task.setOutDirtyJar (df(DEOBF_JAR));
            task.setExceptorJson(df(EXC_JSON));
            task.setExceptorCfg (df(PACKAGED_EXC));
            task.setApplyMarkers(true);
            addATs(task);
            task.dependsOn("downloadMcpTools", "mergeJars", "genSrgs");
        }

        {
            ReobfTask task = makeTask("reobf", ReobfTask.class);
            task.setExceptorCfg(delayedFile(PACKAGED_EXC));
            task.reobf(project.getTasks().getByName("jar"), new Action<ArtifactSpec>()
            {
                @Override
                public void execute(ArtifactSpec arg0)
                {
                    JavaPluginConvention javaConv = (JavaPluginConvention)project.getConvention().getPlugins().get("java");
                    arg0.setClasspath(javaConv.getSourceSets().getByName("main").getCompileClasspath());
                }

            });
            project.getTasks().getByName("assemble").dependsOn(task);
            task.dependsOn("deobfBinJar");
        }

        {
            CopyAssetsTask task = makeTask("copyAssets", CopyAssetsTask.class);
            task.setAssetsDir(delayedFile(ASSETS));
            task.setOutputDir(delayedFile("{ASSET_DIR}"));
            task.setAssetIndex(getAssetIndexClosure());
            task.dependsOn("getAssets");
        }
    }

    private void delayedTasks()
    {
        ProcessJarTask deobf = (ProcessJarTask)project.getTasks().getByName("deobfuscateJar");
        boolean clean = deobf.isClean();
        DelayedFile decompOut = clean ? getDecompOut() : delayedFile(DECOMP_JAR);

        {
            DecompileTask task = makeTask("decompile", DecompileTask.class);
            task.setInJar(deobf.getDelayedOutput());
            task.setOutJar(decompOut);
            task.setFernFlower(delayedFile(FERNFLOWER));
            task.setPatch     (delayedFile(MCP_PATCH_DIR));
            task.setAstyleConfig(delayedFile(ASTYLE_CFG));
            task.dependsOn("downloadMcpTools", "deobfuscateJar", "genSrgs");
        }
     
        // set the correct deobf thing
        ((ReobfTask)project.getTasks().getByName("reobf")).setDeobfFile(deobf.getDelayedOutput());
        
        doPostDecompTasks(clean, decompOut);
    }

    protected abstract void doPostDecompTasks(boolean isClean, DelayedFile decompOut);

    protected abstract DelayedFile getBinPatchOut();

    protected abstract DelayedFile getDecompOut();

    protected abstract void addATs(ProcessJarTask task);

    private void configureDeps()
    {
        // create configs
        project.getConfigurations().create(CONFIG_USERDEV);
        project.getConfigurations().create(CONFIG_API_JAVADOCS);
        project.getConfigurations().create(CONFIG_API_SRC);
        project.getConfigurations().create(CONFIG_NATIVES);
        project.getConfigurations().create(CONFIG);

        // special userDev stuff
        ExtractTask extractUserDev = makeTask("extractUserDev", ExtractTask.class);
        extractUserDev.into(delayedFile(PACK_DIR));
        extractUserDev.doLast(new Action<Task>()
        {
            @Override
            public void execute(Task arg0)
            {
                readAndApplyJson(delayedFile(JSON).call(), CONFIG, CONFIG_NATIVES, arg0.getLogger());
            }
        });

        // special native stuff
        ExtractTask extractNatives = makeTask("extractNatives", ExtractTask.class);
        extractNatives.into(delayedFile(NATIVES_DIR));
        extractNatives.dependsOn("extractUserDev");
        
        // extra libs folder.
        project.getDependencies().add("compile", project.fileTree("libs"));
    }

    protected void configureCompilation()
    {
        Configuration config = project.getConfigurations().getByName(CONFIG);

        Javadoc javadoc = (Javadoc) project.getTasks().getByName("javadoc");
        javadoc.getClasspath().add(config);

        // get conventions
        JavaPluginConvention javaConv = (JavaPluginConvention) project.getConvention().getPlugins().get("java");
        IdeaModel ideaConv = (IdeaModel) project.getExtensions().getByName("idea");
        EclipseModel eclipseConv = (EclipseModel) project.getExtensions().getByName("eclipse");

        SourceSet main = javaConv.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet test = javaConv.getSourceSets().getByName(SourceSet.TEST_SOURCE_SET_NAME);
        SourceSet api = javaConv.getSourceSets().create("api");

        // set the Source
        javaConv.setSourceCompatibility("1.6");
        javaConv.setTargetCompatibility("1.6");

        // add to SourceSet compile paths
        api.setCompileClasspath(api.getCompileClasspath().plus(config));
        main.setCompileClasspath(main.getCompileClasspath().plus(config).plus(api.getOutput()));
        test.setCompileClasspath(test.getCompileClasspath().plus(config).plus(api.getOutput()).plus(main.getCompileClasspath()));

        // add to eclipse and idea
        ideaConv.getModule().getScopes().get("COMPILE").get("plus").add(config);
        eclipseConv.getClasspath().getPlusConfigurations().add(config);

        // add sourceDirs to Intellij
        ideaConv.getModule().getSourceDirs().addAll(main.getAllSource().getFiles());
        ideaConv.getModule().getSourceDirs().addAll(test.getAllSource().getFiles());
        ideaConv.getModule().getSourceDirs().addAll(api.getAllSource().getFiles());
    }

    private void doSourceReplacement()
    {
        JavaPluginConvention javaConv = (JavaPluginConvention) project.getConvention().getPlugins().get("java");
        SourceSet main = javaConv.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);

        // do the special source moving...
        SourceCopyTask task;

        // main
        {
            task = makeTask("sourceMainJava", SourceCopyTask.class);
            task.setSource(main.getJava());
            task.replace(getExtension().getReplacements());
            task.include(getExtension().getIncludes());
            task.setOutput(delayedFile(SOURCES_DIR + "/java"));

            JavaCompile compile = (JavaCompile) project.getTasks().getByName(main.getCompileJavaTaskName());
            compile.dependsOn("sourceMainJava");
            compile.setSource(task.getOutput());
        }

        // scala!!!
        if (project.getPlugins().hasPlugin("scala"))
        {
            ScalaSourceSet set = (ScalaSourceSet) new DslObject(main).getConvention().getPlugins().get("scala");

            task = makeTask("sourceMainScala", SourceCopyTask.class);
            task.setSource(set.getScala());
            task.replace(getExtension().getReplacements());
            task.include(getExtension().getIncludes());
            task.setOutput(delayedFile(SOURCES_DIR + "/scala"));

            ScalaCompile compile = (ScalaCompile) project.getTasks().getByName(main.getCompileTaskName("scala"));
            compile.dependsOn("sourceMainScala");
            compile.setSource(task.getOutput());
        }

        // groovy!!!
        if (project.getPlugins().hasPlugin("groovy"))
        {
            GroovySourceSet set = (GroovySourceSet) new DslObject(main).getConvention().getPlugins().get("groovy");

            task = makeTask("sourceMainGroovy", SourceCopyTask.class);
            task.setSource(set.getGroovy());
            task.replace(getExtension().getReplacements());
            task.include(getExtension().getIncludes());
            task.setOutput(delayedFile(SOURCES_DIR + "/groovy"));

            GroovyCompile compile = (GroovyCompile) project.getTasks().getByName(main.getCompileTaskName("groovy"));
            compile.dependsOn("sourceMainGroovy");
            compile.setSource(task.getOutput());
        }
    }

    @SuppressWarnings({ "unchecked" })
    protected void configureEclipse()
    {
        EclipseModel eclipseConv = (EclipseModel) project.getExtensions().getByName("eclipse");

        eclipseConv.getClasspath().setDownloadJavadoc(true);
        eclipseConv.getClasspath().setDownloadSources(true);
        ((ActionBroadcast<Classpath>) eclipseConv.getClasspath().getFile().getWhenMerged()).add(new Action<Classpath>()
        {
            @Override
            public void execute(Classpath classpath)
            {
                String natives = delayedString(NATIVES_DIR).call().replace('\\', '/');
                for (ClasspathEntry e : classpath.getEntries())
                {
                    if (e instanceof Library)
                    {
                        Library lib = (Library) e;
                        if (lib.getPath().contains("lwjg") || lib.getPath().contains("jinput"))
                        {
                            lib.setNativeLibraryLocation(natives);
                        }
                    }
                }
            }
        });

        Task task = makeTask("afterEclipseImport", DefaultTask.class);
        task.doLast(new Action<Object>() {
            public void execute(Object obj)
            {
                try
                {
                    Node root = new XmlParser().parseText(Files.toString(project.file(".classpath"), Charset.defaultCharset()));

                    HashMap<String, String> map = new HashMap<String, String>();
                    map.put("name", "org.eclipse.jdt.launching.CLASSPATH_ATTR_LIBRARY_PATH_ENTRY");
                    map.put("value", delayedString(NATIVES_DIR).call());

                    for (Node child : (List<Node>) root.children())
                    {
                        if (child.attribute("path").equals("org.springsource.ide.eclipse.gradle.classpathcontainer"))
                        {
                            child.appendNode("attributes").appendNode("attribute", map);
                            break;
                        }
                    }

                    String result = XmlUtil.serialize(root);

                    project.getLogger().lifecycle(result);
                    Files.write(result, project.file(".classpath"), Charset.defaultCharset());

                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    return;
                }
            }
        });
    }

    @SuppressWarnings("serial")
    protected void configureIntellij()
    {
        IdeaModel ideaConv = (IdeaModel) project.getExtensions().getByName("idea");

        ideaConv.getModule().getExcludeDirs().addAll(project.files(".gradle", "build").getFiles());
        ideaConv.getModule().setDownloadJavadoc(true);
        ideaConv.getModule().setDownloadSources(true);

        Task task = makeTask("genIntellijRuns", DefaultTask.class);
        task.doLast(new Action<Task>() {
            @Override
            public void execute(Task task)
            {
                try
                {
                    String module = task.getProject().getProjectDir().getCanonicalPath();
                    File file = project.file(".idea/workspace.xml");
                    if (!file.exists())
                        throw new RuntimeException("Only run this task after importing a build.gradle file into intellij!");

                    DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
                    Document doc = docBuilder.parse(file);

                    injectIntellijRuns(doc, module);

                    // write the content into xml file
                    TransformerFactory transformerFactory = TransformerFactory.newInstance();
                    Transformer transformer = transformerFactory.newTransformer();
                    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
                    transformer.setOutputProperty(OutputKeys.METHOD, "xml");
                    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

                    DOMSource source = new DOMSource(doc);
                    StreamResult result = new StreamResult(file);
                    //StreamResult result = new StreamResult(System.out);

                    transformer.transform(source, result);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        });
        
        if (ideaConv.getWorkspace().getIws() == null)
            return;

        ideaConv.getWorkspace().getIws().withXml(new Closure<Object>(this, null)
        {
            public Object call(Object... obj)
            {
                Element root = ((XmlProvider) this.getDelegate()).asElement();
                Document doc = root.getOwnerDocument();
                try
                {
                    injectIntellijRuns(doc, project.getProjectDir().getCanonicalPath());
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                return null;
            }
        });
    }

    public final void injectIntellijRuns(Document doc, String module) throws DOMException, IOException
    {
        Element root = null;

        {
            NodeList list = doc.getElementsByTagName("component");
            for (int i = 0; i < list.getLength(); i++)
            {
                Element e = (Element) list.item(i);
                if ("RunManager".equals(e.getAttribute("name")))
                {
                    root = e;
                    break;
                }
            }
        }

        String natives = delayedFile(NATIVES_DIR).call().getCanonicalPath().replace(module, "$PROJECT_DIR$");

        String[][] config = new String[][]
        {
            new String[]
            {
                "Minecraft Client",
                "net.minecraft.launchwrapper.Launch",
                "-Xincgc -Xmx1024M -Xms1024M -Djava.library.path=\"" + natives + "\" -Dfml.ignoreInvalidMinecraftCertificates=true",
                "--version 1.7 --tweakClass cpw.mods.fml.common.launcher.FMLTweaker --username=ForgeDevName --accessToken FML"
            },
            new String[]
            {
                "Minecraft Server",
                "cpw.mods.fml.relauncher.ServerLaunchWrapper",
                "-Xincgc -Dfml.ignoreInvalidMinecraftCertificates=true",
                ""
            }
        };

        for (String[] data : config)
        {
            Element child = add(root, "configuration",
                "default",     "false",
                "name",        data[0],
                "type",        "Application",
                "factoryName", "Application",
                "default",     "false");

            add(child, "extension",
                "name",            "coverage",
                "enabled",         "false",
                "sample_coverage", "true",
                "runner",          "idea");
            add(child, "option", "name", "MAIN_CLASS_NAME", "value", data[1]);
            add(child, "option", "name", "VM_PARAMETERS", "value", data[2]);
            add(child, "option", "name", "PROGRAM_PARAMETERS", "value", data[3]);
            add(child, "option", "name", "WORKING_DIRECTORY", "value", "file://" + delayedFile("{ASSETS_DIR}").call().getParentFile().getCanonicalPath().replace(module, "$PROJECT_DIR$"));
            add(child, "option", "name", "ALTERNATIVE_JRE_PATH_ENABLED", "value", "false");
            add(child, "option", "name", "ALTERNATIVE_JRE_PATH", "value", "");
            add(child, "option", "name", "ENABLE_SWING_INSPECTOR", "value", "false");
            add(child, "option", "name", "ENV_VARIABLES");
            add(child, "option", "name", "PASS_PARENT_ENVS", "value", "true");
            add(child, "module", "name", ((IdeaModel)project.getExtensions().getByName("idea")).getModule().getName());
            add(child, "envs");
            add(child, "RunnerSettings", "RunnerId", "Run");
            add(child, "ConfigurationWrapper", "RunnerId", "Run");
            add(child, "method");
        }
    }

    private Element add(Element parent, String name, String... values)
    {
        Element e = parent.getOwnerDocument().createElement(name);
        for (int x = 0; x < values.length; x += 2)
        {
            e.setAttribute(values[x], values[x + 1]);
        }
        parent.appendChild(e);
        return e;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void afterEvaluate()
    {
        super.afterEvaluate();

        // grab the json && read dependencies
        if (delayedFile(JSON).call().exists())
        {
            readAndApplyJson(delayedFile(JSON).call(), CONFIG, CONFIG_NATIVES, project.getLogger());
        }

        // extract userdev
        ((ExtractTask) project.getTasks().findByName("extractUserDev")).from(delayedFile(project.getConfigurations().getByName(CONFIG_USERDEV).getSingleFile().getAbsolutePath()));
        project.getTasks().findByName("getAssetsIndex").dependsOn("extractUserDev");

        // add src ATs
        ProcessJarTask binDeobf = (ProcessJarTask) project.getTasks().getByName("deobfBinJar");
        ProcessJarTask decompDeobf = (ProcessJarTask) project.getTasks().getByName("deobfuscateJar");

        // from the ExtensionObject
        binDeobf.addTransformer(getExtension().getAccessTransformers().toArray());

        // from the resources dirs
        {
            JavaPluginConvention javaConv = (JavaPluginConvention) project.getConvention().getPlugins().get("java");

            SourceSet main = javaConv.getSourceSets().getByName("main");
            SourceSet api = javaConv.getSourceSets().getByName("api");

            for (File at : main.getResources().getFiles())
            {
                if (at.getName().toLowerCase().endsWith("_at.cfg"))
                {
                    binDeobf.addTransformer(at);
                    decompDeobf.addTransformer(at);
                }
            }

            for (File at : api.getResources().getFiles())
            {
                if (at.getName().toLowerCase().endsWith("_at.cfg"))
                {
                    binDeobf.addTransformer(at);
                    decompDeobf.addTransformer(at);
                }
            }
        }

        // make delayed tasks regarding decompilation
        delayedTasks();

        // configure source replacement
        doSourceReplacement();

        final File deobfOut = ((ProcessJarTask) project.getTasks().getByName("deobfBinJar")).getOutJar();

        // add dependency
        project.getDependencies().add(CONFIG, project.files(deobfOut));

        // link sources and javadocs eclipse
        EclipseModel eclipseConv = (EclipseModel) project.getExtensions().getByName("eclipse");
        ((ActionBroadcast<Classpath>) eclipseConv.getClasspath().getFile().getWhenMerged()).add(new Action<Classpath>()
        {
            FileReferenceFactory factory = new FileReferenceFactory();

            @Override
            public void execute(Classpath classpath)
            {
                for (ClasspathEntry e : classpath.getEntries())
                {
                    if (e instanceof Library)
                    {
                        Library lib = (Library) e;
                        if (lib.getLibrary().getFile().equals(deobfOut))
                        {
                            lib.setJavadocPath(factory.fromFile(project.getConfigurations().getByName(CONFIG_API_JAVADOCS).getSingleFile()));
                            lib.setSourcePath(factory.fromFile(project.getConfigurations().getByName(CONFIG_API_SRC).getSingleFile()));
                        }
                    }
                }
            }
        });

        // link sources and javadocs ntellij idea
        IdeaModel ideaConv = (IdeaModel) project.getExtensions().getByName("idea");
        ((ActionBroadcast<Module>) ideaConv.getModule().getIml().getWhenMerged()).add(new Action<Module>() {

            PathFactory factory = new PathFactory();

            @Override
            public void execute(Module module)
            {
                for (Dependency d : module.getDependencies())
                {
                    if (d instanceof SingleEntryModuleLibrary)
                    {
                        SingleEntryModuleLibrary lib = (SingleEntryModuleLibrary) d;
                        if (lib.getLibraryFile().equals(deobfOut))
                        {
                            lib.getJavadoc().add(factory.path("jar://" + project.getConfigurations().getByName(CONFIG_API_JAVADOCS).getSingleFile().getAbsolutePath().replace('\\', '/') + "!/"));
                            lib.getSources().add(factory.path("jar://" + project.getConfigurations().getByName(CONFIG_API_SRC).getSingleFile().getAbsolutePath().replace('\\', '/') + "!/"));
                        }
                    }
                }
            }
        });

        // fix eclipse project location...
        fixEclipseProject(ECLIPSE_LOCATION);
    }

    @Override
    public void finalCall()
    {
        Configuration config = project.getConfigurations().getByName(CONFIG);

        Javadoc javadoc = (Javadoc) project.getTasks().getByName("javadoc");
        javadoc.getClasspath().add(config);

        // get conventions
        JavaPluginConvention javaConv = (JavaPluginConvention) project.getConvention().getPlugins().get("java");
        SourceSet main = javaConv.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);

        main.getCompileConfigurationName();
        Configuration compileConfig = project.getConfigurations().getByName(main.getCompileConfigurationName());

        compileConfig.extendsFrom(config);
    }

    private static final byte[] LOCATION_BEFORE = new byte[] { 0x40, (byte) 0xB1, (byte) 0x8B, (byte) 0x81, 0x23, (byte) 0xBC, 0x00, 0x14, 0x1A, 0x25, (byte) 0x96, (byte) 0xE7, (byte) 0xA3, (byte) 0x93, (byte) 0xBE, 0x1E };
    private static final byte[] LOCATION_AFTER  = new byte[] { 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xC0, 0x58, (byte) 0xFB, (byte) 0xF3, 0x23, (byte) 0xBC, 0x00, 0x14, 0x1A, 0x51, (byte) 0xF3, (byte) 0x8C, 0x7B, (byte) 0xBB, 0x77, (byte) 0xC6 };

    protected void fixEclipseProject(String path)
    {
        File f = new File(path);
        if (f.exists())// && f.length() == 0)
        {
            String projectDir = "URI//" + project.getProjectDir().toURI().toString();
            try
            {
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(LOCATION_BEFORE); //Unknown but w/e
                fos.write((byte) ((projectDir.length() & 0xFF) >> 8));
                fos.write((byte) ((projectDir.length() & 0xFF) >> 0));
                fos.write(projectDir.getBytes());
                fos.write(LOCATION_AFTER); //Unknown but w/e
                fos.close();
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    private void readAndApplyJson(File file, String depConfig, String nativeConfig, Logger log)
    {
        if (version == null)
        {
            try
            {
                version = JsonFactory.loadVersion(file);
            }
            catch (Exception e)
            {
                log.error("" + file + " could not be parsed");
                Throwables.propagate(e);
            }
        }

        if (hasApplied)
            return;

        // apply the dep info.
        DependencyHandler handler = project.getDependencies();

        // actual dependencies
        if (project.getConfigurations().getByName(depConfig).getState() == State.UNRESOLVED)
        {
            for (net.minecraftforge.gradle.json.version.Library lib : version.getLibraries())
            {
                if (lib.natives == null)
                    handler.add(depConfig, lib.getArtifactName());
            }
        }
        else
            log.info("RESOLVED: " + depConfig);

        // the natives
        if (project.getConfigurations().getByName(nativeConfig).getState() == State.UNRESOLVED)
        {
            for (net.minecraftforge.gradle.json.version.Library lib : version.getLibraries())
            {
                if (lib.natives != null)
                    handler.add(nativeConfig, lib.getArtifactName());
            }
        }
        else
            log.info("RESOLVED: " + nativeConfig);

        hasApplied = true;

        // add stuff to the natives task thing..
        // extract natives
        ExtractTask task = (ExtractTask) project.getTasks().findByName("extractNatives");
        for (File dep : project.getConfigurations().getByName(CONFIG_NATIVES).getFiles())
        {
            log.info("ADDING NATIVE: " + dep.getPath());
            task.from(delayedFile(dep.getAbsolutePath()));
            task.exclude("META-INF/**", "META-INF/**");
        }
    }

    @Override
    public String resolve(String pattern, Project project, UserExtension exten)
    {
        pattern = super.resolve(pattern, project, exten);
        pattern = pattern.replace("{API_VERSION}", exten.getApiVersion());
        return pattern;
    }

    private DelayedFile df(String file){ return delayedFile(file); }
}
