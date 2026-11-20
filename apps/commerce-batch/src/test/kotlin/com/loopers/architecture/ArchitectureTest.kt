package com.loopers.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchitectureTest {
    @Test
    fun `ranking respects layers including shared module contracts and annotations`() {
        val classes = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.loopers")
        listOf("domain", "application", "interfaces", "infrastructure").forEach { layer ->
            assertTrue(classes.any { it.packageName.split('.').contains(layer) }, "missing $layer")
        }

        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..application..", "..interfaces..", "..infrastructure..")
            .check(classes)
        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..interfaces..", "..infrastructure..", "org.springframework.jdbc..")
            .check(classes)
        noClasses().that().resideInAPackage("..interfaces..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..").check(classes)
        noClasses().that().resideInAPackage("..infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage("..application..", "..interfaces..").check(classes)
    }
}
