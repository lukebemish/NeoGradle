/*
 * ForgeGradle
 * Copyright (C) 2018 Forge Development LLC
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package net.minecraftforge.gradle.common.extensions;

import groovy.lang.Closure;
import groovy.lang.GroovyObjectSupport;
import net.minecraftforge.gradle.common.util.IConfigurableObject;
import net.minecraftforge.gradle.common.runtime.naming.NamingChannelProvider;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;

import javax.inject.Inject;

public abstract class MinecraftExtension extends GroovyObjectSupport implements IConfigurableObject<MinecraftExtension> {

    private final Project project;
    private final FilesWithEntriesExtension accessTransformers;
    private final MappingsExtension mappings;
    private final NamedDomainObjectContainer<NamingChannelProvider> namingChannelProviders;

    @Inject
    public MinecraftExtension(final Project project) {
        this.project = project;
        this.accessTransformers = project.getObjects().newInstance(FilesWithEntriesExtension.class, project);
        this.mappings = project.getObjects().newInstance(MappingsExtension.class, project, this);
        this.namingChannelProviders = project.getObjects().domainObjectContainer(NamingChannelProvider.class, name -> project.getObjects().newInstance(NamingChannelProvider.class, project, name));
    }

    public Project getProject() {
        return project;
    }

    public NamedDomainObjectContainer<NamingChannelProvider> getNamingChannelProviders() {
        return namingChannelProviders;
    }

    public MappingsExtension getMappings() {
        return mappings;
    }

    public void mappings(Closure<?> closure) {
        mappings.configure(closure);
    }

    public FilesWithEntriesExtension getAccessTransformers() {
        return this.accessTransformers;
    }
}