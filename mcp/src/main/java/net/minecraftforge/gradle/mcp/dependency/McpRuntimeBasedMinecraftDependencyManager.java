package net.minecraftforge.gradle.mcp.dependency;

import net.minecraftforge.gradle.common.extensions.dependenvy.replacement.DependencyReplacementExtension;
import net.minecraftforge.gradle.common.extensions.dependenvy.replacement.DependencyReplacementResult;
import net.minecraftforge.gradle.common.util.ArtifactSide;
import net.minecraftforge.gradle.common.util.Utils;
import net.minecraftforge.gradle.mcp.runtime.McpRuntimeDefinition;
import net.minecraftforge.gradle.mcp.runtime.extensions.McpRuntimeExtension;
import net.minecraftforge.gradle.mcp.util.McpRuntimeUtils;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyArtifact;
import org.gradle.api.artifacts.ExternalModuleDependency;

import java.util.Locale;
import java.util.Optional;

public final class McpRuntimeBasedMinecraftDependencyManager {
    private static final McpRuntimeBasedMinecraftDependencyManager INSTANCE = new McpRuntimeBasedMinecraftDependencyManager();

    private McpRuntimeBasedMinecraftDependencyManager() {
    }

    public static McpRuntimeBasedMinecraftDependencyManager getInstance() {
        return INSTANCE;
    }

    public void apply(final Project project) {
        final DependencyReplacementExtension dependencyReplacer = project.getExtensions().getByType(DependencyReplacementExtension.class);
        dependencyReplacer.getReplacementHandlers().add(context -> {
            if (isNotAMatchingDependency(context.dependency())) {
                return Optional.empty();
            }

            if (!(context.dependency() instanceof ExternalModuleDependency externalModuleDependency)) {
                return Optional.empty();
            }

            final McpRuntimeDefinition runtimeDefinition = buildMcpRuntimeFromDependency(project, context.project(), externalModuleDependency);
            return Optional.of(
                    new DependencyReplacementResult(
                            project,
                            name -> McpRuntimeUtils.buildTaskName(runtimeDefinition, name),
                            runtimeDefinition.sourceJarTask(),
                            runtimeDefinition.rawJarTask(),
                            runtimeDefinition.minecraftDependenciesConfiguration(),
                            builder -> builder.withVersion(runtimeDefinition.spec().mcpVersion())
                    )
            );
        });
    }

    private boolean isNotAMatchingDependency(final Dependency dependencyToCheck) {
        if (dependencyToCheck instanceof ExternalModuleDependency externalModuleDependency) {
            return externalModuleDependency.getGroup() == null || !externalModuleDependency.getGroup().equals("net.minecraft") || !isSupportedSide(dependencyToCheck) || !hasMatchingArtifact(externalModuleDependency);
        }

        return true;
    }

    private boolean isSupportedSide(final Dependency dependency) {
        return dependency.getName().equals("mcp_client") ||
                dependency.getName().equals("mcp_server") ||
                dependency.getName().equals("mcp_joined");
    }

    private boolean hasMatchingArtifact(ExternalModuleDependency externalModuleDependency) {
        if (externalModuleDependency.getArtifacts().isEmpty()){
            return true;
        }

        return hasSourcesArtifact(externalModuleDependency);
    }

    private static boolean hasSourcesArtifact(ExternalModuleDependency externalModuleDependency) {
        if (externalModuleDependency.getArtifacts().size() != 1) {
            return false;
        }

        final DependencyArtifact artifact = externalModuleDependency.getArtifacts().iterator().next();
        return artifact.getClassifier().equals("sources") && artifact.getExtension().equals("jar");
    }


    private static McpRuntimeDefinition buildMcpRuntimeFromDependency(Project project, Project configureProject, ExternalModuleDependency dependency) {
        final McpRuntimeExtension runtimeExtension = project.getExtensions().getByType(McpRuntimeExtension.class);
        return runtimeExtension.registerOrGet(builder -> {
            builder.configureFromProject(configureProject);
            builder.withSide(ArtifactSide.valueOf(dependency.getName().replace("mcp_", "").toUpperCase(Locale.ROOT)));
            builder.withMcpVersion(dependency.getVersion());
            builder.withName("dependency%s".formatted(Utils.capitalize(dependency.getName().replace("mcp_", ""))));
        });
    }


}