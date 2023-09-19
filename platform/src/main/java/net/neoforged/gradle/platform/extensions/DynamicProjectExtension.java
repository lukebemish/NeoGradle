package net.neoforged.gradle.platform.extensions;

import net.minecraftforge.gdi.BaseDSLElement;
import net.minecraftforge.gdi.annotations.ProjectGetter;
import net.neoforged.gradle.common.runtime.extensions.CommonRuntimeExtension;
import net.neoforged.gradle.dsl.common.tasks.WithOutput;
import net.neoforged.gradle.dsl.common.util.CommonRuntimeUtils;
import net.neoforged.gradle.dsl.common.util.DistributionType;
import net.neoforged.gradle.neoform.NeoFormProjectPlugin;
import net.neoforged.gradle.neoform.runtime.definition.NeoFormRuntimeDefinition;
import net.neoforged.gradle.neoform.runtime.extensions.NeoFormRuntimeExtension;
import net.neoforged.gradle.neoform.runtime.tasks.UnpackZip;
import net.neoforged.gradle.neoform.util.NeoFormRuntimeUtils;
import net.neoforged.gradle.platform.PlatformDevProjectPlugin;
import net.neoforged.gradle.platform.model.DynamicProjectType;
import net.neoforged.gradle.platform.runtime.definition.PlatformDevRuntimeDefinition;
import net.neoforged.gradle.platform.runtime.extension.PlatformDevRuntimeExtension;
import net.neoforged.gradle.platform.runtime.tasks.GenerateBinaryPatches;
import net.neoforged.gradle.platform.runtime.tasks.GenerateSourcePatches;
import net.neoforged.gradle.platform.runtime.tasks.PackZip;
import net.neoforged.gradle.platform.tasks.SetupProjectFromRuntime;
import net.neoforged.gradle.platform.util.SetupUtils;
import net.neoforged.gradle.vanilla.VanillaProjectPlugin;
import net.neoforged.gradle.vanilla.runtime.VanillaRuntimeDefinition;
import net.neoforged.gradle.vanilla.runtime.extensions.VanillaRuntimeExtension;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.JavaPlatformExtension;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.tasks.Jar;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;
import java.io.File;
import java.util.EnumMap;

public abstract class DynamicProjectExtension implements BaseDSLElement<DynamicProjectExtension> {

    private final Project project;

    @Nullable
    private DynamicProjectType type = null;

    @Inject
    public DynamicProjectExtension(Project project) {
        this.project = project;
    }

    @ProjectGetter
    @Override
    public Project getProject() {
        return project;
    }

    public void clean() {
        clean("+");
    }

    public void clean(final String minecraftVersion) {
        type = DynamicProjectType.CLEAN;

        project.getPlugins().apply(VanillaProjectPlugin.class);

        final JavaPluginExtension javaPluginExtension = getProject().getExtensions().getByType(JavaPluginExtension.class);
        final SourceSet mainSource = javaPluginExtension.getSourceSets().getByName("main");

        final VanillaRuntimeExtension vanillaRuntimeExtension = project.getExtensions().getByType(VanillaRuntimeExtension.class);
        final VanillaRuntimeDefinition runtimeDefinition = vanillaRuntimeExtension.create(builder -> {
            builder.withMinecraftVersion(minecraftVersion)
                  .withDistributionType(DistributionType.CLIENT)
                  .withFartVersion(vanillaRuntimeExtension.getFartVersion())
                  .withForgeFlowerVersion(vanillaRuntimeExtension.getForgeFlowerVersion())
                  .withAccessTransformerApplierVersion(vanillaRuntimeExtension.getAccessTransformerApplierVersion());
        });

        configureSetupTasks(runtimeDefinition.getSourceJarTask().flatMap(WithOutput::getOutput), mainSource, runtimeDefinition.getMinecraftDependenciesConfiguration());
    }

    public void neoform() {
        //Accept any version of NeoForm. Aka the latest will always work.
        neoform("+");
    }

    public void neoform(final String neoFormVersion) {
        type = DynamicProjectType.NEO_FORM;

        project.getPlugins().apply(NeoFormProjectPlugin.class);

        final JavaPluginExtension javaPluginExtension = getProject().getExtensions().getByType(JavaPluginExtension.class);
        final SourceSet mainSource = javaPluginExtension.getSourceSets().getByName("main");

        final NeoFormRuntimeExtension neoFormRuntimeExtension = project.getExtensions().getByType(NeoFormRuntimeExtension.class);
        final NeoFormRuntimeDefinition runtimeDefinition = neoFormRuntimeExtension.create(builder -> {
            builder.withNeoFormVersion(neoFormVersion)
                    .withDistributionType(DistributionType.CLIENT);
            
            NeoFormRuntimeUtils.configureDefaultRuntimeSpecBuilder(project, builder);
        });

        configureSetupTasks(runtimeDefinition.getSourceJarTask().flatMap(WithOutput::getOutput), mainSource, runtimeDefinition.getMinecraftDependenciesConfiguration());
    }
    
    public void forge(final String neoFormVersion) {
        forge(
              neoFormVersion,
              project.getLayout().getProjectDirectory().dir("patches"),
              project.getLayout().getProjectDirectory().dir("rejects")
        );
    }
    
