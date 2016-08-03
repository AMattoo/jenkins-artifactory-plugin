package org.jfrog.hudson.pipeline.types;

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jfrog.build.api.Artifact;
import org.jfrog.build.api.Dependency;
import org.jfrog.build.api.builder.ArtifactBuilder;
import org.jfrog.build.api.dependency.BuildDependency;
import org.jfrog.build.api.search.AqlSearchResult;
import org.jfrog.build.extractor.clientConfiguration.client.ArtifactoryBuildInfoClient;
import org.jfrog.build.extractor.clientConfiguration.client.ArtifactoryDependenciesClient;
import org.jfrog.hudson.ArtifactoryServer;
import org.jfrog.hudson.CredentialsConfig;
import org.jfrog.hudson.pipeline.ArtifactoryPipelineConfigurator;
import org.jfrog.hudson.pipeline.PipelineBuildInfoDeployer;
import org.jfrog.hudson.pipeline.docker.DockerUtils;
import org.jfrog.hudson.pipeline.docker.proxy.ProxyBuildInfoCallback;
import org.jfrog.hudson.util.BuildUniqueIdentifierHelper;
import org.jfrog.hudson.util.CredentialManager;

import java.io.IOException;
import java.io.Serializable;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Created by romang on 4/26/16.
 */
public class BuildInfo implements Serializable, ProxyBuildInfoCallback {

    private String buildName;
    private String buildNumber;
    private Date startDate;
    private BuildRetention retention;

    private Map<Artifact, Artifact> deployedArtifacts = new HashMap<Artifact, Artifact>();
    private List<BuildDependency> buildDependencies = new ArrayList<BuildDependency>();
    private Map<Dependency, Dependency> publishedDependencies = new HashMap<Dependency, Dependency>();
    private Env env = new Env();

    private Docker docker = new Docker(this);

    public BuildInfo(Run build) {
        this.buildName = BuildUniqueIdentifierHelper.getBuildName(build);
        this.buildNumber = BuildUniqueIdentifierHelper.getBuildNumber(build);
        this.startDate = new Date(build.getStartTimeInMillis());
        this.retention = new BuildRetention();
    }

    @Whitelisted
    public void setName(String name) {
        this.buildName = name;
    }

    @Whitelisted
    public void setNumber(String number) {
        this.buildNumber = number;
    }

    @Whitelisted
    public String getName() {
        return buildName;
    }

    @Whitelisted
    public String getNumber() {
        return buildNumber;
    }

    @Whitelisted
    public Date getStartDate() {
        return startDate;
    }

    @Whitelisted
    public void setStartDate(Date date) {
        this.startDate = date;
    }

    @Whitelisted
    public void append(BuildInfo other) {
        this.deployedArtifacts.putAll(other.deployedArtifacts);
        this.publishedDependencies.putAll(other.publishedDependencies);
        this.buildDependencies.addAll(other.buildDependencies);
        this.docker.getDockerLayersDependencies().addAll(other.docker.getDockerLayersDependencies());
        this.env.append(other.getEnv());
    }

    @Whitelisted
    public Env getEnv() {
        return env;
    }

    @Whitelisted
    public Docker getDocker() {
        return docker;
    }

    @Whitelisted
    public BuildRetention getRetention() {
        return retention;
    }

    @Whitelisted
    public void retention(Map<String, Object> retentionArguments) throws Exception {
        Set<String> retentionArgumentsSet = retentionArguments.keySet();
        List<String> keysAsList = Arrays.asList(new String[]{"maxDays", "maxBuilds", "deleteBuildArtifacts", "doNotDiscardBuilds"});
        if (!keysAsList.containsAll(retentionArgumentsSet)) {
            throw new IllegalArgumentException("Only the following arguments are allowed: " + keysAsList.toString());
        }

        final ObjectMapper mapper = new ObjectMapper();
        this.retention = mapper.convertValue(retentionArguments, BuildRetention.class);
    }

    protected void appendDeployedArtifacts(List<Artifact> artifacts) {
        if (artifacts == null) {
            return;
        }
        for (Artifact artifact : artifacts) {
            deployedArtifacts.put(artifact, artifact);
        }
    }

    protected void appendBuildDependencies(List<BuildDependency> dependencies) {
        if (dependencies == null) {
            return;
        }
        buildDependencies.addAll(dependencies);
    }

    protected void appendPublishedDependencies(List<Dependency> dependencies) {
        if (dependencies == null) {
            return;
        }
        for (Dependency dependency : dependencies) {
            publishedDependencies.put(dependency, dependency);
        }
    }

