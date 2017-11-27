/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser;

import com.google.common.collect.Maps;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint;
import org.gradle.api.internal.artifacts.dependencies.DefaultMutableVersionConstraint;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.PomReader.PomDependencyData;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.data.MavenDependencyKey;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.data.PomDependencyMgt;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.MavenVersionSelectorScheme;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelectorScheme;
import org.gradle.api.internal.component.ArtifactType;
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier;
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector;
import org.gradle.internal.component.external.model.DefaultMutableMavenModuleResolveMetadata;
import org.gradle.internal.component.external.model.GradleDependencyMetadata;
import org.gradle.internal.component.external.model.MavenDependencyDescriptor;
import org.gradle.internal.component.external.model.ModuleDependencyMetadata;
import org.gradle.internal.component.external.model.MutableMavenModuleResolveMetadata;
import org.gradle.internal.resource.local.FileResourceRepository;
import org.gradle.internal.resource.local.LocallyAvailableExternalResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.text.ParseException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.gradle.internal.component.external.model.DefaultMavenModuleResolveMetadata.POM_PACKAGING;

/**
 * This based on a copy of org.apache.ivy.plugins.parser.m2.PomModuleDescriptorParser, but now heavily refactored.
 */
public final class GradlePomModuleDescriptorParser extends AbstractModuleDescriptorParser<MutableMavenModuleResolveMetadata> {
    private static final Logger LOGGER = LoggerFactory.getLogger(GradlePomModuleDescriptorParser.class);
    private static final String DEPENDENCY_IMPORT_SCOPE = "import";
    private final VersionSelectorScheme gradleVersionSelectorScheme;
    private final VersionSelectorScheme mavenVersionSelectorScheme;
    private final ImmutableModuleIdentifierFactory moduleIdentifierFactory;

    public GradlePomModuleDescriptorParser(VersionSelectorScheme gradleVersionSelectorScheme, ImmutableModuleIdentifierFactory moduleIdentifierFactory,  FileResourceRepository fileResourceRepository) {
        super(fileResourceRepository);
        this.gradleVersionSelectorScheme = gradleVersionSelectorScheme;
        mavenVersionSelectorScheme = new MavenVersionSelectorScheme(gradleVersionSelectorScheme);
        this.moduleIdentifierFactory = moduleIdentifierFactory;
    }

    @Override
    protected String getTypeName() {
        return "POM";
    }

    public String toString() {
        return "gradle pom parser";
    }

    private boolean isBom(PomReader pomReader) {
        return pomReader.getDependencies().isEmpty() && !pomReader.getDependencyMgt().isEmpty() && POM_PACKAGING.equals(pomReader.getPackaging());
    }

    protected MutableMavenModuleResolveMetadata doParseDescriptor(DescriptorParseContext parserSettings, LocallyAvailableExternalResource resource, boolean validate) throws IOException, ParseException, SAXException {
        PomReader pomReader = new PomReader(resource, moduleIdentifierFactory);
        GradlePomModuleDescriptorBuilder mdBuilder = new GradlePomModuleDescriptorBuilder(pomReader, gradleVersionSelectorScheme, mavenVersionSelectorScheme, moduleIdentifierFactory);

        doParsePom(parserSettings, mdBuilder, pomReader);

        List<MavenDependencyDescriptor> dependencies = mdBuilder.getDependencies();
        ModuleComponentIdentifier cid = mdBuilder.getComponentIdentifier();
        ModuleVersionIdentifier id = moduleIdentifierFactory.moduleWithVersion(cid.getGroup(), cid.getModule(), cid.getVersion());
        MutableMavenModuleResolveMetadata metadata = new DefaultMutableMavenModuleResolveMetadata(id, cid, dependencies);
        metadata.setStatus(mdBuilder.getStatus());
        if (pomReader.getRelocation() != null) {
            metadata.setPackaging("pom");
            metadata.setRelocated(true);
        } else {
            metadata.setPackaging(pomReader.getPackaging());
            metadata.setRelocated(false);
        }
        return metadata;
    }

    private void doParsePom(DescriptorParseContext parserSettings, GradlePomModuleDescriptorBuilder mdBuilder, PomReader pomReader) throws IOException, SAXException {
        if (pomReader.hasParent()) {
            //Is there any other parent properties?

            ModuleComponentSelector parentId = DefaultModuleComponentSelector.newSelector(
                pomReader.getParentGroupId(),
                pomReader.getParentArtifactId(),
                new DefaultImmutableVersionConstraint(pomReader.getParentVersion()));
            PomReader parentPomReader = parsePomForSelector(parserSettings, parentId, pomReader.getAllPomProperties());
            pomReader.setPomParent(parentPomReader);
        }
        pomReader.resolveGAV();

        String groupId = pomReader.getGroupId();
        String artifactId = pomReader.getArtifactId();
        String version = pomReader.getVersion();
        mdBuilder.setModuleRevId(groupId, artifactId, version);

        ModuleVersionIdentifier relocation = pomReader.getRelocation();
        if (relocation != null) {
            if (groupId != null && artifactId != null && artifactId.equals(relocation.getName()) && groupId.equals(relocation.getGroup())) {
                LOGGER.error("POM relocation to an other version number is not fully supported in Gradle : {} relocated to {}.",
                        mdBuilder.getComponentIdentifier(), relocation);
                LOGGER.warn("Please update your dependency to directly use the correct version '{}'.", relocation);
                LOGGER.warn("Resolution will only pick dependencies of the relocated element.  Artifacts and other metadata will be ignored.");
                PomReader relocatedModule = parsePomForId(parserSettings, DefaultModuleComponentIdentifier.newId(relocation), Maps.<String, String>newHashMap());
                addDependencies(mdBuilder, relocatedModule);
            } else {
                LOGGER.info(mdBuilder.getComponentIdentifier()
                        + " is relocated to " + relocation
                        + ". Please update your dependencies.");
                LOGGER.debug("Relocated module will be considered as a dependency");
                ModuleComponentSelector selector = DefaultModuleComponentSelector.newSelector(relocation.getGroup(), relocation.getName(), new DefaultMutableVersionConstraint(relocation.getVersion()));
                mdBuilder.addDependencyForRelocation(selector);
            }
        } else {
            overrideDependencyMgtsWithImported(parserSettings, pomReader);
            addDependencies(mdBuilder, pomReader);
        }
    }

