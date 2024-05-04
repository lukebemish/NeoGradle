package net.neoforged.gradle.dsl.common.attributes

import groovy.transform.CompileStatic
import org.gradle.api.Named
import org.gradle.api.attributes.Attribute

@CompileStatic
interface Distribution extends Named {
    Attribute<Distribution> SIDE_ATTRIBUTE = Attribute.of("net.neoforged.distribution", Distribution.class)

    String SERVER = "server"
    String CLIENT = "client"
}
