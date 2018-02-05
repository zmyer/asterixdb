/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hyracks.maven.license;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hyracks.maven.license.project.LicensedProjects;
import org.apache.hyracks.maven.license.project.Project;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.inheritance.ModelInheritanceAssembler;

public abstract class LicenseMojo extends AbstractMojo {

    @Parameter
    protected List<Override> overrides = new ArrayList<>();

    @Parameter
    protected String[] models = new String[0];

    @Parameter
    protected List<LicenseSpec> licenses = new ArrayList<>();

    @Parameter
    protected Set<String> excludedScopes = new HashSet<>();

    @Parameter
    protected List<String> excludes = new ArrayList<>();

    @Parameter
    protected List<DependencySet> dependencySets = new ArrayList<>();

    @Parameter(defaultValue = "${project}", readonly = true)
    protected MavenProject project;

    @Parameter(property = "localRepository", required = true, readonly = true)
    private ArtifactRepository localRepository;

    @Parameter(property = "project.remoteArtifactRepositories", required = true, readonly = true)
    private List<ArtifactRepository> remoteRepositories;

    @Component(role = MavenProjectBuilder.class)
    protected MavenProjectBuilder projectBuilder;

    @Component
    private ModelInheritanceAssembler assembler;

    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    protected MavenSession session;

    @Component
    protected ArtifactResolver artifactResolver;

    @Parameter(required = true)
    private String location;

    @Parameter(required = true)
    protected File licenseDirectory;

    private Map<String, MavenProject> projectCache = new HashMap<>();

    private Map<String, Model> supplementModels;

    private List<Pattern> excludePatterns;

    Map<String, LicenseSpec> urlToLicenseMap = new HashMap<>();
    Map<String, LicensedProjects> licenseMap = new TreeMap<>();

    protected Map<String, LicensedProjects> getLicenseMap() {
        return licenseMap;
    }

    protected void init() throws MojoExecutionException, MalformedURLException, ProjectBuildingException {
        excludedScopes.add("system");
        excludePatterns = compileExcludePatterns();
        supplementModels = SupplementalModelHelper.loadSupplements(getLog(), models);
        buildUrlLicenseMap();
    }

    protected void addDependenciesToLicenseMap() throws ProjectBuildingException {
        Map<MavenProject, List<Pair<String, String>>> dependencyLicenseMap = gatherDependencies();
        dependencyLicenseMap.forEach((depProject, value) -> {
            Set<String> locations = dependencySets.isEmpty() ? Collections.singleton(location)
                    : getIncludedLocation(depProject.getArtifact());
            if (isExcluded(depProject.getArtifact())) {
                getLog().debug("skipping " + depProject + " [excluded]");
            } else if (locations.isEmpty()) {
                getLog().debug("skipping " + depProject + " [not included in dependency sets]");
            } else {
                for (String depLocation : locations) {
                    addDependencyToLicenseMap(depProject, value, depLocation);
                }
            }
        });
    }

    private int getLicenseMetric(String url) {
        LicenseSpec licenseSpec = urlToLicenseMap.get(url);
        return licenseSpec != null ? licenseSpec.getMetric() : LicenseSpec.UNDEFINED_LICENSE_METRIC;
    }

