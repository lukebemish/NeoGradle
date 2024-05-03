package net.neoforged.gradle.dsl.common.attributes

import groovy.transform.CompileStatic
import org.gradle.api.Named
import org.gradle.api.attributes.Attribute

@CompileStatic
interface Side extends Named {
    Attribute<Side> SIDE_ATTRIBUTE = Attribute.of("net.neoforged.neogradle.side", Side.class)

    String SERVER = "server"
    String CLIENT = "client"
    String JOINED = "joined"
}
