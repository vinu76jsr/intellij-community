/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.execution.impl;

import com.intellij.CommonBundle;
import com.intellij.execution.*;
import com.intellij.execution.configuration.CompatibilityAwareRunProfile;
import com.intellij.execution.configurations.*;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.execution.ui.RunContentManagerImpl;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Trinity;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.ui.docking.DockManager;
import com.intellij.util.Alarm;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.Predicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

/**
 * @author dyoma
 */
public class ExecutionManagerImpl extends ExecutionManager implements ProjectComponent {
  public static final Key<Object> EXECUTION_SESSION_ID_KEY = Key.create("EXECUTION_SESSION_ID_KEY");
  private static final Logger LOG = Logger.getInstance("com.intellij.execution.impl.ExecutionManagerImpl");

  private final Project myProject;

  private RunContentManagerImpl myContentManager;
  private final Alarm awaitingTerminationAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD);
  private final List<Trinity<RunContentDescriptor, RunnerAndConfigurationSettings, Executor>> myRunningConfigurations =
    ContainerUtil.createLockFreeCopyOnWriteList();

  /**
   * reflection
   */
  ExecutionManagerImpl(final Project project) {
    myProject = project;
  }

  @Override
  public void projectOpened() {
    ((RunContentManagerImpl)getContentManager()).init();
  }

  @Override
  public void projectClosed() {
  }

  @Override
  public void initComponent() {
  }

  @Override
  public void disposeComponent() {
    for (Trinity<RunContentDescriptor, RunnerAndConfigurationSettings, Executor> trinity : myRunningConfigurations) {
      Disposer.dispose(trinity.first);
    }
    myRunningConfigurations.clear();
  }

  @NotNull
  @Override
  public RunContentManager getContentManager() {
    if (myContentManager == null) {
      myContentManager = new RunContentManagerImpl(myProject, DockManager.getInstance(myProject));
      Disposer.register(myProject, myContentManager);
    }
    return myContentManager;
  }

  @Override
  public ProcessHandler[] getRunningProcesses() {
    final List<ProcessHandler> handlers = new ArrayList<ProcessHandler>();
    for (RunContentDescriptor descriptor : getContentManager().getAllDescriptors()) {
      final ProcessHandler processHandler = descriptor.getProcessHandler();
      if (processHandler != null) {
        handlers.add(processHandler);
      }
    }
    return handlers.toArray(new ProcessHandler[handlers.size()]);
  }

  @Override
  public void compileAndRun(@NotNull final Runnable startRunnable,
                            @NotNull final ExecutionEnvironment env,
                            @Nullable final RunProfileState state,
                            @Nullable final Runnable onCancelRunnable) {
    long id = env.getExecutionId();
    if (id == 0) {
      id = env.assignNewExecutionId();
    }
    RunProfile profile = env.getRunProfile();

    if (profile instanceof RunConfiguration) {
      final RunConfiguration runConfiguration = (RunConfiguration)profile;
      final RunManagerImpl runManager = RunManagerImpl.getInstanceImpl(myProject);

      final List<BeforeRunTask> activeTasks = new ArrayList<BeforeRunTask>();
      activeTasks.addAll(runManager.getBeforeRunTasks(runConfiguration));

      DataContext context = env.getDataContext();
      final DataContext projectContext = context != null ? context : SimpleDataContext.getProjectContext(myProject);

      if (!activeTasks.isEmpty()) {
        final long finalId = id;
        final Long executionSessionId = new Long(id);
        ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
          /** @noinspection SSBasedInspection*/
          @Override
          public void run() {
            for (BeforeRunTask task : activeTasks) {
              if (myProject.isDisposed()) {
                return;
              }
              BeforeRunTaskProvider<BeforeRunTask> provider = BeforeRunTaskProvider.getProvider(myProject, task.getProviderId());
              if (provider == null) {
                LOG.warn("Cannot find BeforeRunTaskProvider for id='" + task.getProviderId() + "'");
                continue;
              }
              ExecutionEnvironment taskEnvironment = new ExecutionEnvironmentBuilder(env).setContentToReuse(null).build();
              taskEnvironment.setExecutionId(finalId);
              EXECUTION_SESSION_ID_KEY.set(taskEnvironment, executionSessionId);
              if (!provider.executeTask(projectContext, runConfiguration, taskEnvironment, task)) {
                if (onCancelRunnable != null) {
                  SwingUtilities.invokeLater(onCancelRunnable);
                }
                return;
              }
            }
            // important! Do not use DumbService.smartInvokelater here because it depends on modality state
            // and execution of startRunnable could be skipped if modality state check fails
            SwingUtilities.invokeLater(new Runnable() {
              @Override
              public void run() {
                if (!myProject.isDisposed()) {
                  DumbService.getInstance(myProject).runWhenSmart(startRunnable);
                }
              }
            });
          }
        });
      }
      else {
        startRunnable.run();
      }
    }
    else {
      startRunnable.run();
    }
  }

  @Override
  public void startRunProfile(@NotNull final RunProfileStarter starter, @NotNull final RunProfileState state,
                              @NotNull final Project project, @NotNull final Executor executor, @NotNull final ExecutionEnvironment env) {
    final RunContentDescriptor reuseContent =
      ExecutionManager.getInstance(project).getContentManager().getReuseContent(env);
    if (reuseContent != null) {
      reuseContent.setExecutionId(env.getExecutionId());
    }
    final RunProfile profile = env.getRunProfile();

    project.getMessageBus().syncPublisher(EXECUTION_TOPIC).processStartScheduled(executor.getId(), env);

    Runnable startRunnable = new Runnable() {
      @Override
      public void run() {
        if (project.isDisposed()) return;
        boolean started = false;
        try {
          project.getMessageBus().syncPublisher(EXECUTION_TOPIC).processStarting(executor.getId(), env);

          final RunContentDescriptor descriptor = starter.execute(project, executor, state, reuseContent, env);

          if (descriptor != null) {
            final Trinity<RunContentDescriptor, RunnerAndConfigurationSettings, Executor> trinity =
              Trinity.create(descriptor, env.getRunnerAndConfigurationSettings(), executor);
            myRunningConfigurations.add(trinity);
            Disposer.register(descriptor, new Disposable() {
              @Override
              public void dispose() {
                myRunningConfigurations.remove(trinity);
              }
            });
            ExecutionManager.getInstance(project).getContentManager().showRunContent(executor, descriptor, reuseContent);
            final ProcessHandler processHandler = descriptor.getProcessHandler();
            if (processHandler != null) {
              if (!processHandler.isStartNotified()) {
                processHandler.startNotify();
              }
              project.getMessageBus().syncPublisher(EXECUTION_TOPIC).processStarted(executor.getId(), env, processHandler);
              started = true;
              processHandler.addProcessListener(new ProcessExecutionListener(project, profile, processHandler));
            }
          }
        }
        catch (ExecutionException e) {
          ExecutionUtil.handleExecutionError(project, executor.getToolWindowId(), profile, e);
          LOG.info(e);
        }
        finally {
          if (!started) {
            project.getMessageBus().syncPublisher(EXECUTION_TOPIC).processNotStarted(executor.getId(), env);
          }
        }
      }
    };

    if (ApplicationManager.getApplication().isUnitTestMode()) {
      startRunnable.run();
    }
    else {
      compileAndRun(startRunnable, env, state, new Runnable() {
        @Override
        public void run() {
          if (!project.isDisposed()) {
            project.getMessageBus().syncPublisher(EXECUTION_TOPIC).processNotStarted(executor.getId(), env);
          }
        }
      });
    }
  }

  @Override
  public void startRunProfile(@NotNull RunProfileStarter starter, @NotNull RunProfileState state, @NotNull ExecutionEnvironment env) {
    startRunProfile(starter, state, env.getProject(), env.getExecutor(), env);
  }

  @Override
  public void restartRunProfile(@NotNull final Project project,
                                @NotNull final Executor executor,
                                @NotNull final ExecutionTarget target,
                                @Nullable final RunnerAndConfigurationSettings configuration,
                                @Nullable final ProcessHandler processHandler) {
    if (processHandler != null) {
      for (RunContentDescriptor descriptor : getContentManager().getAllDescriptors()) {
        final ProcessHandler handler = descriptor.getProcessHandler();
        if (handler == processHandler) {
          restartRunProfile(project, null, null, null, null, null, executor, target, configuration, descriptor);
          return;
        }
      }
    }
    restartRunProfile(project, null, null, null, null, null, executor, target, configuration, null);
  }

  @Override
  public void restartRunProfile(@NotNull Project project,
                                @NotNull Executor executor,
                                @NotNull ExecutionTarget target,
                                @Nullable RunnerAndConfigurationSettings configuration,
                                @Nullable RunContentDescriptor currentDescriptor) {
    restartRunProfile(project, null, null, null, null, null, executor, target, configuration, currentDescriptor);
  }

  @Override
  public void restartRunProfile(@Nullable ProgramRunner runner,
                                @NotNull ExecutionEnvironment environment,
                                @Nullable RunContentDescriptor currentDescriptor) {
    restartRunProfile(environment.getProject(),
                      environment.getDataContext(),
                      runner,
                      environment.getRunProfile(),
                      environment.getRunnerSettings(),
                      environment.getConfigurationSettings(),
                      environment.getExecutor(),
                      environment.getExecutionTarget(),
                      environment.getRunnerAndConfigurationSettings(), currentDescriptor);
  }


  private void restartRunProfile(@NotNull final Project project,
                                 @Nullable final DataContext context,
                                 @Nullable ProgramRunner r,
                                 @Nullable final RunProfile runProfile,
                                 @Nullable final RunnerSettings runnerSettings,
                                 @Nullable final ConfigurationPerRunnerSettings configurationPerRunnerSettings,
                                 @NotNull final Executor executor,
                                 @NotNull final ExecutionTarget target,
                                 @Nullable final RunnerAndConfigurationSettings configuration,
                                 @Nullable final RunContentDescriptor currentDescriptor) {
    final ProgramRunner runner = r != null ?
                                 r :
                                 RunnerRegistry.getInstance().getRunner(executor.getId(),
                                                                        configuration != null && configuration.getConfiguration() != null
                                                                        ? configuration.getConfiguration()
                                                                        : runProfile
                                 );
    if (configuration != null && runner == null) {
      LOG.error("Cannot find runner for " + configuration.getName());
      return;
    }

    final List<RunContentDescriptor> runningConfigurationsOfTheSameType = new ArrayList<RunContentDescriptor>();
    final List<RunContentDescriptor> runningIncompatibleConfigurations = new ArrayList<RunContentDescriptor>();

    if (configuration != null) {
      runningIncompatibleConfigurations.addAll(getIncompatibleRunningDescriptors(configuration));
    }
    if (configuration != null && configuration.isSingleton()) {
      runningConfigurationsOfTheSameType.addAll(getRunningDescriptorsOfTheSameConfigType(configuration));
    }
    else if (currentDescriptor != null) {
      runningConfigurationsOfTheSameType.add(currentDescriptor);
    }

    final List<RunContentDescriptor> runningConfigurationsToStop = ContainerUtil.concat(runningConfigurationsOfTheSameType,
                                                                                        runningIncompatibleConfigurations);
    if (!runningConfigurationsToStop.isEmpty()) {
      if (configuration != null) {
        if (!runningConfigurationsOfTheSameType.isEmpty()
            && (runningConfigurationsOfTheSameType.size() > 1 ||
                currentDescriptor == null ||
                runningConfigurationsOfTheSameType.get(0) != currentDescriptor) &&
            !userApprovesStopForSameTypeConfigurations(project, configuration.getName(), runningConfigurationsOfTheSameType.size())) {
          return;
        }
        if (!runningIncompatibleConfigurations.isEmpty()
            && !userApprovesStopForIncompatibleConfigurations(myProject, configuration.getName(), runningIncompatibleConfigurations)) {
          return;
        }
      }
      for (RunContentDescriptor descriptor : runningConfigurationsToStop) {
        stop(descriptor);
      }
    }

    Runnable runnable = new Runnable() {
      @Override
      public void run() {
        if (runner != null && ExecutorRegistry.getInstance().isStarting(project, executor.getId(), runner.getRunnerId())) {
          awaitingTerminationAlarm.addRequest(this, 100);
          return;
        }
        for (RunContentDescriptor descriptor : runningConfigurationsOfTheSameType) {
          ProcessHandler processHandler = descriptor.getProcessHandler();
          if (processHandler != null && !processHandler.isProcessTerminated()) {
            awaitingTerminationAlarm.addRequest(this, 100);
            return;
          }
        }
        start(project, context, runner, runProfile, runnerSettings, configurationPerRunnerSettings, configuration, executor, target,
              currentDescriptor);
      }
    };
    awaitingTerminationAlarm.addRequest(runnable, 50);
  }

  private static void start(@NotNull Project project,
                            @Nullable DataContext context,
                            @Nullable ProgramRunner runner,
                            @Nullable RunProfile runProfile,
                            @Nullable RunnerSettings runnerSettings,
                            @Nullable ConfigurationPerRunnerSettings configurationPerRunnerSettings,
                            @Nullable RunnerAndConfigurationSettings configuration,
                            @NotNull Executor executor,
                            @NotNull ExecutionTarget target,
                            @Nullable RunContentDescriptor descriptor) {
    Runnable restarter = descriptor != null ? descriptor.getRestarter() : null;
    if (runner != null && runProfile != null) {
      ProgramRunnerUtil.executeConfiguration(project, context, configuration, executor, target, descriptor,
                                             configuration != null && configuration.isEditBeforeRun(), runner, runProfile, false);
    }
    else if (configuration != null) {
      ProgramRunnerUtil.executeConfiguration(project, context, configuration, executor, target, descriptor, true);
    }
    else if (restarter != null) {
      restarter.run();
    }
  }

  private static boolean userApprovesStopForSameTypeConfigurations(Project project, String configName, int instancesCount) {
    RunManagerImpl runManager = RunManagerImpl.getInstanceImpl(project);
    final RunManagerConfig config = runManager.getConfig();
    if (!config.isRestartRequiresConfirmation()) return true;

    DialogWrapper.DoNotAskOption option = new DialogWrapper.DoNotAskOption() {
      @Override
      public boolean isToBeShown() {
        return config.isRestartRequiresConfirmation();
      }

      @Override
      public void setToBeShown(boolean value, int exitCode) {
        config.setRestartRequiresConfirmation(value);
      }

      @Override
      public boolean canBeHidden() {
        return true;
      }

      @Override
      public boolean shouldSaveOptionsOnCancel() {
        return false;
      }

      @Override
      public String getDoNotShowMessage() {
        return CommonBundle.message("dialog.options.do.not.show");
      }
    };
    return Messages.showOkCancelDialog(
      project,
      ExecutionBundle.message("rerun.singleton.confirmation.message", configName, instancesCount),
      ExecutionBundle.message("process.is.running.dialog.title", configName),
      ExecutionBundle.message("rerun.confirmation.button.text"),
      CommonBundle.message("button.cancel"),
      Messages.getQuestionIcon(), option) == Messages.OK;
  }

  private static boolean userApprovesStopForIncompatibleConfigurations(Project project,
                                                                       String configName,
                                                                       List<RunContentDescriptor> runningIncompatibleDescriptors) {
    RunManagerImpl runManager = RunManagerImpl.getInstanceImpl(project);
    final RunManagerConfig config = runManager.getConfig();
    if (!config.isStopIncompatibleRequiresConfirmation()) return true;

    DialogWrapper.DoNotAskOption option = new DialogWrapper.DoNotAskOption() {
      @Override
      public boolean isToBeShown() {
        return config.isStopIncompatibleRequiresConfirmation();
      }

      @Override
      public void setToBeShown(boolean value, int exitCode) {
        config.setStopIncompatibleRequiresConfirmation(value);
      }

      @Override
      public boolean canBeHidden() {
        return true;
      }

      @Override
      public boolean shouldSaveOptionsOnCancel() {
        return false;
      }

      @Override
      public String getDoNotShowMessage() {
        return CommonBundle.message("dialog.options.do.not.show");
      }
    };

    final StringBuilder names = new StringBuilder();
    for (final RunContentDescriptor descriptor : runningIncompatibleDescriptors) {
      String name = descriptor.getDisplayName();
      if (names.length() > 0) {
        names.append(", ");
      }
      names.append(StringUtil.isEmpty(name) ? ExecutionBundle.message("run.configuration.no.name")
                                                       : String.format("'%s'", name));
    }

    //noinspection DialogTitleCapitalization
    return Messages.showOkCancelDialog(
      project,
      ExecutionBundle.message("stop.incompatible.confirmation.message",
                              configName, names.toString(), runningIncompatibleDescriptors.size()),
      ExecutionBundle.message("incompatible.configuration.is.running.dialog.title", runningIncompatibleDescriptors.size()),
      ExecutionBundle.message("stop.incompatible.confirmation.button.text"),
      CommonBundle.message("button.cancel"),
      Messages.getQuestionIcon(), option) == Messages.OK;
  }

  private List<RunContentDescriptor> getRunningDescriptorsOfTheSameConfigType(
    @NotNull final RunnerAndConfigurationSettings configurationAndSettings) {
    return getRunningDescriptors(new Predicate<RunnerAndConfigurationSettings>() {
      @Override
      public boolean apply(@Nullable RunnerAndConfigurationSettings runningConfigurationAndSettings) {
        return configurationAndSettings == runningConfigurationAndSettings;
      }
    });
  }

  private List<RunContentDescriptor> getIncompatibleRunningDescriptors(
    @NotNull final RunnerAndConfigurationSettings configurationAndSettings) {
    final RunConfiguration configurationToCheckCompatibility = configurationAndSettings.getConfiguration();
    return getRunningDescriptors(new Predicate<RunnerAndConfigurationSettings>() {
      @Override
      public boolean apply(@Nullable RunnerAndConfigurationSettings runningConfigurationAndSettings) {
        if (runningConfigurationAndSettings == null) return false;
        RunConfiguration runningConfiguration = runningConfigurationAndSettings.getConfiguration();
        if (runningConfiguration == null || !(runningConfiguration instanceof CompatibilityAwareRunProfile)) return false;
        return ((CompatibilityAwareRunProfile)runningConfiguration).mustBeStoppedToRun(configurationToCheckCompatibility);
      }
    });
  }

  private List<RunContentDescriptor> getRunningDescriptors(
    Predicate<RunnerAndConfigurationSettings> condition) {
    List<RunContentDescriptor> result = new ArrayList<RunContentDescriptor>();
    for (Trinity<RunContentDescriptor, RunnerAndConfigurationSettings, Executor> trinity : myRunningConfigurations) {
      if (condition.apply(trinity.getSecond())) {
        ProcessHandler processHandler = trinity.getFirst().getProcessHandler();
        if (processHandler != null && !processHandler.isProcessTerminating() && !processHandler.isProcessTerminated()) {
          result.add(trinity.getFirst());
        }
      }
    }
    return result;
  }

  private static void stop(RunContentDescriptor runContentDescriptor) {
    ProcessHandler processHandler = runContentDescriptor != null ? runContentDescriptor.getProcessHandler() : null;
    if (processHandler == null) {
      return;
    }
    if (processHandler instanceof KillableProcess && processHandler.isProcessTerminating()) {
      ((KillableProcess)processHandler).killProcess();
      return;
    }

    if (processHandler.detachIsDefault()) {
      processHandler.detachProcess();
    }
    else {
      processHandler.destroyProcess();
    }
  }

  @Override
  @NotNull
  public String getComponentName() {
    return "ExecutionManager";
  }

  private static class ProcessExecutionListener extends ProcessAdapter {
    private final Project myProject;
    private final RunProfile myProfile;
    private final ProcessHandler myProcessHandler;

    public ProcessExecutionListener(Project project, RunProfile profile, ProcessHandler processHandler) {
      myProject = project;
      myProfile = profile;
      myProcessHandler = processHandler;
    }

    @Override
    public void processTerminated(ProcessEvent event) {
      if (myProject.isDisposed()) return;

      myProject.getMessageBus().syncPublisher(EXECUTION_TOPIC).processTerminated(myProfile, myProcessHandler);
      VirtualFileManager.getInstance().asyncRefresh(null);
    }

    @Override
    public void processWillTerminate(ProcessEvent event, boolean willBeDestroyed) {
      if (myProject.isDisposed()) return;

      myProject.getMessageBus().syncPublisher(EXECUTION_TOPIC).processTerminating(myProfile, myProcessHandler);
    }
  }
}