    protected Map<Artifact, Artifact> getDeployedArtifacts() {
        return deployedArtifacts;
    }

    protected List<BuildDependency> getBuildDependencies() {
        return buildDependencies;
    }

    protected Map<Dependency, Dependency> getPublishedDependencies() {
        return publishedDependencies;
    }

    protected Map<String, String> getEnvVars() {
        return env.getEnvVars();
    }

    protected Map<String, String> getSysVars() {
        return env.getSysVars();
    }

    protected PipelineBuildInfoDeployer createDeployer(Run build, TaskListener listener, ArtifactoryServer server)
            throws InterruptedException, NoSuchAlgorithmException, IOException {

        ArtifactoryPipelineConfigurator config = new ArtifactoryPipelineConfigurator(server);
        CredentialsConfig preferredDeployer = CredentialManager.getPreferredDeployer(config, server);
        ArtifactoryBuildInfoClient client = server.createArtifactoryClient(preferredDeployer.getUsername(),
                preferredDeployer.getPassword(), server.createProxyConfiguration(Jenkins.getInstance().proxy));

        addDockerBuildInfo(listener, config);
        return new PipelineBuildInfoDeployer(config, client, build, listener, new BuildInfoAccessor(this));
    }

    private void addDockerBuildInfo(TaskListener listener, ArtifactoryPipelineConfigurator config) throws IOException {
        if (docker.getDockerLayersDependencies().isEmpty()) {
            return;
        }
        final String buildProperties = "build.name=" + this.buildName + "|build.number=" + this.buildNumber + "|build.timestamp=" + Long.toString(this.startDate.getTime());

        ArtifactoryServer server = config.getArtifactoryServer();
        CredentialsConfig preferredResolver = server.getDeployerCredentialsConfig();
        ArtifactoryDependenciesClient dependenciesClient = server.createArtifactoryDependenciesClient(
                preferredResolver.getUsername(), preferredResolver.getPassword(),
                server.createProxyConfiguration(Jenkins.getInstance().proxy), listener);

        CredentialsConfig preferredDeployer = CredentialManager.getPreferredDeployer(config, server);
        ArtifactoryBuildInfoClient propertyClientChange = server.createArtifactoryClient(
                preferredDeployer.getUsername(), preferredDeployer.getPassword(),
                server.createProxyConfiguration(Jenkins.getInstance().proxy));

        String queryStr = getAqlSearchQuery(docker.getDockerLayersDependencies());
        AqlSearchResult result = dependenciesClient.searchArtifactsByAql(queryStr);

        for (AqlSearchResult.SearchEntry entry : result.getResults()) {
            //TODO: check the most compatible result and add the property to it.
            Artifact artifact = new ArtifactBuilder(entry.getName()).sha1(entry.getActual_sha1()).build();
            propertyClientChange.executeUpdateFileProperty(entry.getRepo() + "/" +
                    entry.getPath() + "/" + entry.getName(), buildProperties);
            deployedArtifacts.put(artifact, artifact);
        }
    }

    private String getAqlSearchQuery(Set<String> layersDigest) {

        StringBuilder aqlRequestForDockerSha = new StringBuilder("items.find({\"$or\":[ ");
        List<String> layersQuery = new ArrayList<String>();

        for (String digest : layersDigest) {
            String shaVersion = DockerUtils.getShaVersion(digest);
            String shaValue = DockerUtils.getShaValue(digest);

            String searchProperty = "@sha256";
            if (StringUtils.equalsIgnoreCase(shaVersion, "sha1")) {
                searchProperty = "actual_sha1";
            }

            String query = "{\"" + searchProperty + "\": {\"$match\" : \"" + shaValue + "\"}}";
            layersQuery.add(query);
        }
        aqlRequestForDockerSha.append(StringUtils.join(layersQuery, ","));
        aqlRequestForDockerSha.append("]}).include(\"name\",\"repo\",\"path\",\"actual_sha1\")");
        return aqlRequestForDockerSha.toString();
    }


    public void setCpsScript(CpsScript cpsScript) {
        this.env.setCpsScript(cpsScript);
        this.docker.setCpsScript(cpsScript);
    }

    public void collectBuildInfo(String content) {
        // Collect buildInfo from docker manifest
        docker.getDockerLayersDependencies().addAll(DockerUtils.getDockerManifestLayersDigest(content));
    }
}
