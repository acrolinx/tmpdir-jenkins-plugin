/*
 * Copyright 2017 Acrolinx GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.acrolinx.jenkins.tmpdir_jenkins_plugin;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.FormValidation;
import jenkins.security.MasterToSlaveCallable;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Map;

public class TmpdirBuildWrapper extends BuildWrapper {
    @Extension
    public static class DescriptorImpl extends BuildWrapperDescriptor {
        private String globalDirTemplate = "${BUILD_TAG}-tmp";

        public String getTmpdirPluginDirTemplate() {
            return globalDirTemplate;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            this.globalDirTemplate = json.getString("tmpdirPluginDirTemplate");
            this.save();

            return super.configure(req, json);
        }

        public FormValidation doCheckTmpdirPluginDirTemplate(@QueryParameter String value) {
            if (value.trim().isEmpty()) {
                return FormValidation.error(Messages.tmpdir_buildwrapper_error_emptyValue());
            }

            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return Messages.tmpdir_buildwrapper_displayName();
        }
    }

    private static class GetDefaultSlaveTmpdirCallable extends MasterToSlaveCallable<String, IOException> {
        @Override
        public String call() throws IOException {
            return System.getProperty("java.io.tmpdir");
        }
    }

    private class TmpdirEnvironment extends BuildWrapper.Environment {
        private final String tmpdir;

        public TmpdirEnvironment(String tmpdir) {
            this.tmpdir = tmpdir;
        }

        @Override
        public void buildEnvVars(Map<String, String> env) {
            // Windows
            env.put("TEMP", this.tmpdir);

            // UNIX/Linux
            env.put("TMPDIR", this.tmpdir);
        }
    }


    private final String jobDirTemplate;
    private final Boolean logDirContents;

    @DataBoundConstructor
    public TmpdirBuildWrapper(String tmpdirPluginJobDirTemplate, Boolean tmpdirPluginLogDirContents) {
        this.jobDirTemplate = tmpdirPluginJobDirTemplate;
        this.logDirContents = tmpdirPluginLogDirContents;
    }

    public String getTmpdirPluginJobDirTemplate() {
        return this.jobDirTemplate;
    }

    public boolean isTmpdirPluginLogDirContents() {
        return this.logDirContents;
    }

    public String getActualDirTemplate() {
        String jobTemplate = this.jobDirTemplate;

        if (!jobTemplate.isEmpty()) {
            return jobTemplate;
        }

        return ((DescriptorImpl) this.getDescriptor()).getTmpdirPluginDirTemplate();
    }

    @Override
    public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException,
        InterruptedException {
        VirtualChannel launcherChannel = launcher.getChannel();

        String tmpdir = build.getEnvironment(listener).expand(this.getActualDirTemplate());
        FilePath tmpdirPath = new FilePath(launcherChannel, tmpdir);

        if (tmpdirPath.getParent() == null) {
            // Relative path? Make it absolute by prepending the global TMPDIR.
            tmpdirPath = new FilePath(
                launcherChannel,
                launcherChannel.call(new GetDefaultSlaveTmpdirCallable())
            ).child(tmpdir);

            // Don't forget to update the string representation, too.
            tmpdir = tmpdirPath.getRemote();
        }

        return new TmpdirEnvironment(tmpdir);
    }
}
