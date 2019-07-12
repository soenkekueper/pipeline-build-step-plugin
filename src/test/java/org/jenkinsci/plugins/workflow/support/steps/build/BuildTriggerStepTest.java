package org.jenkinsci.plugins.workflow.support.steps.build;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.Action;
import hudson.model.BooleanParameterDefinition;
import hudson.model.BooleanParameterValue;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Label;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.model.queue.QueueTaskFuture;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import jenkins.branch.MultiBranchProjectFactory;
import jenkins.branch.MultiBranchProjectFactoryDescriptor;
import jenkins.branch.OrganizationFolder;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMSource;
import jenkins.scm.impl.mock.MockSCMController;
import jenkins.scm.impl.mock.MockSCMDiscoverBranches;
import jenkins.scm.impl.mock.MockSCMNavigator;
import jenkins.security.QueueItemAuthenticatorConfiguration;
import org.apache.commons.lang.StringUtils;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.cps.SnippetizerTester;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.CaptureEnvironmentBuilder;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.MockQueueItemAuthenticator;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.recipes.LocalData;

public class BuildTriggerStepTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsRule j = new JenkinsRule();
    @Rule public LoggerRule logging = new LoggerRule();

    @Before public void runQuickly() throws IOException {
        j.jenkins.setQuietPeriod(0);
    }

    @Issue("JENKINS-25851")
    @Test public void buildTopLevelProject() throws Exception {
        FreeStyleProject ds = j.createFreeStyleProject("ds");
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
            "def ds = build 'ds'\n" +
            "echo \"ds.result=${ds.result} ds.number=${ds.number}\"", true));
        j.assertLogContains("ds.result=SUCCESS ds.number=1", j.buildAndAssertSuccess(us));
        // TODO JENKINS-28673 assert no warnings, as in StartupTest.noWarnings
        // (but first need to deal with `WARNING: Failed to instantiate optional component org.jenkinsci.plugins.workflow.steps.scm.SubversionStep$DescriptorImpl; skipping`)
        ds.getBuildByNumber(1).delete();
    }

    @Issue("JENKINS-25851")
    @Test public void failingBuild() throws Exception {
        j.createFreeStyleProject("ds").getBuildersList().add(new FailureBuilder());
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build 'ds'", true));
        j.assertBuildStatus(Result.FAILURE, us.scheduleBuild2(0));
        us.setDefinition(new CpsFlowDefinition("echo \"ds.result=${build(job: 'ds', propagate: false).result}\"", true));
        j.assertLogContains("ds.result=FAILURE", j.buildAndAssertSuccess(us));
    }

    @Issue("JENKINS-38339")
    @Test public void upstreamNodeAction() throws Exception {
        FreeStyleProject downstream = j.createFreeStyleProject("downstream");
        WorkflowJob upstream = j.jenkins.createProject(WorkflowJob.class, "upstream");

        upstream.setDefinition(new CpsFlowDefinition("build 'downstream'", true));
        j.assertBuildStatus(Result.SUCCESS, upstream.scheduleBuild2(0));

        WorkflowRun lastUpstreamRun = upstream.getLastBuild();
        FreeStyleBuild lastDownstreamRun = downstream.getLastBuild();

        final FlowExecution execution = lastUpstreamRun.getExecution();
        List<FlowNode> nodes = execution.getCurrentHeads();
        assertEquals("node count", 1, nodes.size());
        FlowNode headNode = nodes.get(0);

        List<BuildUpstreamNodeAction> actions = lastDownstreamRun.getActions(BuildUpstreamNodeAction.class);
        assertEquals("action count", 1, actions.size());

        BuildUpstreamNodeAction action = actions.get(0);
        assertEquals("correct upstreamRunId", action.getUpstreamRunId(), lastUpstreamRun.getExternalizableId());
        assertNotNull("valid upstreamNodeId", execution.getNode(action.getUpstreamNodeId()));
    }

    @SuppressWarnings("deprecation")
    @Test
    public void buildFolderProject() throws Exception {
        MockFolder dir1 = j.createFolder("dir1");
        FreeStyleProject downstream = dir1.createProject(FreeStyleProject.class, "downstream");
        downstream.getBuildersList().add(new SleepBuilder(1));

        MockFolder dir2 = j.createFolder("dir2");
        WorkflowJob upstream = dir2.createProject(WorkflowJob.class, "upstream");
        upstream.setDefinition(new CpsFlowDefinition("build '../dir1/downstream'"));

        j.buildAndAssertSuccess(upstream);
        assertEquals(1, downstream.getBuilds().size());
    }

    @Test
    public void buildParallelTests() throws Exception {
        FreeStyleProject p1 = j.createFreeStyleProject("test1");
        p1.getBuildersList().add(new SleepBuilder(1));

        FreeStyleProject p2 = j.createFreeStyleProject("test2");
        p2.getBuildersList().add(new SleepBuilder(1));

        WorkflowJob foo = j.jenkins.createProject(WorkflowJob.class, "foo");
        foo.setDefinition(new CpsFlowDefinition(StringUtils.join(Arrays.asList("parallel(test1: {\n" +
                "          build('test1');\n" +
                "        }, test2: {\n" +
                "          build('test2');\n" +
                "        })"), "\n"), true));

        j.buildAndAssertSuccess(foo);
    }


    @Test
    public void abortBuild() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("test1");
        p.getBuildersList().add(new SleepBuilder(Long.MAX_VALUE));

        WorkflowJob foo = j.jenkins.createProject(WorkflowJob.class, "foo");
        foo.setDefinition(new CpsFlowDefinition(StringUtils.join(Arrays.asList("build('test1');"), "\n")));

        QueueTaskFuture<WorkflowRun> q = foo.scheduleBuild2(0);
        WorkflowRun b = q.getStartCondition().get();

        CpsFlowExecution e = (CpsFlowExecution) b.getExecutionPromise().get();
        e.waitForSuspension();

        FreeStyleBuild fb=null;
        while (fb==null) {
            fb = p.getBuildByNumber(1);
            Thread.sleep(10);
        }
        fb.getExecutor().interrupt();

        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(fb));
        j.assertBuildStatus(Result.ABORTED, q.get());
    }

    @Issue("JENKINS-49073")
    @Test public void downstreamUnstable() throws Exception {
        FreeStyleProject ds = j.createFreeStyleProject("ds");
        ds.getBuildersList().add(new TestBuilder() {
            @Override public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
                build.setResult(Result.UNSTABLE);
                return true;
            }
        });
        WorkflowJob us = j.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build 'ds'", true));
        j.assertBuildStatus(Result.UNSTABLE, us.scheduleBuild2(0));
    }

    @Test
    public void cancelBuildQueue() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("test1");
        p.getBuildersList().add(new SleepBuilder(Long.MAX_VALUE));

        WorkflowJob foo = j.jenkins.createProject(WorkflowJob.class, "foo");
        foo.setDefinition(new CpsFlowDefinition(StringUtils.join(Arrays.asList("build('test1');"), "\n")));

        j.jenkins.setNumExecutors(0); //should force freestyle build to remain in the queue?

        QueueTaskFuture<WorkflowRun> q = foo.scheduleBuild2(0);

        WorkflowRun b = foo.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Scheduling project", b);
        CpsFlowExecution e = (CpsFlowExecution) b.getExecutionPromise().get();
        e.waitForSuspension();

        Queue.Item[] items = j.jenkins.getQueue().getItems();
        assertEquals(1, items.length);
        j.jenkins.getQueue().cancel(items[0]);

        j.assertBuildStatus(Result.FAILURE,q.get());
    }

    /** Interrupting the flow ought to interrupt its downstream builds too, even across nested parallel branches. */
    @Test public void interruptFlow() throws Exception {
        FreeStyleProject ds1 = j.createFreeStyleProject("ds1");
        ds1.getBuildersList().add(new SleepBuilder(Long.MAX_VALUE));
        FreeStyleProject ds2 = j.createFreeStyleProject("ds2");
        ds2.getBuildersList().add(new SleepBuilder(Long.MAX_VALUE));
        FreeStyleProject ds3 = j.createFreeStyleProject("ds3");
        ds3.getBuildersList().add(new SleepBuilder(Long.MAX_VALUE));
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("parallel ds1: {build 'ds1'}, ds23: {parallel ds2: {build 'ds2'}, ds3: {build 'ds3'}}", true));
        j.jenkins.setNumExecutors(3);
        j.jenkins.setNodes(j.jenkins.getNodes()); // TODO https://github.com/jenkinsci/jenkins/pull/1596 renders this workaround unnecessary
        WorkflowRun usb = us.scheduleBuild2(0).getStartCondition().get();
        assertEquals(1, usb.getNumber());
        FreeStyleBuild ds1b, ds2b, ds3b;
        while ((ds1b = ds1.getLastBuild()) == null || (ds2b = ds2.getLastBuild()) == null || (ds3b = ds3.getLastBuild()) == null) {
            Thread.sleep(100);
        }
        assertEquals(1, ds1b.getNumber());
        assertEquals(1, ds2b.getNumber());
        assertEquals(1, ds3b.getNumber());
        // Same as X button in UI.
        // Should be the same as, e.g., GerritTrigger.RunningJobs.cancelJob, which calls Executor.interrupt directly.
        // (Not if the Executor.currentExecutable is an AfterRestartTask.Body, though in that case probably the FreeStyleBuild would have been killed by restart anyway!)
        usb.doStop();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(usb));
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(ds1b));
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(ds2b));
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(ds3b));
    }

    @Issue("JENKINS-31902")
    @Test public void interruptFlowDownstreamFlow() throws Exception {
        WorkflowJob ds = j.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition("semaphore 'ds'", true));
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build 'ds'", true));
        WorkflowRun usb = us.scheduleBuild2(0).getStartCondition().get();
        assertEquals(1, usb.getNumber());
        SemaphoreStep.waitForStart("ds/1", null);
        WorkflowRun dsb = ds.getLastBuild();
        assertEquals(1, dsb.getNumber());
        usb.doStop();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(usb));
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(dsb));
    }

    @Test public void interruptFlowNonPropagate() throws Exception {
        WorkflowJob ds = j.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition("semaphore 'ds'", true));
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("while (true) {build job: 'ds', propagate: false}", true));
        WorkflowRun usb = us.scheduleBuild2(0).getStartCondition().get();
        assertEquals(1, usb.getNumber());
        SemaphoreStep.waitForStart("ds/1", null);
        WorkflowRun dsb = ds.getLastBuild();
        assertEquals(1, dsb.getNumber());
        usb.doStop();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(usb));
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(dsb));
    }

    @SuppressWarnings("deprecation")
    @Test public void triggerWorkflow() throws Exception {
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build 'ds'"));
        WorkflowJob ds = j.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition("echo 'OK'"));
        j.buildAndAssertSuccess(us);
        assertEquals(1, ds.getBuilds().size());
    }

    @Issue("JENKINS-31897")
    @Test public void parameters() throws Exception {
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        FreeStyleProject ds = j.jenkins.createProject(FreeStyleProject.class, "ds");
        ds.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("branch", "master"), new BooleanParameterDefinition("extra", false, null)));
        CaptureEnvironmentBuilder env = new CaptureEnvironmentBuilder();
        ds.getBuildersList().add(env);
        us.setDefinition(new CpsFlowDefinition("build 'ds'"));
        WorkflowRun us1 = j.buildAndAssertSuccess(us);
        assertEquals("1", env.getEnvVars().get("BUILD_NUMBER"));
        assertEquals("master", env.getEnvVars().get("branch"));
        assertEquals("false", env.getEnvVars().get("extra"));
        Cause.UpstreamCause cause = ds.getBuildByNumber(1).getCause(Cause.UpstreamCause.class);
        assertNotNull(cause);
        assertEquals(us1, cause.getUpstreamRun());
        us.setDefinition(new CpsFlowDefinition("build job: 'ds', parameters: [string(name: 'branch', value: 'release')]", true));
        j.buildAndAssertSuccess(us);
        assertEquals("2", env.getEnvVars().get("BUILD_NUMBER"));
        assertEquals("release", env.getEnvVars().get("branch"));
        assertEquals("false", env.getEnvVars().get("extra")); //
        us.setDefinition(new CpsFlowDefinition("build job: 'ds', parameters: [string(name: 'branch', value: 'release'), booleanParam(name: 'extra', value: true)]", true));
        j.buildAndAssertSuccess(us);
        assertEquals("3", env.getEnvVars().get("BUILD_NUMBER"));
        assertEquals("release", env.getEnvVars().get("branch"));
        assertEquals("true", env.getEnvVars().get("extra"));
    }

    @Issue("JENKINS-26123")
    @Test public void noWait() throws Exception {
        j.createFreeStyleProject("ds").setAssignedLabel(Label.get("nonexistent"));
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build job: 'ds', wait: false"));
        j.buildAndAssertSuccess(us);
    }

    @Test public void rejectedStart() throws Exception {
        j.createFreeStyleProject("ds");
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        // wait: true also fails as expected w/o fix, just more slowly (test timeout):
        us.setDefinition(new CpsFlowDefinition("build job: 'ds', wait: false"));
        j.assertLogContains("Failed to trigger build of ds", j.assertBuildStatus(Result.FAILURE, us.scheduleBuild2(0)));
    }
    @TestExtension("rejectedStart") public static final class QDH extends Queue.QueueDecisionHandler {
        @Override public boolean shouldSchedule(Queue.Task p, List<Action> actions) {
            return p instanceof WorkflowJob; // i.e., refuse FreestyleProject
        }
    }

    @Issue("JENKINS-25851")
    @Test public void buildVariables() throws Exception {
        j.createFreeStyleProject("ds").addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("param", "default")));
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("echo \"build var: ${build(job: 'ds', parameters: [string(name: 'param', value: 'override')]).buildVariables.param}\"", true));
        j.assertLogContains("build var: override", j.buildAndAssertSuccess(us));
    }

    @Issue("JENKINS-29169")
    @Test public void buildVariablesWorkflow() throws Exception {
        WorkflowJob ds = j.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition("env.RESULT = \"ds-${env.BUILD_NUMBER}\"", true));
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("def vars = build('ds').buildVariables; echo \"received RESULT=${vars.RESULT} vs. BUILD_NUMBER=${vars.BUILD_NUMBER}\"", true));
        j.assertLogContains("received RESULT=ds-1 vs. BUILD_NUMBER=null", j.buildAndAssertSuccess(us));
        ds.getBuildByNumber(1).delete();
    }

    @Issue("JENKINS-28063")
    @Test public void coalescedQueue() throws Exception {
        FreeStyleProject ds = j.createFreeStyleProject("ds");
        ds.setQuietPeriod(3);
        ds.setConcurrentBuild(true);
        ds.getBuildersList().add(new SleepBuilder(3000));
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("echo \"triggered #${build('ds').number}\"", true));
        WorkflowRun us1 = us.scheduleBuild2(0).waitForStart();
        assertEquals(1, us1.getNumber());
        j.waitForMessage("Scheduling project: ds", us1);
        QueueTaskFuture<WorkflowRun> us2F = us.scheduleBuild2(0);
        j.assertLogContains("triggered #1", j.waitForCompletion(us1));
        WorkflowRun us2 = us2F.get();
        assertEquals(2, us2.getNumber());
        j.assertLogContains("triggered #1", us2);
        FreeStyleBuild ds1 = ds.getLastBuild();
        assertEquals(1, ds1.getNumber());
        assertEquals(2, ds1.getCauses().size()); // 2× UpstreamCause
    }

    @Issue("http://stackoverflow.com/q/32228590/12916")
    @Test public void nonCoalescedQueueParallel() throws Exception {
        j.jenkins.setNumExecutors(5);
        FreeStyleProject ds = j.createFreeStyleProject("ds");
        ds.setConcurrentBuild(true);
        ds.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("which", null)));
        ds.getBuildersList().add(new SleepBuilder(3000));
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition(
            "def branches = [:]\n" +
            "for (int i = 0; i < 5; i++) {\n" +
            "  def which = \"${i}\"\n" +
            "  branches[\"branch${i}\"] = {\n" +
            "    build job: 'ds', parameters: [string(name: 'which', value: which)]\n" +
            "  }\n" +
            "}\n" +
            "parallel branches", true));
        j.buildAndAssertSuccess(us);
        FreeStyleBuild ds1 = ds.getLastBuild();
        assertEquals(5, ds1.getNumber());
    }

    @Issue("JENKINS-39454")
    @Test public void raceCondition() throws Exception {
        logging.record(BuildTriggerStepExecution.class.getPackage().getName(), Level.FINE).record(Queue.class, Level.FINE).record(Executor.class, Level.FINE);
        j.jenkins.setQuietPeriod(0);
        WorkflowJob ds = j.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition("sleep 1", true));
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("def rebuild() {for (int i = 0; i < 20; i++) {build 'ds'}}; parallel A: {rebuild()}, B: {rebuild()}, C: {rebuild()}", true));
        j.buildAndAssertSuccess(us);
    }

    @Issue("JENKINS-31897")
    @Test public void defaultParameters() throws Exception {
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build job: 'ds', parameters: [string(name: 'PARAM1', value: 'first')]"));
        WorkflowJob ds = j.jenkins.createProject(WorkflowJob.class, "ds");
        ds.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("PARAM1", "p1"), new StringParameterDefinition("PARAM2", "p2")));
        // TODO use params when updating workflow-cps/workflow-job
        ds.setDefinition(new CpsFlowDefinition("echo \"${PARAM1} - ${PARAM2}\""));
        j.buildAndAssertSuccess(us);
        j.assertLogContains("first - p2", ds.getLastBuild());
    }

    @LocalData
    @Test public void storedForm() throws Exception {
        WorkflowJob us = j.jenkins.getItemByFullName("us", WorkflowJob.class);
        WorkflowRun us1 = us.getBuildByNumber(1);
        WorkflowJob ds = j.jenkins.getItemByFullName("ds", WorkflowJob.class);
        WorkflowRun ds1 = ds.getBuildByNumber(1);
        ds1.setDescription("something");
        j.assertBuildStatusSuccess(j.waitForCompletion(ds1));
        j.assertBuildStatusSuccess(j.waitForCompletion(us1));
    }

    @Test
    @Issue("JENKINS-38887")
    public void triggerOrgFolder() throws Exception {
        try (MockSCMController c = MockSCMController.create()) {
            c.createRepository("foo");
            WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
            us.setDefinition(new CpsFlowDefinition("build job:'ds', wait:false", true));
            OrganizationFolder ds = j.jenkins.createProject(OrganizationFolder.class, "ds");
            ds.getSCMNavigators().add(new MockSCMNavigator(c, new MockSCMDiscoverBranches()));
            ds.getProjectFactories().add(new DummyMultiBranchProjectFactory());
            j.waitUntilNoActivity();
            assertThat(ds.getComputation().getResult(), nullValue());
            j.buildAndAssertSuccess(us);
            j.waitUntilNoActivity();
            assertThat(ds.getComputation().getResult(), notNullValue());
        }
    }

    public static class DummyMultiBranchProjectFactory extends MultiBranchProjectFactory {
        @Override
        public boolean recognizes(@NonNull ItemGroup<?> parent, @NonNull String name,
                                  @NonNull List<? extends SCMSource> scmSources,
                                  @NonNull Map<String, Object> attributes,
                                  @CheckForNull SCMHeadEvent<?> event, @NonNull TaskListener listener)
                throws IOException, InterruptedException {
            return false;
        }

        @TestExtension("triggerOrgFolder")
        public static class DescriptorImpl extends MultiBranchProjectFactoryDescriptor {

            @Override
            public MultiBranchProjectFactory newInstance() {
                return new DummyMultiBranchProjectFactory();
            }
        }
    }

    @Issue("SECURITY-433")
    @Test public void permissions() throws Exception {
        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build 'ds'", true));
        WorkflowJob ds = j.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition("", true));
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        QueueItemAuthenticatorConfiguration.get().getAuthenticators().add(new MockQueueItemAuthenticator(Collections.singletonMap("us", User.get("dev").impersonate())));
        // Control case: dev can do anything to ds.
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.ADMINISTER).everywhere().to("dev"));
        j.buildAndAssertSuccess(us);
        // Main test case: dev can see ds but not build it.
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.READ, Computer.BUILD).everywhere().to("dev").grant(Item.READ).onItems(ds).to("dev"));
        j.assertLogContains("dev is missing the Job/Build permission", j.assertBuildStatus(Result.FAILURE, us.scheduleBuild2(0)));
        // Aux test case: dev cannot see ds.
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.READ, Computer.BUILD).everywhere().to("dev"));
        j.assertLogContains("No item named ds found", j.assertBuildStatus(Result.FAILURE, us.scheduleBuild2(0)));
        // Aux test case: dev can learn of the existence of ds but no more.
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.READ, Computer.BUILD).everywhere().to("dev").grant(Item.DISCOVER).onItems(ds).to("dev"));
        j.assertLogContains("Please login to access job ds", j.assertBuildStatus(Result.FAILURE, us.scheduleBuild2(0)));
    }

    @Issue("JENKINS-48632")
    @Test
    public void parameterDescriptions() throws Exception {
        WorkflowJob ds = j.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition("properties([\n" +
                "  parameters([\n" +
                "    booleanParam(defaultValue: true, description: 'flag description', name: 'flag'),\n" +
                "    string(defaultValue: 'default string', description: 'strParam description', name: 'strParam')\n" +
                "  ])\n" +
                "])\n", true));
        // Define the parameters
        j.buildAndAssertSuccess(ds);

        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build job: 'ds', parameters: [booleanParam(name: 'flag', value: false)]\n", true));
        j.buildAndAssertSuccess(us);

        j.waitUntilNoActivity();

        WorkflowRun r = ds.getBuildByNumber(2);
        assertNotNull(r);

        ParametersAction action = r.getAction(ParametersAction.class);
        assertNotNull(action);

        ParameterValue flagValue = action.getParameter("flag");
        assertNotNull(flagValue);
        assertEquals("flag description", flagValue.getDescription());

        ParameterValue strValue = action.getParameter("strParam");
        assertNotNull(strValue);
        assertEquals("strParam description", strValue.getDescription());
    }

    @Issue("JENKINS-52038")
    @Test
    public void invalidChoiceParameterValue() throws Exception {
        WorkflowJob ds = j.jenkins.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition("properties([\n" +
                "  parameters([\n" +
                "    choice(name:'letter', description: 'a letter', choices: ['a', 'b'].join(\"\\n\"))\n" +
                "  ])\n" +
                "])\n", true));
        // Define the parameters
        j.buildAndAssertSuccess(ds);

        WorkflowJob us = j.jenkins.createProject(WorkflowJob.class, "us");
        us.setDefinition(new CpsFlowDefinition("build job: 'ds', parameters: [string(name: 'letter', value: 'c')]\n", true));
        j.assertLogContains("Value for choice parameter 'letter' is 'c', but valid choices are [a, b]",
                j.assertBuildStatus(Result.FAILURE, us.scheduleBuild2(0)));
    }

    @Test public void snippetizerRoundTrip() throws Exception {
        SnippetizerTester st = new SnippetizerTester(j);
        BuildTriggerStep step = new BuildTriggerStep("downstream");
        st.assertRoundTrip(step, "build 'downstream'");
        step.setParameters(Arrays.asList(new StringParameterValue("branch", "default"), new BooleanParameterValue("correct", true)));
        // Note: This does not actually test the format of the JSON produced by the snippet generator for parameters, see generateSnippet* for tests of that behavior.
        st.assertRoundTrip(step, "build job: 'downstream', parameters: [string(name: 'branch', value: 'default'), booleanParam(name: 'correct', value: true)]");
    }

    @Issue("JENKINS-26093")
    @Test public void generateSnippetForBuildTrigger() throws Exception {
        SnippetizerTester st = new SnippetizerTester(j);
        MockFolder d1 = j.createFolder("d1");
        FreeStyleProject ds = d1.createProject(FreeStyleProject.class, "ds");
        MockFolder d2 = j.createFolder("d2");
        WorkflowJob us = d2.createProject(WorkflowJob.class, "us");
        ds.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("key", ""), new BooleanParameterDefinition("flag", false, "")));
        String snippet = "build job: '../d1/ds', parameters: [string(name: 'key', value: 'stuff'), booleanParam(name: 'flag', value: true)]";
        st.assertGenerateSnippet("{'stapler-class':'" + BuildTriggerStep.class.getName() + "', 'job':'../d1/ds', 'parameter': [{'name':'key', 'value':'stuff'}, {'name':'flag', 'value':true}]}", snippet, us.getAbsoluteUrl() + "configure");
    }

    @Issue("JENKINS-29739")
    @Test public void generateSnippetForBuildTriggerSingle() throws Exception {
        SnippetizerTester st = new SnippetizerTester(j);
        FreeStyleProject ds = j.jenkins.createProject(FreeStyleProject.class, "ds1");
        FreeStyleProject us = j.jenkins.createProject(FreeStyleProject.class, "us1");
        ds.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("key", "")));
        String snippet = "build job: 'ds1', parameters: [string(name: 'key', value: 'stuff')]";
        st.assertGenerateSnippet("{'stapler-class':'" + BuildTriggerStep.class.getName() + "', 'job':'ds1', 'parameter': {'name':'key', 'value':'stuff'}}", snippet, us.getAbsoluteUrl() + "configure");
    }

    @Test public void generateSnippetForBuildTriggerNone() throws Exception {
        SnippetizerTester st = new SnippetizerTester(j);
        FreeStyleProject ds = j.jenkins.createProject(FreeStyleProject.class, "ds0");
        FreeStyleProject us = j.jenkins.createProject(FreeStyleProject.class, "us0");
        st.assertGenerateSnippet("{'stapler-class':'" + BuildTriggerStep.class.getName() + "', 'job':'ds0'}", "build 'ds0'", us.getAbsoluteUrl() + "configure");
    }

    @Test
    public void buildStepDocs() throws Exception {
        SnippetizerTester.assertDocGeneration(BuildTriggerStep.class);
    }
}
