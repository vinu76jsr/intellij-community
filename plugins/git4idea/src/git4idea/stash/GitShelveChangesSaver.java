/*
 * Copyright 2000-2011 JetBrains s.r.o.
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
package git4idea.stash;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.shelf.ShelveChangesManager;
import com.intellij.openapi.vcs.changes.shelf.ShelvedChangeList;
import com.intellij.openapi.vcs.changes.shelf.ShelvedChangesViewManager;
import com.intellij.openapi.vcs.impl.LocalChangesUnderRoots;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.continuation.ContinuationContext;
import com.intellij.util.continuation.TaskDescriptor;
import com.intellij.util.continuation.Where;
import git4idea.GitPlatformFacade;
import git4idea.commands.Git;
import git4idea.i18n.GitBundle;
import git4idea.rollback.GitRollbackEnvironment;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * @author Kirill Likhodedov
 */
public class GitShelveChangesSaver extends GitChangesSaver {
  private static final Logger LOG = Logger.getInstance(GitShelveChangesSaver.class);
  private final ShelveChangesManager myShelveManager;
  private final ShelvedChangesViewManager myShelveViewManager;
  private Map<String, ShelvedChangeList> myShelvedLists;

  public GitShelveChangesSaver(@NotNull Project project, GitPlatformFacade platformFacade, @NotNull Git git,
                               @NotNull ProgressIndicator indicator, String stashMessage) {
    super(project, platformFacade, git, indicator, stashMessage);
    myShelveManager = ShelveChangesManager.getInstance(myProject);
    myShelveViewManager = ShelvedChangesViewManager.getInstance(myProject);
  }

  @Override
  protected void save(@NotNull Collection<VirtualFile> rootsToSave) throws VcsException {
    LOG.info("save " + rootsToSave);
    final Map<String, Map<VirtualFile, Collection<Change>>> lists =
      new LocalChangesUnderRoots(myChangeManager, myPlatformFacade.getVcsManager(myProject)).getChangesByLists(rootsToSave);

    String oldProgressTitle = myProgressIndicator.getText();
    myProgressIndicator.setText(GitBundle.getString("update.shelving.changes"));
    List<VcsException> exceptions = new ArrayList<VcsException>(1);
    myShelvedLists = new HashMap<String, ShelvedChangeList>();

    for (Map.Entry<String, Map<VirtualFile, Collection<Change>>> entry : lists.entrySet()) {
      final Map<VirtualFile, Collection<Change>> map = entry.getValue();
      final Set<Change> changes = new HashSet<Change>();
      for (Collection<Change> changeCollection : map.values()) {
        changes.addAll(changeCollection);
      }
      if (! changes.isEmpty()) {
        final ShelvedChangeList list = GitShelveUtils.shelveChanges(myProject, myShelveManager, changes,
                                                                    myStashMessage + " [" + entry.getKey() + "]", exceptions, false);
        myShelvedLists.put(entry.getKey(), list);
      }
    }
    if (! exceptions.isEmpty()) {
      LOG.info("save " + exceptions, exceptions.get(0));
      myShelvedLists = null;  // no restore here since during shelving changes are not rolled back...
      throw exceptions.get(0);
    } else {
      for (VirtualFile root : rootsToSave) {
        GitRollbackEnvironment.resetHardLocal(myProject, root);
      }
    }
    myProgressIndicator.setText(oldProgressTitle);
  }

  protected void load(ContinuationContext context) {
    if (myShelvedLists != null) {
      LOG.info("load ");
      final String oldProgressTitle = myProgressIndicator.getText();
      myProgressIndicator.setText(GitBundle.getString("update.unshelving.changes"));
      context.next(new TaskDescriptor("", Where.AWT) {
        @Override
        public void run(ContinuationContext context) {
          myProgressIndicator.setText(oldProgressTitle);
        }
      });
      for (ShelvedChangeList list : myShelvedLists.values()) {
        GitShelveUtils.doSystemUnshelve(myProject, list, myShelveManager, context,
                                        getConflictLeftPanelTitle(), getConflictRightPanelTitle());
      }
    }
  }

  @Override
  protected boolean wereChangesSaved() {
    return myShelvedLists != null;
  }

  @Override
  public String getSaverName() {
    return "shelf";
  }

  @Override
  protected void showSavedChanges() {
    myShelveViewManager.activateView(myShelvedLists.get(myShelvedLists.keySet().iterator().next()));
  }

  @Override
  public void refresh() {
    // refreshed inside shelve manager
  }

}