    private void addDependencies(GradlePomModuleDescriptorBuilder mdBuilder, PomReader pomReader) {
        if (isBom(pomReader)) {
            for (PomDependencyMgt dependencyMgt : pomReader.getDependencyMgt().values()) {
                mdBuilder.addOptionalDependency(dependencyMgt);
            }
        } else {
            for (PomDependencyData dependency : pomReader.getDependencies().values()) {
                mdBuilder.addDependency(dependency);
            }
        }
    }

    /**
     * Overrides existing dependency management information with imported ones if existing.
     *
     * @param parseContext Parse context
     * @param pomReader    POM reader
     * @throws IOException
     * @throws SAXException
     */
    private void overrideDependencyMgtsWithImported(DescriptorParseContext parseContext, PomReader pomReader) throws IOException, SAXException {
        Map<MavenDependencyKey, PomDependencyMgt> importedDependencyMgts = parseImportedDependencyMgts(parseContext, pomReader.parseDependencyMgt());
        pomReader.addImportedDependencyMgts(importedDependencyMgts);
    }

    /**
     * Parses imported dependency management information.
     *
     * @param parseContext          Parse context
     * @param currentDependencyMgts Current dependency management information
     * @return Imported dependency management information
     * @throws IOException
     * @throws SAXException
     */
    private Map<MavenDependencyKey, PomDependencyMgt> parseImportedDependencyMgts(DescriptorParseContext parseContext, Collection<PomDependencyMgt> currentDependencyMgts) throws IOException, SAXException {
        Map<MavenDependencyKey, PomDependencyMgt> importedDependencyMgts = new LinkedHashMap<MavenDependencyKey, PomDependencyMgt>();

        for (PomDependencyMgt currentDependencyMgt : currentDependencyMgts) {
            if (isDependencyImportScoped(currentDependencyMgt)) {
                ModuleComponentSelector importedId = DefaultModuleComponentSelector.newSelector(
                    currentDependencyMgt.getGroupId(),
                    currentDependencyMgt.getArtifactId(),
                    new DefaultMutableVersionConstraint(currentDependencyMgt.getVersion()));
                PomReader importedPom = parsePomForSelector(parseContext, importedId, Maps.<String, String>newHashMap());
                for (Map.Entry<MavenDependencyKey, PomDependencyMgt> entry : importedPom.getDependencyMgt().entrySet()) {
                    if (!importedDependencyMgts.containsKey(entry.getKey())) {
                        importedDependencyMgts.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
        return importedDependencyMgts;
    }

    /**
     * Checks if dependency has scope "import".
     *
     * @param dependencyMgt Dependency management element
     * @return Flag
     */
    private boolean isDependencyImportScoped(PomDependencyMgt dependencyMgt) {
        return DEPENDENCY_IMPORT_SCOPE.equals(dependencyMgt.getScope());
    }

    private PomReader parsePomForId(DescriptorParseContext parseContext, ModuleComponentIdentifier identifier, Map<String, String> childProperties) throws IOException, SAXException {
        return parsePomResource(parseContext, parseContext.getMetaDataArtifact(identifier, ArtifactType.MAVEN_POM), childProperties);
    }

    private PomReader parsePomForSelector(DescriptorParseContext parseContext, ModuleComponentSelector selector, Map<String, String> childProperties) throws IOException, SAXException {
        LocallyAvailableExternalResource localResource = parseContext.getMetaDataArtifact(toDependencyMetadata(selector), ArtifactType.MAVEN_POM);
        return parsePomResource(parseContext, localResource, childProperties);
    }

    private ModuleDependencyMetadata toDependencyMetadata(ModuleComponentSelector selector) {
        return new GradleDependencyMetadata(selector);
    }

    private PomReader parsePomResource(DescriptorParseContext parseContext, LocallyAvailableExternalResource localResource, Map<String, String> childProperties) throws SAXException, IOException {
        PomReader pomReader = new PomReader(localResource, moduleIdentifierFactory, childProperties);
        GradlePomModuleDescriptorBuilder mdBuilder = new GradlePomModuleDescriptorBuilder(pomReader, gradleVersionSelectorScheme, mavenVersionSelectorScheme, moduleIdentifierFactory);
        doParsePom(parseContext, mdBuilder, pomReader);
        return pomReader;
    }
}
