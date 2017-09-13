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
import java.io.PrintStream;
import java.util.*;


/**
 * Build wrapper that creates a TMPDIR before the build and deletes it afterwards.
 */
public class TmpdirBuildWrapper extends BuildWrapper {
    @Extension
    public static class DescriptorImpl extends BuildWrapperDescriptor {
        /**
         * Default, global template for the TMPDIR path.
         *
         * @see #getTmpdirPluginDirTemplate()
         */
        private String globalDirTemplate = "${BUILD_TAG}-tmp";

        /**
         * Retrieves the default TMPDIR path template.
         *
         * This is used when a job does not provide its own template.
         *
         * May contain the usual Jenkins environment/build variables. If relative, then it is
         * made absolute by prepending the value of the <code>java.io.tmpdir</code> property
         * on the node that is used to execute a particular build.
         *
         * @see #globalDirTemplate
         * @return Default TMPDIR path template.
         */
        public String getTmpdirPluginDirTemplate() {
            return globalDirTemplate;
        }

        /**
         * Used to process parameters for this plugin that are configured on the global
         * "Configure System" page.
         *
         * @see BuildWrapperDescriptor#configure(StaplerRequest, JSONObject)
         */
        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            this.globalDirTemplate = json.getString("tmpdirPluginDirTemplate");
            this.save();

            return super.configure(req, json);
        }

        /**
         * Performs form validation for the default TMPDIR path template configuration parameter on the global
         * "Configure System" page.
         *
         * This currently simply checks if the parameter is empty.
         *
         * @param value Value for the TMPDIR path template, as entered by user.
         * @return Form validation result.
         */
        public FormValidation doCheckTmpdirPluginDirTemplate(@QueryParameter String value) {
            if (value.isEmpty()) {
                return FormValidation.error(Messages.tmpdir_buildwrapper_error_emptyValue());
            }

            return FormValidation.ok();
        }

