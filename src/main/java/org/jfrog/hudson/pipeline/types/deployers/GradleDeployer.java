package org.jfrog.hudson.pipeline.types.deployers;

import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.jfrog.hudson.RepositoryConf;
import org.jfrog.hudson.ServerDetails;
import org.jfrog.hudson.action.ActionableHelper;
import org.jfrog.hudson.pipeline.Utils;
import org.jfrog.hudson.util.publisher.PublisherContext;

/**
 * Created by Tamirh on 16/08/2016.
 */
public class GradleDeployer extends Deployer {
    private boolean usesPlugin;
    private boolean deployMaven;
    private boolean deployIvy;
    private String ivyPattern;
    private String artifactPattern;

    public GradleDeployer() {
        super();
        usesPlugin = false;
        ivyPattern = "[organisation]/[module]/ivy-[revision].xml";
        artifactPattern = "[organisation]/[module]/[revision]/[artifact]-[revision](-[classifier]).[ext]";
    }

    @Override
    public ServerDetails getDetails() {
        RepositoryConf snapshotRepositoryConf = null;
        RepositoryConf releaesRepositoryConf = new RepositoryConf(releaseRepo, releaseRepo, false);
        return new ServerDetails(this.server.getServerName(), this.server.getUrl(), releaesRepositoryConf, snapshotRepositoryConf, releaesRepositoryConf, snapshotRepositoryConf, "", "");
    }

    @Whitelisted
    public boolean isUsesPlugin() {
        return usesPlugin;
    }

    @Whitelisted
    public void setUsesPlugin(boolean usesPlugin) {
        this.usesPlugin = usesPlugin;
    }

    @Whitelisted
    public boolean isDeployMaven() {
        return deployMaven;
    }

    @Whitelisted
    public void setDeployMaven(boolean deployMaven) {
        this.deployMaven = deployMaven;
    }

    @Whitelisted
    public boolean isDeployIvy() {
        return deployIvy;
    }

    @Whitelisted
    public void setDeployIvy(boolean deployIvy) {
        this.deployIvy = deployIvy;
    }

    @Whitelisted
    public String getIvyPattern() {
        return ivyPattern;
    }

    @Whitelisted
    public void setIvyPattern(String ivyPattern) {
        this.ivyPattern = ivyPattern;
    }

    @Whitelisted
    public String getArtifactPattern() {
        return artifactPattern;
    }

    @Whitelisted
    public void setArtifactPattern(String artifactPattern) {
        this.artifactPattern = artifactPattern;
    }

    public PublisherContext.Builder getContextBuilder() {
        return new PublisherContext.Builder()
                .artifactoryServer(getArtifactoryServer())
                .serverDetails(getDetails())
                .deployArtifacts(isDeployArtifacts()).includesExcludes(Utils.getArtifactsIncludeExcludeForDeyployment(getArtifactDeploymentPatterns().getPatternFilter()))
                .skipBuildInfoDeploy(!isDeployBuildInfo())
                .artifactsPattern(getArtifactPattern()).ivyPattern(getIvyPattern())
                .deployIvy(isDeployIvy()).deployMaven(isDeployMaven())
                .deployerOverrider(this)
                .artifactoryPluginVersion(ActionableHelper.getArtifactoryPluginVersion());
    }
}