    private void addDependencyToLicenseMap(MavenProject depProject, List<Pair<String, String>> depLicenses,
            String depLocation) {
        final String depGav = toGav(depProject);
        getLog().debug("adding " + depGav + ", location: " + depLocation);
        final MutableBoolean usedMetric = new MutableBoolean(false);
        if (depLicenses.size() > 1) {
            Collections.sort(depLicenses, (o1, o2) -> {
                final int metric1 = getLicenseMetric(o1.getLeft());
                final int metric2 = getLicenseMetric(o2.getLeft());
                usedMetric.setValue(usedMetric.booleanValue() || metric1 != LicenseSpec.UNDEFINED_LICENSE_METRIC
                        || metric2 != LicenseSpec.UNDEFINED_LICENSE_METRIC);
                return Integer.compare(metric1, metric2);
            });
            if (usedMetric.booleanValue()) {
                getLog().info("Multiple licenses for " + depGav + ": " + depLicenses + "; taking lowest metric: "
                        + depLicenses.get(0));
            } else {
                getLog().warn("Multiple licenses for " + depGav + ": " + depLicenses + "; taking first listed: "
                        + depLicenses.get(0));
            }
        } else if (depLicenses.isEmpty()) {
            getLog().info("no license defined in model for " + depGav);
            depLicenses.add(new ImmutablePair<>("MISSING_LICENSE", null));
        }
        Pair<String, String> key = depLicenses.get(0);
        String licenseUrl = key.getLeft();
        final String displayName = key.getRight();
        if (!urlToLicenseMap.containsKey(licenseUrl)) {
            // assuming we've not already mapped it, annotate the URL with artifact info, if not an actual URL
            try {
                getLog().debug("- URL: " + new URL(licenseUrl));
                // life is good
            } catch (MalformedURLException e) {
                // we encounter this a lot.  Log a warning, and use an annotated key
                final String fakeLicenseUrl = depGav.replaceAll(":", "--") + "_" + licenseUrl;
                getLog().info("- URL for " + depGav + " is malformed: " + licenseUrl + "; using: " + fakeLicenseUrl);
                licenseUrl = fakeLicenseUrl;
            }
        }
        addProject(new Project(depProject, depLocation, depProject.getArtifact().getFile()),
                new LicenseSpec(licenseUrl, displayName), true);
    }

    protected void addProject(Project project, LicenseSpec spec, boolean additive) {
        String licenseUrl = spec.getUrl();
        LicenseSpec license = urlToLicenseMap.get(licenseUrl);
        if (license == null) {
            license = spec;
            urlToLicenseMap.put(licenseUrl, license);
            for (String alias : license.getAliasUrls()) {
                if (!urlToLicenseMap.containsKey(alias)) {
                    urlToLicenseMap.put(alias, license);
                }
            }
        } else if (license.getDisplayName() == null && spec.getDisplayName() != null) {
            getLog().info("Propagating license name from " + project.gav() + ": " + spec.getDisplayName());
            license.setDisplayName(spec.getDisplayName());
        }
        licenseUrl = license.getUrl();
        LicensedProjects entry = licenseMap.get(licenseUrl);
        if (entry == null) {
            entry = new LicensedProjects(license);
            licenseMap.put(licenseUrl, entry);
        }
        if (additive || entry.getProjects().contains(project)) {
            entry.addProject(project);
        }
    }

    private void buildUrlLicenseMap() throws MojoExecutionException {
        for (LicenseSpec license : licenses) {
            if (urlToLicenseMap.put(license.getUrl(), license) != null) {
                throw new MojoExecutionException("Duplicate URL mapping: " + license.getUrl());
            }
            for (String alias : license.getAliasUrls()) {
                if (urlToLicenseMap.put(alias, license) != null) {
                    throw new MojoExecutionException("Duplicate URL mapping: " + alias);
                }
            }
        }
    }

    protected Map<MavenProject, List<Pair<String, String>>> gatherDependencies() throws ProjectBuildingException {
        Map<MavenProject, List<Pair<String, String>>> dependencyLicenseMap = new HashMap<>();
        Map<String, MavenProject> dependencyGavMap = new HashMap<>();

        gatherProjectDependencies(project, dependencyLicenseMap, dependencyGavMap);
        for (Override override : overrides) {
            String gav = override.getGav();
            MavenProject dep = dependencyGavMap.get(gav);
            if (dep == null) {
                getLog().warn("Unused override dependency " + gav + "; ignoring...");
            } else {
                final List<Pair<String, String>> newLicense =
                        Collections.singletonList(new ImmutablePair<>(override.getUrl(), override.getName()));
                List<Pair<String, String>> prevLicense = dependencyLicenseMap.put(dep, newLicense);
                getLog().warn("license list for " + toGav(dep) + " changed with <override>; was: " + prevLicense
                        + ", now: " + newLicense);
            }
        }
        return dependencyLicenseMap;
    }

