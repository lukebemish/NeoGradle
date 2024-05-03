package net.neoforged.gradle.dsl.common.attributes

import groovy.transform.CompileStatic
import org.gradle.api.Named
import org.gradle.api.attributes.Attribute

@CompileStatic
interface OperatingSystem extends Named {
    Attribute<OperatingSystem> OPERATING_SYSTEM_ATTRIBUTE = Attribute.of("net.neoforged.neogradle.operatingsystem", OperatingSystem.class)

    String WINDOWS = "windows"
    String LINUX = "linux"
    String OSX = "osx"
}
