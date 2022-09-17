package net.minecraftforge.gradle.mcp.runtime.extensions;

import net.minecraftforge.gradle.common.config.McpConfigConfigurationSpecV1;
import net.minecraftforge.gradle.common.config.McpConfigConfigurationSpecV2;
import net.minecraftforge.gradle.common.util.Artifact;
import net.minecraftforge.gradle.common.util.FileUtils;
import net.minecraftforge.gradle.common.util.FileWrapper;
import net.minecraftforge.gradle.common.util.MinecraftRepo;
import net.minecraftforge.gradle.mcp.McpExtension;
import net.minecraftforge.gradle.mcp.runtime.McpRuntime;
import net.minecraftforge.gradle.mcp.runtime.spec.McpRuntimeSpec;
import net.minecraftforge.gradle.mcp.runtime.spec.builder.McpRuntimeSpecBuilder;
import net.minecraftforge.gradle.mcp.runtime.tasks.*;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public abstract class McpRuntimeExtension {
    private static final Pattern OUTPUT_REPLACE_PATTERN = Pattern.compile("^\\{(\\w+)Output}$");

    private final Project project;

    @Inject
    public McpRuntimeExtension(Project project) {
        this.project = project;

        this.getDefaultSide().convention("joined");
        this.getDefaultVersion().convention(
                project.provider(() -> project.getExtensions().getByType(McpExtension.class))
                        .flatMap(McpExtension::getConfig)
                        .map(Artifact::getVersion)
        );
    }

    public Project getProject() {
        return project;
    }

    public McpRuntime setup(final Action<McpRuntimeSpecBuilder> configurator) {
        final McpRuntimeSpecBuilder builder = McpRuntimeSpecBuilder.from(project);
        builder.withSide(getDefaultSide().get());
        configurator.execute(builder);
        final McpRuntimeSpec spec = builder.build();

        final Dependency mcpConfigDependency = getProject().getDependencies().create("de.oceanlabs.mcp:mcp_config:" + spec.mcpVersion() + "@zip");
        final Configuration mcpDownloadConfiguration = getProject().getConfigurations().detachedConfiguration(mcpConfigDependency);
        final ResolvedConfiguration resolvedConfiguration = mcpDownloadConfiguration.getResolvedConfiguration();
        final File mcpZipFile = resolvedConfiguration.getFiles().iterator().next();

        final File mcpDirectory = project.getLayout().getBuildDirectory().dir("mcp").get().getAsFile();
        final File unpackedMcpZipDirectory = new File(mcpDirectory, "unpacked");

        FileUtils.unzip(mcpZipFile, unpackedMcpZipDirectory);

        final File mcpConfigFile = new File(unpackedMcpZipDirectory, "config.json");
        final McpConfigConfigurationSpecV2 mcpConfig = McpConfigConfigurationSpecV2.get(mcpConfigFile);

        final Map<String, FileWrapper> data = buildDataMap(mcpConfig, spec.side(), unpackedMcpZipDirectory);

        final List<McpConfigConfigurationSpecV1.Step> steps = mcpConfig.getSteps(spec.side());
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Unknown side: " + spec.side() + " For Config: " + mcpZipFile);
        }

        final LinkedHashMap<String, McpRuntimeTask> tasks = new LinkedHashMap<>();
        for (McpConfigConfigurationSpecV1.Step step : steps) {
            Optional<Provider<File>> adaptedInput = Optional.empty();

            if (step.getName().equals("decompile")) {
                final String inputArgumentMarker = step.getValue("input");
                final Provider<File> inputArtifact = getInputForTaskFrom(inputArgumentMarker, tasks);

                if (spec.preDecompileTaskTreeModifier() != null) {
                    final McpRuntimeTask modifiedTree = spec.preDecompileTaskTreeModifier().adapt(spec, inputArtifact);
                    adaptedInput = Optional.of(modifiedTree.getOutputFile().getAsFile());
                }
            }

            McpRuntimeTask task = createBuiltIn(spec, mcpConfig, step, tasks, adaptedInput);

            if (task == null) {
                McpConfigConfigurationSpecV1.Function function = mcpConfig.getFunction(step.getType());
                if (function == null) {
                    throw new IllegalArgumentException("Invalid MCP Config, Unknown function step type: %s File: %s".formatted(step.getType(), mcpConfig));
                }

                task = createExecute(spec, step, function);
            }

            tasks.put(step.getName(), task);

            task.getArguments().set(buildArguments(step, tasks));
            task.getData().set(data);
        }

        return new McpRuntime(spec, tasks);
    }

    public AccessTransformerTask createAt(McpRuntimeSpec runtimeSpec, List<File> files, Collection<String> data) {
        return getProject().getTasks().register(buildTaskName(runtimeSpec, "accessTransformer"), AccessTransformerTask.class, task -> {
            task.getAdditionalTransformers().addAll(data);
            task.getTransformers().plus(project.files(files.toArray()));
        }).get();
    }

    /**
     * Internal Use Only
     * Non-Public API, Can be changed at any time.
     */
    public SideAnnotationStripperTask createSAS(McpRuntimeSpec spec, List<File> files, Collection<String> data) {
        return getProject().getTasks().register(buildTaskName(spec, "sideAnnotationStripper"), SideAnnotationStripperTask.class, task -> {
            task.getAdditionalDataEntries().addAll(data);
            task.getDataFiles().plus(project.files(files.toArray()));
        }).get();
    }

    public abstract Property<String> getDefaultSide();

    public abstract Property<String> getDefaultVersion();

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @Nullable
    private McpRuntimeTask createBuiltIn(final McpRuntimeSpec runtimeSpec, McpConfigConfigurationSpecV2 mcpConfigV2, McpConfigConfigurationSpecV1.Step step, final Map<String, McpRuntimeTask> tasks, final Optional<Provider<File>> adaptedInput) {
        switch (step.getType()) {
            case "downloadManifest":
                return getProject().getTasks().register(buildTaskName(runtimeSpec, step.getName()), DownloadFileTask.class, task -> {
                    task.getOutputFileName().set("manifest.json");
                    task.getDownloadInfo().set(new DownloadFileTask.DownloadInfo(MinecraftRepo.MANIFEST_URL, null, "unknown", null, null));
                }).get();
            case "downloadJson":
                return getProject().getTasks().register(buildTaskName(runtimeSpec, step.getName()), DownloadVersionJsonTask.class, task -> {
                    task.getDownloadedManifest().fileProvider(getTaskInputFor(tasks, step, "downloadManifest", adaptedInput));
                }).get();
            case "downloadClient":
                return getProject().getTasks().register(buildTaskName(runtimeSpec, step.getName()), DownloadCoreTask.class, task -> {
                    task.getDownloadedVersionJson().fileProvider(getTaskInputFor(tasks, step, "downloadJson", adaptedInput));
                    task.getArtifact().set("client");
                    task.getExtension().set("jar");
                }).get();
            case "downloadServer":
                return getProject().getTasks().register(buildTaskName(runtimeSpec, step.getName()), DownloadCoreTask.class, task -> {
                    task.getDownloadedVersionJson().fileProvider(getTaskInputFor(tasks, step, "downloadJson", adaptedInput));
                    task.getArtifact().set("server");
                    task.getExtension().set("jar");
                }).get();
            case "strip":
                return getProject().getTasks().register(buildTaskName(runtimeSpec, step.getName()), StripJarTask.class, task -> {
                    task.getInput().fileProvider(getTaskInputFor(tasks, step));
                }).get();
            case "listLibraries":
                return getProject().getTasks().register(buildTaskName(runtimeSpec, step.getName()), ListLibrariesTask.class, task -> {
                    task.getServerBundleFile().fileProvider(getTaskInputFor(tasks, step, "downloadServer", adaptedInput));
                }).get();
            case "inject":
                return getProject().getTasks().register(buildTaskName(runtimeSpec, step.getName()), InjectTask.class, task -> {
                    task.getInjectionSource().fileProvider(getTaskInputFor(tasks, step));
                }).get();
            case "patch":
                return getProject().getTasks().register(buildTaskName(runtimeSpec, step.getName()), PatchTask.class, task -> {
                    task.getInput().fileProvider(getTaskInputFor(tasks, step));
                }).get();
        }
        if (mcpConfigV2.getSpec() >= 2) {
            switch (step.getType()) {
                case "downloadClientMappings":
                    return getProject().getTasks().register(buildTaskName(runtimeSpec, step.getName()), DownloadCoreTask.class, task -> {
                        task.getDownloadedVersionJson().fileProvider(getTaskInputFor(tasks, step, "downloadClientMappings", adaptedInput));
                        task.getArtifact().set("client_mappings");
                        task.getExtension().set("txt");
                    }).get();
                case "downloadServerMappings":
                    return getProject().getTasks().register(buildTaskName(runtimeSpec, step.getName()), DownloadCoreTask.class, task -> {
                        task.getDownloadedVersionJson().fileProvider(getTaskInputFor(tasks, step, "downloadServerMappings", adaptedInput));
                        task.getArtifact().set("server_mappings");
                        task.getExtension().set("txt");
                    }).get();
            }
        }

        return null;
    }

    private McpRuntimeTask createExecute(final McpRuntimeSpec runtimeSpec, final McpConfigConfigurationSpecV1.Step step, final McpConfigConfigurationSpecV1.Function function) {
        return getProject().getTasks().register(buildTaskName(runtimeSpec, step.getName()), ExecutingMcpRuntimeTask.class, task -> {
            task.getExecutingArtifact().set(function.getVersion());
            task.getJvmArguments().addAll(function.getJvmArgs());
            task.getProgramArguments().addAll(function.getArgs());
        }).get();
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private Provider<File> getTaskInputFor(final Map<String, McpRuntimeTask> tasks, McpConfigConfigurationSpecV1.Step step, final String defaultInputTask, final Optional<Provider<File>> adaptedInput) {
        if (adaptedInput.isPresent()) {
            return adaptedInput.get();
        }

        if (step.getValue("input") == null) {
            return getInputForTaskFrom("{" + defaultInputTask + "Output}", tasks);
        }

        return getInputForTaskFrom(step.getValue("input"), tasks);
    }

    private Provider<File> getTaskInputFor(final Map<String, McpRuntimeTask> tasks, McpConfigConfigurationSpecV1.Step step) {
        return getInputForTaskFrom(step.getValue("input"), tasks);
    }

    private Provider<File> getInputForTaskFrom(final String inputValue, Map<String, McpRuntimeTask> tasks) {
        Matcher matcher = OUTPUT_REPLACE_PATTERN.matcher(inputValue);
        if (!matcher.find()) {
            return getProject().provider(() -> new File(inputValue));
        }

        String stepName = matcher.group(1);
        if (stepName != null) {
            return tasks.computeIfAbsent(inputValue, value -> {
                throw new IllegalArgumentException("Could not find mcp task for input: " + value);
            }).getOutputFile().getAsFile();
        }

        throw new IllegalStateException("The string '" + inputValue + "' did not return a valid substitution match!");
    }

    private Map<String, String> buildArguments(McpConfigConfigurationSpecV1.Step step, final Map<String, McpRuntimeTask> tasks) {
        final Map<String, String> arguments = new HashMap<>();

        step.getValues().forEach((key, value) -> {
            if (value.startsWith("{") && value.endsWith("}")) {
                arguments.put(key, getInputForTaskFrom(value, tasks).get().getAbsolutePath());
            } else {
                arguments.put(key, value);
            }
        });

        return arguments;
    }

    private String buildTaskName(final McpRuntimeSpec runtimeSpec, final String defaultName) {
        if (runtimeSpec.namePrefix().isEmpty())
            return defaultName;

        return runtimeSpec.namePrefix() + StringUtils.capitalize(defaultName);
    }

    @SuppressWarnings("unchecked")
    @NotNull
    private Map<String, FileWrapper> buildDataMap(McpConfigConfigurationSpecV2 mcpConfig, final String side, final File unpackedMcpDirectory) {
        return mcpConfig.getData().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                e -> new FileWrapper(new File(unpackedMcpDirectory, e.getValue() instanceof Map ? ((Map<String, String>) e.getValue()).get(side) : (String) e.getValue()))
        ));
    }

}