    private void gatherProjectDependencies(MavenProject project,
            Map<MavenProject, List<Pair<String, String>>> dependencyLicenseMap,
            Map<String, MavenProject> dependencyGavMap) throws ProjectBuildingException {
        final Set dependencyArtifacts = project.getArtifacts();
        if (dependencyArtifacts != null) {
            for (Object depArtifactObj : dependencyArtifacts) {
                final Artifact depArtifact = (Artifact) depArtifactObj;
                if (!excludedScopes.contains(depArtifact.getScope())) {
                    MavenProject dep = resolveDependency(depArtifact);
                    dep.setArtifact(depArtifact);
                    dependencyGavMap.put(toGav(dep), dep);
                    List<Pair<String, String>> licenseUrls = new ArrayList<>();
                    for (Object license : dep.getLicenses()) {
                        final License license1 = (License) license;
                        String url = license1.getUrl() != null ? license1.getUrl()
                                : (license1.getName() != null ? license1.getName() : "LICENSE_EMPTY_NAME_URL");
                        licenseUrls.add(new ImmutablePair<>(url, license1.getName()));
                    }
                    dependencyLicenseMap.put(dep, licenseUrls);
                }
            }
        }
    }

    protected MavenProject resolveDependency(Artifact depObj) throws ProjectBuildingException {
        String key = depObj.getGroupId() + ":" + depObj.getArtifactId() + ":" + depObj.getVersion();

        MavenProject depProj = projectCache.get(key);

        if (depProj == null) {
            try {
                depProj = projectBuilder.buildFromRepository(depObj, remoteRepositories, localRepository, false);
            } catch (ProjectBuildingException e) {
                throw new ProjectBuildingException(key, "Error creating dependent artifacts", e);
            }

            Model supplement = supplementModels
                    .get(SupplementalModelHelper.generateSupplementMapKey(depObj.getGroupId(), depObj.getArtifactId()));
            if (supplement != null) {
                Model merged = SupplementalModelHelper.mergeModels(assembler, depProj.getModel(), supplement);
                Set<String> origLicenses =
                        depProj.getModel().getLicenses().stream().map(License::getUrl).collect(Collectors.toSet());
                Set<String> newLicenses =
                        merged.getLicenses().stream().map(License::getUrl).collect(Collectors.toSet());
                if (!origLicenses.equals(newLicenses)) {
                    getLog().warn("license list for " + toGav(depProj) + " changed with supplemental model; was: "
                            + origLicenses + ", now: " + newLicenses);
                }
                depProj = new MavenProject(merged);
                depProj.setArtifact(depObj);
                depProj.setVersion(depObj.getVersion());
            }
            depProj.getArtifact().setScope(depObj.getScope());
            projectCache.put(key, depProj);
        }
        return depProj;
    }

    private String toGav(MavenProject dep) {
        return dep.getGroupId() + ":" + dep.getArtifactId() + ":" + dep.getVersion();
    }

    protected List<Pattern> compileExcludePatterns() {
        List<Pattern> patterns = new ArrayList<>();
        for (String exclude : excludes) {
            patterns.add(compileGAWildcardPattern(exclude));
        }
        return patterns;
    }

    public static Pattern compileGAWildcardPattern(String spec) {
        return Pattern.compile(spec.replace(".", "\\.").replace("*", "[^:]*"));
    }

    protected boolean isExcluded(Artifact artifact) {
        for (Pattern exclude : excludePatterns) {
            if (exclude.matcher(artifact.getGroupId() + ":" + artifact.getArtifactId()).matches()) {
                return true;
            }
        }
        return false;
    }

    protected Set<String> getIncludedLocation(Artifact artifact) {
        Set<String> locations = new TreeSet<>();
        for (DependencySet set : dependencySets) {
            for (Pattern include : set.getPatterns()) {
                if (include.matcher(artifact.getGroupId() + ":" + artifact.getArtifactId()).matches()) {
                    locations.add(set.getLocation());
                }
            }
        }
        return locations;
    }

    public MavenSession getSession() {
        return session;
    }

    public ArtifactResolver getArtifactResolver() {
        return artifactResolver;
    }
}