    public void forge(final String neoFormVersion, Directory patches, Directory rejects) {
        type = DynamicProjectType.FORGE;
        
        project.getPlugins().apply(PlatformDevProjectPlugin.class);
        
        final JavaPluginExtension javaPluginExtension = getProject().getExtensions().getByType(JavaPluginExtension.class);
        final SourceSet mainSource = javaPluginExtension.getSourceSets().getByName("main");
        
        final PlatformDevRuntimeExtension platformDevRuntimeExtension = project.getExtensions().getByType(PlatformDevRuntimeExtension.class);
        final PlatformDevRuntimeDefinition runtimeDefinition = platformDevRuntimeExtension.create(builder -> {
            builder.withNeoFormVersion(neoFormVersion)
                  .withPatchesDirectory(patches)
                  .withRejectsDirectory(rejects)
                  .withDistributionType(DistributionType.JOINED);
        });
        
        final EnumMap<DistributionType, TaskProvider<? extends WithOutput>> neoformRawJarProviders = new EnumMap<>(DistributionType.class);
        neoformRawJarProviders.put(DistributionType.CLIENT, runtimeDefinition.getClientNeoFormRuntimeDefinition().getRawJarTask());
        neoformRawJarProviders.put(DistributionType.JOINED, runtimeDefinition.getJoinedNeoFormRuntimeDefinition().getRawJarTask());
        neoformRawJarProviders.put(DistributionType.SERVER, runtimeDefinition.getServerNeoFormRuntimeDefinition().getRawJarTask());
        
        final TaskProvider<? extends WithOutput> neoFormSources = runtimeDefinition.getClientNeoFormRuntimeDefinition().getSourceJarTask();
        
        final TaskProvider<SetupProjectFromRuntime> setupTask = configureSetupTasks(runtimeDefinition.getSourceJarTask().flatMap(WithOutput::getOutput), mainSource, runtimeDefinition.getMinecraftDependenciesConfiguration());
        setupTask.configure(task -> task.getShouldLockDirectories().set(false));
        
        final File workingDirectory = getProject().getLayout().getBuildDirectory().dir(String.format("patchgeneration/%s", runtimeDefinition.getSpecification().getIdentifier())).get().getAsFile();
        
        final TaskProvider<? extends WithOutput> packChanges = project.getTasks().register("packForgeChanges", PackZip.class, task -> {
            task.getInputFiles().from(SetupUtils.getSetupSourceTarget(getProject()));
            CommonRuntimeExtension.configureCommonRuntimeTaskParameters(task, runtimeDefinition, workingDirectory);
        });
        
        final TaskProvider<? extends GenerateSourcePatches> createPatches = project.getTasks().register("createSourcePatches", GenerateSourcePatches.class, task -> {
            task.getBase().set(neoFormSources.flatMap(WithOutput::getOutput));
            task.getModified().set(packChanges.flatMap(WithOutput::getOutput));
            
            CommonRuntimeExtension.configureCommonRuntimeTaskParameters(task, runtimeDefinition, workingDirectory);
        });
        
        final TaskProvider<? extends UnpackZip> unpackZip = project.getTasks().register("unpackSourcePatches", UnpackZip.class, task -> {
            task.getInputZip().set(createPatches.flatMap(WithOutput::getOutput));
            task.getUnpackingTarget().set(patches);
            
            CommonRuntimeExtension.configureCommonRuntimeTaskParameters(task, runtimeDefinition, workingDirectory);
        });
        
        final TaskProvider<? extends Jar> compiledJarProvider = project.getTasks().named(
                project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets().getByName("main").getJarTaskName(),
                Jar.class
        );
        
        final EnumMap<DistributionType, TaskProvider<GenerateBinaryPatches>> binaryPatchGenerators = new EnumMap<>(DistributionType.class);
        for (DistributionType distribution : DistributionType.values()) {
            final TaskProvider<? extends WithOutput> cleanProvider = neoformRawJarProviders.get(distribution);
            final TaskProvider<GenerateBinaryPatches> generateBinaryPatchesTask = project.getTasks().register(distribution.createTaskName("generate", "BinaryPatches"), GenerateBinaryPatches.class, task  -> {
                task.getClean().set(cleanProvider.flatMap(WithOutput::getOutput));
                task.getPatched().set(compiledJarProvider.flatMap(Jar::getArchiveFile));
                task.getDistributionType().set(distribution);
                
                task.mustRunAfter(unpackZip);
                task.mustRunAfter(setupTask);
                
                CommonRuntimeExtension.configureCommonRuntimeTaskParameters(task, runtimeDefinition, workingDirectory);
            });
            binaryPatchGenerators.put(distribution, generateBinaryPatchesTask);
        }
        
        final TaskProvider<?> generateBinaryPatches = project.getTasks().register("generateBinaryPatches", task -> {
            binaryPatchGenerators.values().forEach(task::dependsOn);
            task.setGroup("neogradle/runtime/platform");
        });
    }

    private TaskProvider<SetupProjectFromRuntime> configureSetupTasks(Provider<RegularFile> rawJarProvider, SourceSet mainSource, Configuration runtimeDefinition1) {
        final TaskProvider<SetupProjectFromRuntime> projectSetup = project.getTasks().register("setup", SetupProjectFromRuntime.class, task -> {
            task.getSourcesFile().set(rawJarProvider);
        });

        final Configuration implementation = project.getConfigurations().getByName(mainSource.getImplementationConfigurationName());
        runtimeDefinition1.getAllDependencies()
                .forEach(dep -> implementation.getDependencies().add(dep));

        final Project rootProject = project.getRootProject();
        if (!rootProject.getTasks().getNames().contains("setup")) {
            rootProject.getTasks().create("setup");
        }

        rootProject.getTasks().named("setup").configure(task -> task.dependsOn(projectSetup));
        
        return projectSetup;
    }

    @NotNull
    public DynamicProjectType getType() {
        if (type == null)
            throw new IllegalStateException("Project is not configured yet!");
        return type;
    }
}
