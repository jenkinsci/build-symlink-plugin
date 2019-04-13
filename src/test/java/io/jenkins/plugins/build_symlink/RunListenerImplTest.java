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

import hudson.Util;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import hudson.tasks.ArtifactArchiver;
import hudson.tasks.Shell;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import jenkins.model.PeepholePermalink;
import org.apache.commons.io.FileUtils;
import org.junit.ClassRule;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.RestartableJenkinsRule;

@Issue("JENKINS-37862")
public class RunListenerImplTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule public RestartableJenkinsRule rr = new RestartableJenkinsRule();

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Rule public LoggerRule logging = new LoggerRule().
        record(RunListenerImpl.class, Level.FINE).
        record(PeepholePermalink.class, Level.FINE);

    @Issue("JENKINS-1986")
    @Test public void buildSymlinks() throws Exception {
        rr.then(r -> {
            FreeStyleProject job = r.createFreeStyleProject();
            job.getBuildersList().add(new Shell("echo \"Build #$BUILD_NUMBER\"\n"));
            FreeStyleBuild build = job.scheduleBuild2(0).get();
            File lastSuccessful = new File(job.getRootDir(), "lastSuccessful"),
                    lastStable = new File(job.getRootDir(), "lastStable");
            // First build creates links
            assertSymlinkForBuild(lastSuccessful, 1);
            assertSymlinkForBuild(lastStable, 1);
            FreeStyleBuild build2 = job.scheduleBuild2(0).get();
            // Another build updates links
            assertSymlinkForBuild(lastSuccessful, 2);
            assertSymlinkForBuild(lastStable, 2);
            // Delete latest build should update links
            build2.delete();
            assertSymlinkForBuild(lastSuccessful, 1);
            assertSymlinkForBuild(lastStable, 1);
            // Delete all builds should remove links
            build.delete();
            assertFalse("lastSuccessful link should be removed", lastSuccessful.exists());
            assertFalse("lastStable link should be removed", lastStable.exists());
        });
    }

    private static void assertSymlinkForBuild(File file, int buildNumber)
            throws IOException, InterruptedException {
        assert file.exists() : "should exist and point to something that exists";
        assert Util.isSymlink(file) : "should be symlink";
        String s = FileUtils.readFileToString(new File(file, "log"), Charset.defaultCharset());
        assert s.contains("Build #" + buildNumber + "\n") : "link should point to build #$buildNumber, but link was: ${Util.resolveSymlink(file, TaskListener.NULL)}\nand log was:\n$s";
    }

    @Issue("JENKINS-2543")
    @Test public void symlinkForPostBuildFailure() throws Exception {
        rr.then(r -> {
            // Links should be updated after post-build actions when final build result is known
            FreeStyleProject job = r.createFreeStyleProject();
            job.getBuildersList().add(new Shell("echo \"Build #$BUILD_NUMBER\"\n"));
            FreeStyleBuild build = job.scheduleBuild2(0).get();
            assert Result.SUCCESS == build.getResult();
            File lastSuccessful = new File(job.getRootDir(), "lastSuccessful"),
                    lastStable = new File(job.getRootDir(), "lastStable");
            // First build creates links
            assertSymlinkForBuild(lastSuccessful, 1);
            assertSymlinkForBuild(lastStable, 1);
            // Archive artifacts that don't exist to create failure in post-build action
            job.getPublishersList().add(new ArtifactArchiver("*.foo"));
            build = job.scheduleBuild2(0).get();
            assert Result.FAILURE == build.getResult();
            // Links should not be updated since build failed
            assertSymlinkForBuild(lastSuccessful, 1);
            assertSymlinkForBuild(lastStable, 1);
        });
    }

    @Test
    @Issue("JENKINS-17137")
    public void externalBuildDirectorySymlinks() throws Exception {
        // Hack to get String builds usable in lambda below
        final List<String> builds = new ArrayList<>();

        rr.then(r -> {
            builds.add(tmp.getRoot().getAbsolutePath());
            setBuildsDirProperty(builds.get(0) + "/${ITEM_FULL_NAME}");
        });

        rr.then(r -> {
            assertEquals(builds.get(0) + "/${ITEM_FULL_NAME}", r.jenkins.getRawBuildsDir());
            FreeStyleProject p = r.createProject(MockFolder.class, "d").createProject(FreeStyleProject.class, "p");
            FreeStyleBuild b1 = p.scheduleBuild2(0).get();
            File link = new File(p.getRootDir(), "lastStable");
            assertTrue(link.exists());
            assertEquals(resolveAll(link).getAbsolutePath(), b1.getRootDir().getAbsolutePath());
            FreeStyleBuild b2 = p.scheduleBuild2(0).get();
            assertTrue(link.exists());
            assertEquals(resolveAll(link).getAbsolutePath(), b2.getRootDir().getAbsolutePath());
            b2.delete();
            assertTrue(link.exists());
            assertEquals(resolveAll(link).getAbsolutePath(), b1.getRootDir().getAbsolutePath());
            b1.delete();
            assertFalse(link.exists());
        });
    }

    private File resolveAll(File link) throws InterruptedException, IOException {
        while (true) {
            File f = Util.resolveSymlinkToFile(link);
            if (f == null) {
                return link;
            }
            link = f;
        }
    }

    private void setBuildsDirProperty(String buildsDir) {
        System.setProperty(/*Jenkins.BUILDS_DIR_PROP*/Jenkins.class.getName() + ".buildsDir", buildsDir);
    }

    /**
     * Basic operation of the permalink generation.
     */
    @Test
    public void peepholePermalinks() throws Exception {
        rr.then(r -> {
            List<Class<?>> listenerImpls = RunListener.all().stream().map(Object::getClass).collect(Collectors.toList());
            assertTrue(listenerImpls.toString(), listenerImpls.indexOf(PeepholePermalink.RunListenerImpl.class) < listenerImpls.indexOf(RunListenerImpl.class));

            FreeStyleProject p = r.createFreeStyleProject();
            FreeStyleBuild b1 = r.assertBuildStatusSuccess(p.scheduleBuild2(0));

            File lsb = new File(p.getBuildDir(), "lastSuccessfulBuild");
            File lfb = new File(p.getBuildDir(), "lastFailedBuild");

            assertLink(lsb, b1);

            // now another build that fails
            p.getBuildersList().add(new FailureBuilder());
            FreeStyleBuild b2 = p.scheduleBuild2(0).get();

            assertLink(lsb, b1);
            assertLink(lfb, b2);

            // one more build and this time it succeeds
            p.getBuildersList().clear();
            FreeStyleBuild b3 = r.assertBuildStatusSuccess(p.scheduleBuild2(0));

            assertLink(lsb, b3);
            assertLink(lfb, b2);

            // delete b3 and symlinks should update properly
            b3.delete();
            assertLink(lsb, b1);
            assertLink(lfb, b2);

            b1.delete();
            assertLink(lsb, null);
            assertLink(lfb, b2);

            b2.delete();
            assertLink(lsb, null);
            assertLink(lfb, null);
        });
    }

    private void assertLink(File symlink, Run<?, ?> build) throws Exception {
        assertEquals(build == null ? "-1" : Integer.toString(build.getNumber()), Util.resolveSymlink(symlink));
    }

    /**
     * job/JOBNAME/lastStable and job/JOBNAME/lastSuccessful symlinks that we
     * used to generate should still work
     */
    @Test public void legacyCompatibility() throws Exception {
        rr.then(r -> {
            FreeStyleProject p = r.createFreeStyleProject();
            FreeStyleBuild b1 = r.assertBuildStatusSuccess(p.scheduleBuild2(0));

            for (String n : new String[] {"lastStable", "lastSuccessful"}) {
                // test if they both point to b1
                assertEquals(new File(p.getRootDir(), n + "/build.xml").length(), new File(b1.getRootDir(), "build.xml").length());
            }
        });
    }

}
