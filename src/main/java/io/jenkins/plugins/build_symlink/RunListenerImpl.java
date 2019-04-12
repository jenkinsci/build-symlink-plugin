/*
 * The MIT License
 *
 * Copyright 2019 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.jenkins.plugins.build_symlink;

import hudson.Extension;
import hudson.Util;
import hudson.model.PermalinkProjectAction;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;

@Extension public final class RunListenerImpl extends RunListener<Run<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(RunListenerImpl.class.getName());

    /**
     * Makes sure that {@code lastSuccessful} and {@code lastStable} legacy links in the project’s root directory exist.
     * @param listener probably unused
     */
    @Override public void onStarted(Run<?, ?> build, TaskListener listener) {
        createSymlink(build, listener, "lastSuccessful", PermalinkProjectAction.Permalink.LAST_SUCCESSFUL_BUILD);
        createSymlink(build, listener, "lastStable", PermalinkProjectAction.Permalink.LAST_STABLE_BUILD);
    }

    /**
     * Backward compatibility.
     *
     * We used to have $JENKINS_HOME/jobs/JOBNAME/lastStable and lastSuccessful symlinked to the appropriate
     * builds, but now those are done in {@link PeepholePermalink}. So here, we simply create symlinks that
     * resolves to the symlink created by {@link PeepholePermalink}.
     */
    private void createSymlink(Run<?, ?> build, @Nonnull TaskListener listener, @Nonnull String name, @Nonnull PermalinkProjectAction.Permalink target) {
        File buildDir = build.getParent().getBuildDir();
        File rootDir = build.getParent().getRootDir();
        String targetDir;
        if (buildDir.equals(new File(rootDir, "builds"))) {
            targetDir = "builds" + File.separator + target.getId();
        } else {
            targetDir = buildDir + File.separator + target.getId();
        }
        try {
            Util.createSymlink(rootDir, targetDir, name, listener);
        } catch (InterruptedException x) {
            LOGGER.log(Level.WARNING, null, x);
        }
    }
}