        /**
         * Returns whether this wrapper can be used for a certain project.
         *
         * Currently, this wrapper can be used for all projects.
         *
         * @param item Project to check.
         * @return Whether this wrapper can be used for this project.
         */
        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        /**
         * Returns the display name for this build wrapper.
         *
         * @return Display name for this build wrapper.
         */
        @Nonnull
        @Override
        public String getDisplayName() {
            return Messages.tmpdir_buildwrapper_displayName();
        }
    }

    /**
     * Returns value of system property <code>java.io.tmpdir</code> on a Jenkins slave.
     *
     * Run this on a slave by using a {@link hudson.remoting.VirtualChannel} obtained from e. g.
     * a {@link hudson.Launcher}.
     */
    private static class GetDefaultSlaveTmpdirCallable extends MasterToSlaveCallable<String, IOException> {
        @Override
        public String call() throws IOException {
            return System.getProperty("java.io.tmpdir");
        }
    }

    /**
     * Non-recursively retrieves a sorted list of files and directories in a given directory.
     *
     * @param directory Directory whose children should be returned.
     * @return Sorted list of files/directories in the given directory. List is sorted by the absolute path of each
     * file, in ascending order.
     * @throws IOException          On I/O errors.
     * @throws InterruptedException When the thread is interrupted.
     */
    private static List<FilePath> getSortedDirectoryContents(FilePath directory)
        throws IOException, InterruptedException {
        List<FilePath> children = directory.list();
        children.sort(Comparator.comparing(FilePath::getRemote));
        return children;
    }

    /**
     * The main environment for the TMPDIR build wrapper.
     *
     * This environment actually creates and deletes the TMPDIR.
     */
    private class TmpdirEnvironment extends BuildWrapper.Environment {
        /**
         * Path to the TMPDIR for this build.
         *
         * @see #tmpdirPath
         */
        private final String tmpdir;

        /**
         * Path to the TMPDIR for this build.
         *
         * @see #tmpdir
         */
        private final FilePath tmpdirPath;

        /**
         * Listener for the current build.
         * <p>
         * Used for logging.
         */
        private final BuildListener buildListener;

        /**
         * Creates a new TMPDIR environment.
         *
         * @param tmpdir Path to the TMPDIR that should be managed.
         * @param tmpdirPath Just like <code>tmpdir</code>, but as a {@link hudson.FilePath}.
         * @param buildListener Listener for the current build.
         */
        public TmpdirEnvironment(String tmpdir, FilePath tmpdirPath, BuildListener buildListener) {
            this.tmpdir = tmpdir;
            this.tmpdirPath = tmpdirPath;
            this.buildListener = buildListener;
        }

        /**
         * Injects the <code>TEMP</code> and <code>TMPDIR</code> variables into the build environment.
         *
         * @param env Amended environment variables (i. e. containing <code>TEMP</code> and <code>TMPDIR</code>).
         */
        @Override
        public void buildEnvVars(Map<String, String> env) {
            // Windows
            env.put("TEMP", this.tmpdir);

            // UNIX/Linux
            env.put("TMPDIR", this.tmpdir);

            this.buildListener.getLogger().println("[TMPDIR] Injected environment variables TEMP and TMPDIR.");
        }

        @Override
        public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
            PrintStream logger = listener.getLogger();

            // Do we need to do anything?
            if (!this.tmpdirPath.exists()) {
                logger.format(
                    "[TMPDIR] Directory %s already deleted during build, nothing to do.%n",
                    this.tmpdir
                );
                return true;
            }

            // Should we log the directory contents before deleting the directory?
            if (logDirContents) {
                logger.format("[TMPDIR] ----- Listing leftover files in directory %s -----%n", this.tmpdir);

                int tmpdirPathLen = this.tmpdir.length();
                LinkedList<FilePath> stack = new LinkedList<>();

                // Initialize the file stack:
                stack.addAll(0, getSortedDirectoryContents(this.tmpdirPath));

                while (!stack.isEmpty()) {
                    FilePath filePath = stack.pop();
                    String filePathStr = filePath.getRemote();
                    boolean isDirectory = filePath.isDirectory();

                    logger.format(
                        (Locale) null,
                        "[TMPDIR]  %s  %10d B  %s%s%n",
                        isDirectory ? "DIR" : "   ",
                        isDirectory ? 0 : filePath.length(),
                        // Make the path relative to the TMPDIR:
                        filePathStr.substring(Math.min(tmpdirPathLen + 1, filePathStr.length())),
                        isDirectory ? "/" : ""
                    );

                    // Process the children of this path, if any:
                    stack.addAll(0, getSortedDirectoryContents(filePath));
                }

                logger.println("[TMPDIR] --------------------------------");
            }

            // Now delete the directory!
            logger.format("[TMPDIR] Deleting directory: %s%n", this.tmpdir);
            this.tmpdirPath.deleteRecursive();
            return true;
        }
    }


    /**
     * Job-specific TMPDIR template.
     *
     * Overrides and behaves exactly like {@link DescriptorImpl#globalDirTemplate}.
     */
    private final String jobDirTemplate;

    /**
     * If <code>true</code>, then the contents of the TMPDIR will be logged before it is deleted.
     */
    private final Boolean logDirContents;

    /**
     * Creates a new build wrapper instance.
     *
     * @param tmpdirPluginJobDirTemplate TMPDIR path template configured for this job. See
     *                                   {@link #getTmpdirPluginJobDirTemplate()}.
     * @param tmpdirPluginLogDirContents Whether to log the contents of the TMPDIR before it gets deleted. See
     *                                   {@link #isTmpdirPluginLogDirContents()}.
     */
    @DataBoundConstructor
    public TmpdirBuildWrapper(String tmpdirPluginJobDirTemplate, Boolean tmpdirPluginLogDirContents) {
        this.jobDirTemplate = tmpdirPluginJobDirTemplate;
        this.logDirContents = tmpdirPluginLogDirContents;
    }

    /**
     * Returns the TMPDIR path template configured for this job.
     *
     * This overrides the {@link DescriptorImpl#getTmpdirPluginDirTemplate() global template}.
     *
     * @return <code>null</code> if no template is configured for this job, the template otherwise.
     */
    public String getTmpdirPluginJobDirTemplate() {
        return this.jobDirTemplate;
    }

    /**
     * Returns whether the contents of the TMPDIR should be logged before it is deleted.
     *
     * @return Whether the contents of the TMPDIR should be logged before it is deleted.
     */
    public boolean isTmpdirPluginLogDirContents() {
        return this.logDirContents;
    }

    /**
     * Returns the effective TMPDIR template for this job.
     *
     * This returns the job-specific template, if set, and the global (default) template otherwise.
     *
     * Variables are <b>not replaced</b> in the returned value! Use {@link hudson.EnvVars#expand(String)} with the
     * returned <code>String</code> to do that.
     *
     * @return Effective TMPDIR template, with all variables intact.
     */
    public String getActualDirTemplate() {
        String jobTemplate = this.jobDirTemplate;

        if (!jobTemplate.isEmpty()) {
            return jobTemplate;
        }

        return ((DescriptorImpl) this.getDescriptor()).getTmpdirPluginDirTemplate();
    }

    /**
     * Creates the environment used for this build wrapper.
     *
     * This creates a {@link TmpdirEnvironment} which creates the TMPDIR and removes it after the build.
     *
     * @param build Current build.
     * @param launcher Launcher for the current slave.
     * @param listener Listener for the current build.
     * @return Environment used for this build wrapper.
     * @throws IOException On I/O errors.
     * @throws InterruptedException When the thread is interrupted.
     */
    @Override
    public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException,
        InterruptedException {
        VirtualChannel launcherChannel = launcher.getChannel();

        String specifiedTmpdir = build.getEnvironment(listener).expand(this.getActualDirTemplate());
        FilePath tmpdirPath = new FilePath(launcherChannel, specifiedTmpdir);

        // We do this so that the path we get is normalized, i. e. has no trailing slashes etc.
        String tmpdir = tmpdirPath.getRemote();

        if (tmpdirPath.getParent() == null) {
            // Relative path? Make it absolute by prepending the global TMPDIR.
            tmpdirPath = new FilePath(
                launcherChannel,
                launcherChannel.call(new GetDefaultSlaveTmpdirCallable())
            ).child(tmpdir);

            // Don't forget to update the string representation, too.
            tmpdir = tmpdirPath.getRemote();
        }

        listener.getLogger().format("[TMPDIR] Creating temporary directory: %s%n", tmpdir);

        tmpdirPath.mkdirs();
        // TODO: What about Windows/NTFS permissions?
        tmpdirPath.chmod(0700);

        return new TmpdirEnvironment(tmpdir, tmpdirPath, listener);
    }
}
